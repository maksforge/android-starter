package app.starter.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * `startActivity`, reporting failure rather than taking the app down with it.
 *
 * Every hand-off launches something it cannot be sure exists — a mail app, Play, an OEM settings
 * screen. One that is present but not exported to us throws `SecurityException`, which is the same
 * outcome from here as it not being there at all. Here rather than in `work/` so removing the
 * reminders leaves Support's hand-offs compiling.
 */
internal fun Context.startActivitySafely(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
