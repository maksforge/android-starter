package app.starter

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.starter.data.APP_DATABASE_FILE
import app.starter.data.APP_SCHEMA_VERSION
import app.starter.data.AppPreferences
import app.starter.data.PRESERVED_DIRECTORY
import app.starter.data.SchemaGate
import app.starter.data.ThemeMode
import app.starter.data.destructiveMigrationAllowed
import app.starter.data.preserveBeforeWipe
import app.starter.data.readUserVersion
import app.starter.data.schemaGateDecision
import app.starter.theme.applyThemeMode
import app.starter.work.ensureSweepEnqueued
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A database this build cannot open as it stands. The copy is already taken and the original is
 * still on disk untouched; what happens next depends on which build found it (ADR-0001).
 *
 * @param preservedCopy where the copy landed, under `filesDir`.
 * @param fromVersion the schema version the file on disk was written at.
 * @param toVersion the version this build expects.
 * @param wipeOnConsent whether continuing would destroy the file and let the app through. True in a
 *   debug build, where a schema bump is still free; false in a release build, where the open would
 *   throw instead — so the screen is a dead end offering the copy, not a button that destroys the
 *   user's history on a path where nothing was going to destroy it.
 */
data class SchemaMismatch(
    val preservedCopy: File,
    val fromVersion: Int,
    val toVersion: Int,
    val wipeOnConsent: Boolean,
)

/**
 * Holds the one [AppContainer] for the process, and **the wipe guard that stands in front of it**.
 *
 * ADR-0001's guard is *structural*, and this class is the structure. The pre-Room version check and
 * the copy-aside run in [onCreate] — four bytes out of a file header, no Room and no container
 * involved — and [container] sits behind a `lazy` that is forced only once any pending wipe has been
 * consented to. No Room object exists, so no collection of one can exist, and the property stays
 * true however the container grows later.
 *
 * The tempting alternative — leave the container constructed and merely stop it from *collecting* —
 * works today and is one eager `stateIn` away from silently breaking, in an app that will go on to
 * add background work at process start. A guard by absence-of-subscription is unwritten and
 * unenforceable, and it would be load-bearing for the only copy of data the user cannot retype.
 *
 * Kotlin note: `by lazy` computes on first read and caches — like a memoised getter, but here the
 * *first read* is the event that matters, which is why nothing may touch [container] before consent.
 */
class MainApplication :
    Application(),
    Configuration.Provider {
    /**
     * The scope for work that outlives any screen. `SupervisorJob` so one failed child does not
     * cancel its siblings — the closest JS analogue is `Promise.allSettled` semantics rather than
     * `Promise.all`.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _schemaMismatch = MutableStateFlow<SchemaMismatch?>(null)

    /** Non-null while a blocking schema screen should be shown instead of the app. */
    val schemaMismatch: StateFlow<SchemaMismatch?> = _schemaMismatch.asStateFlow()

    /**
     * The preference store, and the one thing outside the gate that may be read before it.
     *
     * It has to be: the theme is chosen before the first window exists, and DataStore is not the
     * database the guard is protecting.
     */
    lateinit var preferences: AppPreferences
        private set

    /**
     * The theme mode as it was on disk at process start, so the very first composition paints in the
     * right palette rather than flashing light and repainting.
     */
    var startupThemeMode: ThemeMode = ThemeMode.SYSTEM
        private set

    /**
     * **Nothing may read this until [schemaMismatch] is null.** Forcing the lazy constructs Room.
     */
    val container: AppContainer by lazy { AppContainer(this, preferences, applicationScope) }

    /**
     * WorkManager builds itself the first time `getInstance` is called, rather than from an
     * androidx.startup initializer inside a ContentProvider — which would run *before* this method,
     * and so before the guard below. See the manifest for the other half.
     */
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
                .build()

    override fun onCreate() {
        super.onCreate()

        preferences = AppPreferences(preferencesStore)

        // A blocking read, deliberately and exactly once. Everything else about DataStore is a Flow,
        // but this value has to be known before the first window is created — a Flow collected in a
        // composition arrives a frame later, which is a visible light flash for a user who chose
        // dark. One small file read at process start is the cheaper of the two costs.
        startupThemeMode = runBlocking { preferences.themeModeNow() }
        applyThemeMode(startupThemeMode)

        val databaseFile = getDatabasePath(APP_DATABASE_FILE)
        val onDisk = readUserVersion(databaseFile)

        when (schemaGateDecision(onDisk)) {
            SchemaGate.Open -> onDatabaseUsable()

            SchemaGate.Consent, SchemaGate.Refuse -> {
                // The copy is taken *before* anything decides what to do about it, so the file
                // exists whichever branch the user then takes — including "force-stop the app".
                val preserved =
                    preserveBeforeWipe(databaseFile, File(filesDir, PRESERVED_DIRECTORY))
                if (preserved == null) {
                    // Nothing was there to preserve after all, so there is nothing to refuse over.
                    onDatabaseUsable()
                } else {
                    _schemaMismatch.value =
                        SchemaMismatch(
                            preservedCopy = preserved,
                            fromVersion = onDisk,
                            toVersion = APP_SCHEMA_VERSION,
                            wipeOnConsent = destructiveMigrationAllowed(),
                        )
                }
            }
        }
    }

    /**
     * The user has read the screen and continued. Only reachable in a build where continuing is
     * survivable — a release build's screen is a dead end by design.
     */
    fun consentToWipe() {
        _schemaMismatch.value = null
        onDatabaseUsable()
    }

    /**
     * Everything that is allowed to touch the database, gathered in one place so it is obvious that
     * the gate above is the only thing standing in front of all of it.
     */
    private fun onDatabaseUsable() {
        applicationScope.launch {
            ensureSweepEnqueued(this@MainApplication)
        }
        settleTipsOnEveryForeground()
    }

    /**
     * Play refunds a purchase nobody acknowledged within three days, so a tip that cleared while the
     * app was closed — or was paid just before a process death — is consumed the next time the user
     * opens the app, whichever screen that is (ADR-0007). On *start*, not at process creation: a
     * broadcast or the sweep starts the process too, and binding Play for those wakes it for nobody.
     * Behind the gate, because the container holds the tip jar.
     */
    private fun settleTipsOnEveryForeground() {
        // Kotlin note: an `object :` expression is an anonymous class instance — the listener object
        // JS would pass inline. Added on the main thread, which `ProcessLifecycleOwner` requires.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { container.tipJar.settlePending() }
                }
            },
        )
    }
}
