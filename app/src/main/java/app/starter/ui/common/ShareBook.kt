package app.starter.ui.common

/**
 * Which share sheet is open, and which one was last taken as cancelled, so a pick reported only after
 * the app is back can be told from a pick on an older sheet. Main thread only.
 */
internal class ShareBook<S : Any>(
    private val id: (S) -> String,
) {
    private var open: S? = null
    private var cancelled: S? = null

    fun opened(share: S) {
        open = share
        cancelled = null
    }

    /** The share still waiting for a pick, now taken as cancelled; null when there is none. */
    fun returned(): S? =
        open?.also {
            open = null
            cancelled = it
        }

    /** True when the pick on [shareId] comes after its share was taken as cancelled, so it is owed its grant. */
    fun picked(shareId: String?): Boolean {
        if (shareId == null) return false
        if (open?.let(id) == shareId) {
            open = null
            return false
        }
        if (cancelled?.let(id) != shareId) return false
        cancelled = null
        return true
    }
}
