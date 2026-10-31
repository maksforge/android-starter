package app.starter.tip

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `a tip Play says is already owned is consumed at once and thanked once`() =
        runTest {
            val billing = FakeBilling(OwnedTip("owned-token", Purchase.PurchaseState.PURCHASED))
            val jar = TipJar(this) { listener -> billing.also { it.listener = listener } }
            val outcomes = mutableListOf<TipOutcome>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { jar.outcomes.toList(outcomes) }

            billing.listener(BillingResponseCode.ITEM_ALREADY_OWNED, null)
            advanceUntilIdle()

            assertEquals(listOf("owned-token"), billing.consumedTokens)
            assertEquals(listOf(TipOutcome.Thanked), outcomes)
        }

    @Test
    fun `a completed purchase is announced and a cancel is not`() {
        assertEquals(ListenerAction.Announce, listenerActionFor(BillingResponseCode.OK))
        assertEquals(ListenerAction.Ignore, listenerActionFor(BillingResponseCode.USER_CANCELED))
    }
}

private class FakeBilling(
    private vararg val owned: OwnedTip,
) : TipBilling {
    lateinit var listener: PurchasesListener
    val consumedTokens = mutableListOf<String>()

    override suspend fun connect() = true

    override suspend fun productDetails(): List<ProductDetails>? = null

    override suspend fun owned() = owned.toList()

    override suspend fun consume(token: String): BillingResult {
        consumedTokens += token
        return BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build()
    }

    override fun launch(
        activity: Activity,
        details: ProductDetails,
    ) = Unit
}
