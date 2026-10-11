package app.starter.tip

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val TAG = "StarterTip"

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
class TipJar(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val _offers = MutableStateFlow<List<TipOffer>>(emptyList())

    /** Cheapest first; empty until [refresh] has heard from Play, or for good if it never does. */
    val offers: StateFlow<List<TipOffer>> = _offers.asStateFlow()

    // Kotlin note: a `SharedFlow` with no replay is an event stream — an `EventEmitter`, not a value.
    // A thank-you belongs to the moment it happened, so a screen opened later must not receive it.
    private val _outcomes = MutableSharedFlow<TipOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<TipOutcome> = _outcomes.asSharedFlow()

    private val client: BillingClient =
        BillingClient
            .newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    scope.launch { announce(purchases) }
                }
            }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    private val connecting = Mutex()

    // The listener and the foreground settle can reach the same token at once; under this lock the
    // second one finds it in [consumed] instead of racing the first to Play.
    private val consuming = Mutex()
    private val consumed = mutableSetOf<String>()

    /** Asks Play for prices. */
    suspend fun refresh() {
        if (!connect()) return
        val (result, details) =
            client.queryProductDetails(
                QueryProductDetailsParams
                    .newBuilder()
                    .setProductList(
                        TIP_PRODUCT_IDS.map {
                            QueryProductDetailsParams.Product
                                .newBuilder()
                                .setProductId(it)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()
                        },
                    ).build(),
            )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "no prices: ${result.debugMessage}")
            return
        }
        _offers.value = details.orEmpty().mapNotNull(::offerOf).sortedBy { it.priceMicros }
    }

    /**
     * Consumes every tip Play still holds as owned: a pending payment that has since cleared, or one a
     * process death caught between paying and consuming. Run on every foreground by
     * `MainApplication`, because Play refunds what is not acknowledged in three days. Announces
     * nothing: what it finds may be any tip, from any day.
     */
    suspend fun settlePending() {
        if (!connect()) return
        val owned =
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            )
        if (owned.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "no purchases: ${owned.billingResult.debugMessage}")
            return
        }
        owned.purchasesList.forEach { settle(it) }
    }

    /** Opens Play's sheet. The answer arrives at the listener, because the sheet is Play's to close. */
    fun tip(
        activity: Activity,
        offer: TipOffer,
    ) {
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(offer.details)
                            .build(),
                    ),
                ).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "sheet refused: ${result.debugMessage}")
        }
    }

    /**
     * Settles the purchases of one listener callback and announces what **they** became, so an older
     * tip still pending cannot answer for this one, and a foreground settle that consumed it first
     * cannot swallow the thanks.
     */
    private suspend fun announce(purchases: List<Purchase>) {
        outcomeOf(purchases.map { settle(it) })?.let { _outcomes.tryEmit(it) }
    }

    private suspend fun settle(purchase: Purchase): Settled =
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
            val result =
                client
                    .consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(token).build())
                    .billingResult
            val settled = consumeSettled(result.responseCode)
            if (settled) consumed += token else Log.w(TAG, "consume failed: ${result.debugMessage}")
            settled
        }

    private suspend fun connect(): Boolean =
        connecting.withLock {
            if (client.isReady) return@withLock true
            // Kotlin note: `suspendCancellableCoroutine` turns a one-shot callback into a suspend
            // call, as `new Promise(resolve => api(resolve))` does. The library has no ktx form of
            // `startConnection`, so this is the hand-written one.
            suspendCancellableCoroutine { continuation ->
                client.startConnection(
                    object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            val ready = result.responseCode == BillingClient.BillingResponseCode.OK
                            if (!ready) Log.i(TAG, "Play Billing unavailable: ${result.debugMessage}")
                            if (continuation.isActive) continuation.resume(ready)
                        }

                        override fun onBillingServiceDisconnected() {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                )
            }
        }
}

private fun offerOf(details: ProductDetails): TipOffer? {
    val offer = details.oneTimePurchaseOfferDetails ?: return null
    return TipOffer(details.productId, offer.formattedPrice, offer.priceAmountMicros, details)
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
