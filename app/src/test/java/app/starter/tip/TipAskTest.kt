package app.starter.tip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TipAskTest {
    private val installedAt = Instant.parse("2026-09-01T08:00:00Z")
    private val monthLater = installedAt + TIP_ASK_AFTER

    private fun due(
        asked: Boolean = false,
        items: Int = TIP_ASK_AFTER_ITEMS,
        now: Instant = monthLater,
    ) = tipAskDue(asked, items, installedAt, now)

    @Test
    fun `due once installed a month with enough recorded`() {
        assertTrue(due())
    }

    @Test
    fun `not due a millisecond short of the month`() {
        assertFalse(due(items = 1_000, now = monthLater.minusMillis(1)))
    }

    @Test
    fun `not due one item short`() {
        assertFalse(due(items = TIP_ASK_AFTER_ITEMS - 1, now = monthLater.plus(TIP_ASK_AFTER)))
    }

    @Test
    fun `never again once answered`() {
        assertFalse(due(asked = true, items = 1_000, now = monthLater.plus(TIP_ASK_AFTER)))
    }
}
