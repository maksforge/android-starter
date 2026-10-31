package app.starter.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.starter.data.schemaBlocksBackgroundWork
import app.starter.ui.settings.inAppLanguage
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The one name every sweep is enqueued under. **Never two**: the invariant that makes this design
 * checkable is "exactly one enqueued work item exists at any time", and that is only true while
 * there is one name.
 */
const val SWEEP_WORK_NAME = "reminder-sweep"

/** When the sweep runs, absent a user's choice. */
val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(9, 0)

/**
 * **One worker for the whole app**, and the reason there is no per-reminder scheduled work to
 * cancel, orphan or lose (ADR-0003).
 *
 * The alternative — one scheduled work item per reminder — sounds simpler and is not: every item is
 * something to cancel when the reminder is edited, to re-enqueue on boot, and to reconcile after a
 * restore. Getting any of those wrong produces a notification that fires twice, or one that stops
 * firing with nothing to show for it. One sweep that asks "what is due today?" has none of those
 * states, because the answer is derived from the data every time.
 *
 * It ships doing almost nothing on purpose. What it proves is the path: that a worker runs at all,
 * that it survives a reboot, that it re-arms itself, and that it refuses to run over a database it
 * must not touch. Proving that on an empty database rather than underneath the first real reminder
 * means a missed notification later has one suspect instead of two.
 *
 * Kotlin note: `CoroutineWorker.doWork` is a `suspend` function, so the whole body already runs off
 * the main thread on WorkManager's own dispatcher — there is no `withContext(Dispatchers.IO)` to
 * add, and adding one would only move the work sideways.
 */
class ReminderSweepWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // The wipe guard, from the direction it exists for: the OS can start this process to run a
        // worker with no UI and no user present, and any worker that touches a repository forces the
        // container — which, at a stale schema, destroys the database in the background on a phone
        // nobody is looking at. Asked *before* anything else, and answered out of four bytes of the
        // file header rather than by opening anything.
        if (applicationContext.schemaBlocksBackgroundWork()) {
            // Success, and deliberately no re-enqueue. There is nothing wrong with the *work* — the
            // database is simply not this build's to open yet, and the consent screen is what
            // resolves that. Re-arming happens on the next launch, through the same path the boot
            // receiver uses.
            return Result.success()
        }

        // Wrapped, deliberately: this is the one place where a failure must not cost the *next*
        // sweep. A throw here would return `Result.failure` with the re-enqueue below unreached, and
        // the app would go quiet until the next launch or reboot — a far worse outcome than one
        // missed morning. Give each independent job its own `runCatching` rather than one around
        // all of them, so a failure in one does not cost the others their morning.
        runCatching {
            val context = applicationContext.inAppLanguage()
            context.ensureReminderChannels()
            // → Derive what is due today and post it. Nothing to derive yet.
        }

        // **The sweep re-arms itself, and this line is the whole chain.** A periodic work request
        // would be the obvious alternative and is worse: WorkManager's minimum period is 15 minutes
        // with a flex window it chooses, so "every day at 09:00" is not expressible. A one-time
        // request that enqueues the next one is exact to the minute and survives being run late.
        enqueueSweep(applicationContext, ExistingWorkPolicy.REPLACE)
        return Result.success()
    }
}

/**
 * Enqueue the sweep for its next slot if one is not already enqueued.
 *
 * `KEEP` rather than `REPLACE`: called from process start and from the boot receiver, where the
 * right move is to leave a good pending sweep alone. The worker itself uses `REPLACE`, because there
 * it *is* the one being replaced.
 */
suspend fun ensureSweepEnqueued(context: Context) {
    enqueueSweep(context, ExistingWorkPolicy.KEEP)
}

private fun enqueueSweep(
    context: Context,
    policy: ExistingWorkPolicy,
) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        SWEEP_WORK_NAME,
        policy,
        OneTimeWorkRequestBuilder<ReminderSweepWorker>()
            .setInitialDelay(delayUntilNextSlot(), TimeUnit.MILLISECONDS)
            .build(),
    )
}

/**
 * Milliseconds until the next [DEFAULT_REMINDER_TIME] in the phone's own zone.
 *
 * **The zone is read now, not cached.** A user who flies somewhere expects the reminder at nine in
 * the morning where they are, and a cached zone gives them nine in the morning where they were.
 */
private fun delayUntilNextSlot(): Long {
    val zone = ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    var next = now.with(DEFAULT_REMINDER_TIME)
    if (!next.isAfter(now)) next = next.plusDays(1).with(DEFAULT_REMINDER_TIME)
    return Duration.between(now, next).toMillis().coerceAtLeast(0)
}

/** Today in the phone's own zone. Extracted so a test can pass its own clock. */
fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
