package app.starter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Which colour scheme applies, regardless of what the phone is set to. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Everything the app remembers that is not a row in the database.
 *
 * **DataStore, not SharedPreferences.** SharedPreferences' `commit()` blocks and its `apply()` can
 * still write on the main thread at the wrong moment; DataStore is `suspend`-only and transactional,
 * so there is no API here that can stall a frame.
 *
 * Kotlin note: every read is a `Flow`, not a value. There is no "current value" to ask for
 * synchronously — the first emission arrives after a disk read. That matters at startup: a screen
 * collecting `themeMode` gets `SYSTEM` for one frame and the stored value on the next, which is a
 * visible flash. [themeModeNow] exists for the one caller that must have the answer *before* the
 * first frame, and it is `suspend` because reading a file is.
 *
 * ## Adding a preference
 *
 * A key, listed in `Keys.SETTINGS` or `Keys.KEPT`, a `Flow` that reads it with a default, and a
 * `suspend` setter. `AppPreferencesTest` fails until the key is in exactly one of the two lists, so
 * whoever adds it decides whether *Restore to default* resets it. Keep the default in the `Flow`
 * rather than writing it on first launch — a default that has been written to disk cannot be
 * changed in a later version without a migration, and a default that has not can.
 */
class AppPreferences(
    private val store: DataStore<Preferences>,
) {
    internal object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val MATERIAL_YOU = booleanPreferencesKey("material_you")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val TIP_ASKED = booleanPreferencesKey("tip_asked")

        /** What *Restore to default* removes: everything the user chose, so each reads its default again. */
        val SETTINGS: List<Preferences.Key<*>> =
            listOf(
                THEME_MODE,
                MATERIAL_YOU,
                REMINDERS_ENABLED,
            )

        /**
         * What *Restore to default* leaves alone: live state, one-time asks, and anything pointing at
         * a grant the system holds, which a reset would orphan rather than undo.
         */
        val KEPT: List<Preferences.Key<*>> =
            listOf(
                ONBOARDING_DONE,
                BACKUP_FOLDER_URI,
                TIP_ASKED,
            )
    }

    /**
     * Light, dark or follow-the-system.
     *
     * Unknown stored values fall back to [ThemeMode.SYSTEM] rather than throwing: a preference file
     * survives a downgrade, and a crash loop at startup is a far worse outcome than the wrong theme.
     * This is the opposite of the rule for enums stored in the *database*, where an unknown value
     * means the schema gate missed something and should be loud.
     */
    val themeMode: Flow<ThemeMode> =
        store.data.map { prefs ->
            prefs[Keys.THEME_MODE]?.let { name ->
                runCatching { enumValueOf<ThemeMode>(name) }.getOrNull()
            } ?: ThemeMode.SYSTEM
        }

    /**
     * Whether to take the wallpaper's palette instead of the app's own (ADR-0006).
     *
     * Defaults **off**. With it on, nothing on Android 12+ reads the generated scheme at all, so the
     * app's identity would be invisible on almost every device that runs it.
     */
    val materialYou: Flow<Boolean> = store.data.map { it[Keys.MATERIAL_YOU] ?: false }

    /**
     * Whether the user has opted into reminders.
     *
     * Separate from the OS notification permission on purpose, and both are checked before anything
     * is posted. The permission answers "may we", this answers "did they ask us to" — and a user who
     * granted the permission once and turned reminders off in the app has not revoked the permission.
     */
    val remindersEnabled: Flow<Boolean> = store.data.map { it[Keys.REMINDERS_ENABLED] ?: false }

    /** Whether first-run setup has been completed. */
    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    /**
     * The tree Uri the user picked for automatic backups, if any.
     *
     * Stored as a string because that is what `Uri.toString()`/`Uri.parse()` round-trip, and because
     * the persisted permission grant it refers to is held by the system, not here. A stored Uri whose
     * grant has been revoked reads back fine and fails at write time — so treat this as a *hint*, and
     * handle the failure where the write happens.
     */
    val backupFolderUri: Flow<String?> = store.data.map { it[Keys.BACKUP_FOLDER_URI] }

    /**
     * Whether the home screen's one tip ask has been answered, either way (ADR-0007). It does not reach
     * the next phone: `AppBackupAgent` backs up only the database and the thumbnails (ADR-0005), so
     * after a move the user is asked once more, a month after the new install.
     */
    val tipAsked: Flow<Boolean> = store.data.map { it[Keys.TIP_ASKED] ?: false }

    /**
     * The theme mode, read once, before any Activity exists.
     *
     * The one place a `suspend` read is worth its cost: `MainApplication.onCreate` needs the answer
     * to call `applyThemeMode` *before* the first window is created, and a `Flow` collected in a
     * composition arrives a frame too late — which is a light flash on every cold start for a user
     * who chose dark.
     */
    suspend fun themeModeNow(): ThemeMode = themeMode.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setMaterialYou(enabled: Boolean) {
        store.edit { it[Keys.MATERIAL_YOU] = enabled }
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        store.edit { it[Keys.REMINDERS_ENABLED] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun markTipAsked() {
        store.edit { it[Keys.TIP_ASKED] = true }
    }

    suspend fun setBackupFolderUri(uri: String?) {
        store.edit { prefs ->
            if (uri == null) prefs.remove(Keys.BACKUP_FOLDER_URI) else prefs[Keys.BACKUP_FOLDER_URI] = uri
        }
    }

    /**
     * Every setting back to how the app came, in one edit. The keys are removed rather than written,
     * so each reads its default again and stays free to change in a later version.
     */
    suspend fun restoreDefaults() {
        store.edit { prefs -> Keys.SETTINGS.forEach { prefs.remove(it) } }
    }
}
