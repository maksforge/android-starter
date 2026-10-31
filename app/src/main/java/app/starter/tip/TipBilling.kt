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
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

internal const val TAG = "StarterTip"

/** A purchase as [TipJar] needs it; [purchaseState] is one of `Purchase.PurchaseState`. */
internal class OwnedTip(
    val purchaseToken: String,
    val purchaseState: Int,
)

/** Play's purchases listener: a response code, and the purchases it is about when there are any. */
internal typealias PurchasesListener = (responseCode: Int, purchases: List<OwnedTip>?) -> Unit

/** The Play calls the tip makes. [PlayTipBilling] is the real one; a unit test drives [TipJar] with a fake. */
internal interface TipBilling {
    suspend fun connect(): Boolean

    /** `null` when Play would not answer. */
    suspend fun productDetails(): List<ProductDetails>?

    /** `null` when Play would not answer. */
    suspend fun owned(): List<OwnedTip>?

    suspend fun consume(token: String): BillingResult

    fun launch(
        activity: Activity,
        details: ProductDetails,
    )
}

internal class PlayTipBilling(
    context: Context,
    listener: PurchasesListener,
) : TipBilling {
    private val client: BillingClient =
        BillingClient
            .newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                listener(result.responseCode, purchases?.map { OwnedTip(it.purchaseToken, it.purchaseState) })
            }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    private val connecting = Mutex()

    override suspend fun connect(): Boolean =
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

    override suspend fun productDetails(): List<ProductDetails>? {
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
            return null
        }
        return details.orEmpty()
    }

    override suspend fun owned(): List<OwnedTip>? {
        val owned =
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            )
        if (owned.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "no purchases: ${owned.billingResult.debugMessage}")
            return null
        }
        return owned.purchasesList.map { OwnedTip(it.purchaseToken, it.purchaseState) }
    }

    override suspend fun consume(token: String): BillingResult =
        client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(token).build()).billingResult

    override fun launch(
        activity: Activity,
        details: ProductDetails,
    ) {
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(details)
                            .build(),
                    ),
                ).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "sheet refused: ${result.debugMessage}")
        }
    }
}
