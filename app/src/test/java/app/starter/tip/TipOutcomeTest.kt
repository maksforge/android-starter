package app.starter.tip

import com.android.billingclient.api.BillingClient.BillingResponseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipOutcomeTest {
    @Test
    fun `a consumed tip is thanked even beside an older pending one`() {
        assertEquals(TipOutcome.Thanked, outcomeOf(listOf(Settled.Pending, Settled.Consumed)))
    }

    @Test
    fun `a tip still pending says so`() {
        assertEquals(TipOutcome.Pending, outcomeOf(listOf(Settled.Pending)))
    }

    @Test
    fun `a tip that could not be consumed announces nothing`() {
        assertNull(outcomeOf(listOf(Settled.Unsettled)))
        assertNull(outcomeOf(emptyList()))
    }

    @Test
    fun `a token the other path already consumed counts as settled`() {
        assertTrue(consumeSettled(BillingResponseCode.OK))
        assertTrue(consumeSettled(BillingResponseCode.ITEM_NOT_OWNED))
    }

    @Test
    fun `any other consume failure leaves the tip for the next foreground`() {
        assertFalse(consumeSettled(BillingResponseCode.SERVICE_UNAVAILABLE))
        assertFalse(consumeSettled(BillingResponseCode.ERROR))
        assertFalse(consumeSettled(BillingResponseCode.SERVICE_DISCONNECTED))
    }
}
