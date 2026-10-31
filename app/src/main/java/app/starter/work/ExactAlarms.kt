package app.starter.work

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.starter.ui.common.startActivitySafely

/**
 * Whether this app may place an **exact** alarm right now.
 *
 * `SCHEDULE_EXACT_ALARM` is in the manifest and `USE_EXACT_ALARM` deliberately is not (ADR-0003,
 * and `scripts/aab-permissions.py` forbids it in the artifact):
 * the latter is auto-granted and never revoked, but Play permits it only for apps whose core
 * function is an alarm clock or a calendar, and few apps' is.
 * The one we do declare is **denied by default** at `targetSdk` 36, so nothing here assumes it.
 *
 * Below API 31 there is no permission to hold — exact alarms were simply allowed — so the answer is
 * yes, and it is a real yes rather than a hedge.
 *
 * Read fresh at every use, never cached. The user can revoke it from system settings while the app
 * is in the background, and on Android 14+ revoking it also cancels every pending exact alarm this
 * app placed (ADR-0003).
 */
fun Context.canScheduleExactAlarms(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarms = getSystemService(AlarmManager::class.java) ?: return false
    return alarms.canScheduleExactAlarms()
}

/**
 * Opens the system screen where the user grants exact alarms, and that is the end of it.
 *
 * **The ask is a deep link, not a dialog.** There is no runtime-permission path for this one and no
 * result to read, which is why every caller re-reads [canScheduleExactAlarms] on resume instead of
 * waiting for an answer. Same shape as [openBatteryOptimisationSettings], and for the same reason:
 * the app explains the state, then hands the decision over.
 *
 * The app-specific action first, the whole list second. `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` is
 * unrestricted — unlike battery optimisation's app-specific sibling — so the good one is reachable
 * here, and the fallbacks exist for skins that removed the screen rather than for a permission we
 * are not allowed to name.
 *
 * @return whether anything opened, so the caller can say so rather than guess.
 */
fun Context.openExactAlarmSettings(): Boolean {
    // Nothing to open below 31: the screen does not exist, because neither does the permission.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val intents =
        listOf(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)),
            // Without the package Uri some skins open the full alarm-permission list, which is one
            // scroll away from us rather than nowhere.
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    for (intent in intents) {
        // NEW_TASK, so Settings opens as its own task rather than being pushed onto this app's —
        // without it the user cannot bring the app forward again while they are in there.
        if (startActivitySafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true
    }
    return false
}
