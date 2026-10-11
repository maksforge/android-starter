package app.starter.tip

import java.time.Duration
import java.time.Instant

/** Items recorded before the tip is mentioned. Replace with your domain's own measure of use. */
const val TIP_ASK_AFTER_ITEMS = 10

/** How long the app has to have been installed before the tip is mentioned. */
val TIP_ASK_AFTER: Duration = Duration.ofDays(30)

/**
 * Whether the home screen should mention the tip — **once per install, to someone who already uses
 * the app** (ADR-0007).
 *
 * [installedAt] is the package's `firstInstallTime`, which a restore onto a new phone does not carry,
 * so a moved install waits its own month. [asked] lives in preferences, which Auto Backup carries, so
 * a user who has answered is never asked on their next phone.
 *
 * Add a guard for any moment the ask would be tone-deaf: the first app to adopt this never asks while
 * one of the user's animals is unwell.
 */
fun tipAskDue(
    asked: Boolean,
    items: Int,
    installedAt: Instant,
    now: Instant,
): Boolean =
    !asked &&
        items >= TIP_ASK_AFTER_ITEMS &&
        Duration.between(installedAt, now) >= TIP_ASK_AFTER
