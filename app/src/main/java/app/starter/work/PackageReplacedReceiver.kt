package app.starter.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delivered when this app has just been replaced by an update.
 *
 * **Why an update needs its own entry point.** A schema-bumping update leaves the database at the
 * old version until something opens it, and every background entry point correctly declines to work
 * over a version it cannot open (`schemaBlocksBackgroundWork`). Without this receiver the first
 * reminder after an update goes unposted and nothing recovers until the user opens the app by hand.
 *
 * It only *enqueues*: the migrating happens inside the worker, which has minutes where a receiver
 * has ten seconds.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        rebuildInBackground(context) { ensureSweepEnqueued(it) }
    }
}
