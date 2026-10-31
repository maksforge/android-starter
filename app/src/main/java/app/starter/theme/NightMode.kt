package app.starter.theme

import androidx.appcompat.app.AppCompatDelegate
import app.starter.data.ThemeMode

/**
 * The **window** half of the light/dark override (ADR-0006).
 *
 * [AppTheme] picks the Compose colour scheme, and that is all Compose can reach. It is not all the
 * user sees. The window background is painted from `Theme.App`, a `DayNight` theme, *before*
 * Compose composes anything, and the system-bar scrim behind the status and navigation bars is a
 * `values-night/` colour resolved at inflation. Both follow Android's own configuration, so a
 * Compose-only override leaves them following the **phone** while the app follows the **user** —
 * the exact mismatch the four `colors.xml` qualifiers exist to prevent, and it shows plainly on
 * API 26–28 where there is no system dark mode to have agreed with by accident.
 *
 * `AppCompatDelegate.setDefaultNightMode` moves the configuration itself, so all three agree. It is
 * process-wide and process-*only*: AppCompat persists an application locale but not a night mode,
 * which is why `AppPreferences.themeMode` exists at all and why this has to be called again on
 * every cold start.
 *
 * ## When it is called
 *
 * Three times, and each is load-bearing:
 *
 * - `MainApplication.onCreate`, before any Activity exists, so the very first window is right.
 * - `SettingsViewModel.setThemeMode`, when the user taps a chip. List `uiMode` in the activity's
 *   `configChanges` and AppCompat hands the running Activity an `onConfigurationChanged` instead of
 *   recreating it — the app repaints in place rather than blinking through a restart.
 * - `SettingsViewModel.restoreDefaults`, which resets the stored mode and would otherwise leave the
 *   window on the old one until the next cold start.
 *
 * Kotlin note: a top-level function, not a method on [ThemeMode]. The enum lives in `data/` and
 * describes a stored preference; teaching it about AppCompat would put an Android UI dependency
 * inside the layer that is meant not to have one.
 */
fun applyThemeMode(mode: ThemeMode) {
    AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            // MODE_NIGHT_FOLLOW_SYSTEM, not MODE_NIGHT_UNSPECIFIED: "unspecified" is the
            // per-Activity value meaning "defer to the default", and setting it *as* the default is
            // the one combination AppCompat treats as a no-op.
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        },
    )
}
