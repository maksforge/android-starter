package app.starter.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.starter.ui.common.startActivitySafely

/**
 * Xiaomi's autostart manager. The activity that decides, on HyperOS, whether this app's background
 * work is allowed to run at all (ADR-0003) — and the one with no readable state anywhere. Named
 * explicitly in the manifest's `<queries>` so it can be looked up at all.
 *
 * **This file is split out of `BatteryExemption.kt` on purpose**, which now holds only the
 * battery-optimisation half. The two are not the same kind of thing — one is a state the app can
 * read and re-read, the other is a grant it can never see — and while they shared a file named for
 * one of them, there was no way to tell the live code from the waiting code. An unused defence and a
 * working one look identical from the outside. The first app built from this template split them the
 * day its reboot-restore feature made this half live.
 */
private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
private const val MIUI_AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

/**
 * Whether this phone has Xiaomi's autostart screen at all.
 *
 * Answerable only because the manifest declares the package in `<queries>`; without that,
 * package-visibility filtering on API 30+ returns null for an activity that would have launched
 * perfectly well. It is a **live read**, which is what lets a settings row about surviving a restart
 * be *absent* on a phone with no such screen rather than a dead button on every other vendor's
 * device.
 */
fun Context.hasAutostartSettings(): Boolean = autostartIntent().resolveActivity(packageManager) != null

/**
 * Opens Xiaomi's autostart manager, and that is the end of the app's involvement.
 *
 * **Offered once and claimed never** (ADR-0003). Launching this returns no result and the setting
 * has no public state, so the app cannot know afterwards what the user did — and a checkbox asking
 * them to confirm would have the app repeating the user's own guess back to them as its assurance,
 * which is that ADR's central hazard sourced from a new place.
 *
 * There is no in-app re-read either, and there never will be: `AUTO_START` is not in `cmd appops`'
 * vocabulary, and the OEM screen's own `checked` attribute reads false on granted rows. The state is
 * recoverable only host-side, by `scripts/device-gate.py` scraping that screen over `adb`. So this
 * is the case `PLAN.md` rule 4 names as the exception that proves the clause: the app still owns the
 * hand-off and still owns saying what a denial costs — a permanent row that makes no claim, never a
 * `WarningBanner`, which could not clear. What stands behind the claim that the grant matters is an
 * overnight Doze run on a real device (`scripts/doze-capture.sh`).
 */
fun Context.openAutostartSettings(): Boolean =
    startActivitySafely(autostartIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun autostartIntent(): Intent =
    Intent().setComponent(ComponentName(MIUI_SECURITY_CENTER, MIUI_AUTOSTART_ACTIVITY))
