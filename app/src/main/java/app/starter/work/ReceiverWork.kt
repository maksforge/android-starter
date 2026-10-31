package app.starter.work

import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs [work] off the main thread while holding the broadcast open, and lets go when it finishes.
 *
 * `onReceive` runs on the **main thread** and the receiver is considered finished the moment it
 * returns — after which the process is a candidate for death. Everything these receivers do is
 * disk-bound, so doing it inline would be a StrictMode violation on the main thread and doing it in
 * a bare thread would race the process teardown. `goAsync()` is the platform's answer to both: it
 * hands back a token that keeps the broadcast alive until `finish()`.
 *
 * Kotlin note: `CoroutineScope(Dispatchers.IO).launch` is a fire-and-forget background job — the
 * closest thing to an un-awaited `async` call in JS. There is no lifecycle to scope it to here (a
 * receiver has none), which is exactly why the `finish()` in `finally` is not optional: it is what
 * tells Android the work is over, and skipping it on a throw would hold the process open for the
 * ten seconds Android allows before killing it anyway.
 */
internal fun BroadcastReceiver.rebuildInBackground(
    context: Context,
    work: suspend (Context) -> Unit,
) {
    val pending = goAsync()
    // The receiver's own Context is short-lived; the application one outlives the broadcast.
    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        try {
            work(appContext)
        } finally {
            pending.finish()
        }
    }
}
