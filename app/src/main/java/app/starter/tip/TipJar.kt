package app.starter.tip

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Must match the one-time products in Play Console exactly; a missing one is simply not offered. */
val TIP_PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large", "tip_xlarge")

/**
 * One tip as Play prices it for this user. [price] is Play's own formatted string in their
 * currency, and the only price the UI may show.
 */
class TipOffer internal constructor(
    val productId: String,
    val price: String,
    internal val priceMicros: Long,
    internal val details: ProductDetails,
)

enum class TipOutcome {
    Thanked,

    /** Paid by a method that settles later, such as cash at a shop. Play finishes it. */
    Pending,
}

/**
 * The tip, through Play Billing (ADR-0007).
 *
 * **Every tip is consumed, never just acknowledged**: consuming lets the same product be bought again
 * and also counts as the acknowledgement Play requires within three days. Nothing is unlocked, so
 * there is no entitlement to remember.
 *
 * **Held by `AppContainer`, not a `ViewModel`**, because a purchase can finish after the screen that
 * opened Play's sheet is gone, and its consume has to run anyway.
 *
 * Unavailable is a normal state: no Play, the debug `applicationId` Play has never heard of, or
 * products not yet created all leave [offers] empty, and the UI then shows no tip at all.
 */
class TipJar internal constructor(
    private val scope: CoroutineScope,
    billingFor: (PurchasesListener) -> TipBilling,
) {
    constructor(context: Context, scope: CoroutineScope) : this(scope, { PlayTipBilling(context, it) })

    private val _offers = MutableStateFlow<List<TipOffer>>(emptyList())

    /** Cheapest first; empty until [refresh] has heard from Play, or for good if it never does. */
    val offers: StateFlow<List<TipOffer>> = _offers.asStateFlow()

    // Kotlin note: a `SharedFlow` with no replay is an event stream — an `EventEmitter`, not a value.
    // A thank-you belongs to the moment it happened, so a screen opened later must not receive it.
    private val _outcomes = MutableSharedFlow<TipOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<TipOutcome> = _outcomes.asSharedFlow()

    private val billing = billingFor(::onPurchasesUpdated)

    // The listener and the foreground settle can reach the same token at once; under this lock the
    // second one finds it in [consumed] instead of racing the first to Play.
    private val consuming = Mutex()
    private val consumed = mutableSetOf<String>()

    /** Asks Play for prices. */
    suspend fun refresh() {
        if (!billing.connect()) return
        val details = billing.productDetails() ?: return
        _offers.value = details.mapNotNull(::offerOf).sortedBy { it.priceMicros }
    }

    /**
     * Consumes every tip Play still holds as owned: a pending payment that has since cleared, or one a
     * process death caught between paying and consuming. Run on every foreground by
     * `MainApplication`, because Play refunds what is not acknowledged in three days. Announces
     * nothing: what it finds may be any tip, from any day.
     */
    suspend fun settlePending() = settleOwned(announce = false)

    private suspend fun settleOwned(announce: Boolean) {
        if (!billing.connect()) return
        val settled = billing.owned()?.map { settle(it) } ?: return
        if (announce) outcomeOf(settled)?.let { _outcomes.tryEmit(it) }
    }

    /** Opens Play's sheet. The answer arrives at the listener, because the sheet is Play's to close. */
    fun tip(
        activity: Activity,
        offer: TipOffer,
    ) = billing.launch(activity, offer.details)

    private fun onPurchasesUpdated(
        responseCode: Int,
        purchases: List<OwnedTip>?,
    ) {
        when (listenerActionFor(responseCode)) {
            ListenerAction.Announce -> purchases?.let { scope.launch { announce(it) } }
            ListenerAction.SettleOwned -> scope.launch { settleOwned(announce = true) }
            ListenerAction.Ignore -> Unit
        }
    }

    /**
     * Settles the purchases of one listener callback and announces what **they** became, so an older
     * tip still pending cannot answer for this one, and a foreground settle that consumed it first
     * cannot swallow the thanks.
     */
    private suspend fun announce(purchases: List<OwnedTip>) {
        outcomeOf(purchases.map { settle(it) })?.let { _outcomes.tryEmit(it) }
    }

    private suspend fun settle(purchase: OwnedTip): Settled =
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED ->
                if (consume(purchase.purchaseToken)) Settled.Consumed else Settled.Unsettled
            Purchase.PurchaseState.PENDING -> Settled.Pending
            else -> Settled.Unsettled
        }

    /** One consume per purchase token covers its whole quantity, so quantity is never read here. */
    private suspend fun consume(token: String): Boolean =
        consuming.withLock {
            if (token in consumed) return@withLock true
            val result = billing.consume(token)
            val settled = consumeSettled(result.responseCode)
            if (settled) consumed += token else Log.w(TAG, "consume failed: ${result.debugMessage}")
            settled
        }
}

private fun offerOf(details: ProductDetails): TipOffer? {
    val offer = details.oneTimePurchaseOfferDetails ?: return null
    return TipOffer(details.productId, offer.formattedPrice, offer.priceAmountMicros, details)
}

internal enum class ListenerAction { Announce, SettleOwned, Ignore }

/**
 * `ITEM_ALREADY_OWNED` means an earlier tip of the same product is still unconsumed, and Play will
 * not sell it again until it is. Settling what is owned there and then lets the user's next tap work,
 * instead of waiting for the next foreground.
 */
internal fun listenerActionFor(responseCode: Int): ListenerAction =
    when (responseCode) {
        BillingClient.BillingResponseCode.OK -> ListenerAction.Announce
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ListenerAction.SettleOwned
        else -> ListenerAction.Ignore
    }

/** What one purchase in a listener callback came to. */
internal enum class Settled { Consumed, Pending, Unsettled }

/** A consumed tip outranks a pending one in the same callback: the user has paid. */
internal fun outcomeOf(settled: List<Settled>): TipOutcome? =
    when {
        Settled.Consumed in settled -> TipOutcome.Thanked
        Settled.Pending in settled -> TipOutcome.Pending
        else -> null
    }

/**
 * `ITEM_NOT_OWNED` is Play's answer for a token already consumed — by the other path, or by a run
 * a process death cut short — so the tip is settled either way. Any other failure leaves it owned,
 * and the next foreground tries again before Play's three days run out.
 */
internal fun consumeSettled(responseCode: Int): Boolean =
    responseCode == BillingClient.BillingResponseCode.OK ||
        responseCode == BillingClient.BillingResponseCode.ITEM_NOT_OWNED
