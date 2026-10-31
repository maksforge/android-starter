package app.starter.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preferences by lazy {
        AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("app.preferences_pb") })
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun `every key is either a setting that Restore removes or kept, never both or neither`() {
        // Kotlin note: an `object`'s `val`s compile to static fields on its class, so plain Java
        // reflection finds every key declared there without kotlin-reflect.
        val declared =
            AppPreferences.Keys::class.java.declaredFields
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .map { field ->
                    field.isAccessible = true
                    field.get(null) as Preferences.Key<*>
                }
        assertTrue(declared.isNotEmpty())
        for (key in declared) {
            val lists = listOf(AppPreferences.Keys.SETTINGS, AppPreferences.Keys.KEPT).count { key in it }
            assertEquals(key.name, 1, lists)
        }
        assertEquals(declared.toSet(), (AppPreferences.Keys.SETTINGS + AppPreferences.Keys.KEPT).toSet())
    }

    @Test
    fun `restoring resets every setting and keeps state and grants`() =
        runBlocking {
            with(preferences) {
                setThemeMode(ThemeMode.DARK)
                setMaterialYou(true)
                setRemindersEnabled(true)
                setOnboardingDone(true)
                setBackupFolderUri("content://tree")
                markTipAsked()

                restoreDefaults()

                assertEquals(ThemeMode.SYSTEM, themeMode.first())
                assertFalse(materialYou.first())
                assertFalse(remindersEnabled.first())
                assertTrue(onboardingDone.first())
                assertEquals("content://tree", backupFolderUri.first())
                assertTrue(tipAsked.first())
            }
        }
}
