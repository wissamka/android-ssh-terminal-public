package dev.wissam.sshterm.core.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.annotation.MainThread
import com.android.billingclient.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wissam.sshterm.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class PurchaseUiState(
    val entitlement: Entitlement = Entitlement(),
    val price: String? = null,
    val busy: Boolean = false,
    val message: String = "Pro purchases are not configured in this build.",
)

/** Application-scoped, client-only billing. All entry points/callbacks run on the main thread.
 * No Activity retained. No receipts, hosts, keys or analytics sent to an application backend.
 */
@Singleton
class PlayBillingRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val productId = BuildConfig.PLAY_PRO_PRODUCT_ID
    private val signature = ReceiptSignature(BuildConfig.PLAY_BILLING_PUBLIC_KEY)
    val ownerDevelopmentPro = DevelopmentAccess.ownerPro(BuildConfig.DEBUG, BuildConfig.OWNER_PRO)
    private val configured = !ownerDevelopmentPro && productId.matches(Regex("[a-z0-9][a-z0-9_.]*")) && signature.configured
    // Launch safety: build configuration, NOT a successful network lookup, controls restrictions.
    // An outage must not turn enforcement off or create a paid entitlement.
    val enforcesFreeLimits: Boolean get() = configured
    val freeLimits = FreeTierLimits()
    private val receiptFile = AtomicFile(File(context.noBackupFilesDir, "play-pro-receipt.json"))
    private val mutableState = MutableStateFlow(PurchaseUiState(entitlement = Entitlement(
        pro = configured && readCachedReceipt(),
        availability = if (configured) BillingAvailability.CHECKING else BillingAvailability.UNCONFIGURED,
    ), message = if (ownerDevelopmentPro) "Owner development Pro — no purchase or billing verification."
        else "Pro purchases are not configured in this build."))
    val state = mutableState.asStateFlow()
    private var details: ProductDetails? = null
    private var offerToken: String? = null
    private var connecting = false
    private var querying = false
    private var queryAgain = false
    private val receiptPersistence = ReceiptPersistenceGuard()
    private var productGeneration = 0L
    private val main = Handler(Looper.getMainLooper())
    private val client = BillingClient.newBuilder(context)
        .setListener { result, _ -> onMain {
            mutableState.update { it.copy(busy = false) }
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK,
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                BillingClient.BillingResponseCode.USER_CANCELED -> message("Purchase cancelled. Your access is unchanged.")
                else -> unavailable("Purchase could not complete. Retry or restore purchases.")
            }
        } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Call on app foreground, purchase-screen entry and explicit Restore/Retry. */
    @MainThread
    fun refresh() {
        if (!configured) return
        if (connecting) return
        if (client.isReady) { queryOwnership(); loadProduct(); return }
        connecting = true
        message("Connecting to Google Play…")
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = onMain {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryOwnership()
                    loadProduct()
                } else unavailable("Google Play is unavailable. Saved Pro access remains available. Retry when online.")
            }
            override fun onBillingServiceDisconnected() = onMain {
                connecting = false
                unavailable("Google Play disconnected. Saved Pro access remains available.")
            }
        })
    }

    private fun queryOwnership() {
        if (querying) { queryAgain = true; return }
        querying = true
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases -> onMain {
            querying = false
            mutableState.update { it.copy(busy = false) }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                unavailable("Could not check purchases. Existing access is unchanged; retry when online.")
            } else {
                val matching = purchases.filter { productId in it.products }
                val purchased = matching.firstOrNull { verified(it) }
                // A malformed/invalid matching receipt is not evidence of a refund.
                val invalid = matching.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED && !verified(it)
                }
                if (invalid) {
                    unavailable("Purchase verification failed. Retry restore or contact support.")
                } else {
                    val pending = matching.any { it.purchaseState == Purchase.PurchaseState.PENDING }
                    mutableState.update { it.copy(
                        entitlement = EntitlementPolicy.reconcile(it.entitlement, true, purchased != null, pending),
                        message = when {
                            purchased != null -> "Pro is active."
                            pending -> "Payment pending. Pro activates after Google Play confirms payment."
                            else -> "No Pro purchase found for the current Google Play account."
                        },
                    ) }
                    val ticket = receiptPersistence.confirmedOwnership(purchased?.purchaseToken)
                    if (purchased == null) receiptFile.delete()
                    else if (purchased.isAcknowledged) cache(purchased)
                    else client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchased.purchaseToken).build()) { acknowledgement -> onMain {
                        // Only confirmed ownership changes invalidate this receipt. Merely starting
                        // a refresh (or failing it) must not discard successful acknowledgement.
                        if (ticket != null && receiptPersistence.isCurrent(ticket)) {
                            if (acknowledgement.responseCode == BillingClient.BillingResponseCode.OK) {
                                receiptPersistence.persistIfCurrent(ticket) { cache(purchased) }
                            }
                            else message("Pro verified; purchase confirmation needs a retry. Open this app online within 3 days to avoid an automatic refund.")
                        }
                    } }
                }
            }
            if (queryAgain) { queryAgain = false; queryOwnership() }
        } }
    }

    private fun loadProduct() {
        val request = ++productGeneration
        details = null
        offerToken = null
        mutableState.update { it.copy(price = null) }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product)).build()) { result, response -> onMain {
            if (request != productGeneration || !client.isReady) return@onMain
            val found = response.productDetailsList.firstOrNull { it.productId == productId }
            // This integration intentionally supports one permanent buy option, no rentals/offers.
            val offer = found?.oneTimePurchaseOfferDetailsList?.singleOrNull()
            if (result.responseCode == BillingClient.BillingResponseCode.OK && offer != null &&
                offer.rentalDetails == null && offer.preorderDetails == null && offer.offerId == null) {
                details = found
                offerToken = offer.offerToken
                mutableState.update { it.copy(price = offer.formattedPrice) }
            } else {
                message("Pro is currently unavailable to buy. You can still restore an existing purchase.")
            }
        } }
    }

    @MainThread
    fun purchase(activity: Activity) {
        val product = details ?: return
        val token = offerToken ?: return
        if (!configured || state.value.busy || state.value.entitlement.pro || state.value.entitlement.pending) return
        if (!client.isReady) { refresh(); return }
        mutableState.update { it.copy(busy = true, message = "Opening Google Play…") }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product).setOfferToken(token).build()
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params)).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            mutableState.update { it.copy(busy = false) }
            if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh()
            else message("Google Play could not open checkout. Retry or restore purchases.")
        }
    }

    private fun verified(purchase: Purchase): Boolean =
        purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            productId in purchase.products && purchase.purchaseToken.isNotBlank() &&
            runCatching { JSONObject(purchase.originalJson).optString("packageName") == context.packageName }.getOrDefault(false) &&
            signature.verify(purchase.originalJson, purchase.signature)

    private fun readCachedReceipt(): Boolean = runCatching {
        val saved = JSONObject(receiptFile.openRead().bufferedReader().use { it.readText() })
        verified(Purchase(saved.getString("receipt"), saved.getString("signature")))
    }.getOrDefault(false)

    private fun cache(purchase: Purchase) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = receiptFile.startWrite()
            stream.write(JSONObject().put("receipt", purchase.originalJson)
                .put("signature", purchase.signature).toString().toByteArray(Charsets.UTF_8))
            receiptFile.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { receiptFile.failWrite(it) }
            message("Pro is active, but offline access could not be saved. Restore when online after restarting.")
        }
    }

    private fun message(text: String) { mutableState.update { it.copy(message = text) } }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun unavailable(text: String) {
        productGeneration++
        details = null
        offerToken = null
        mutableState.update { it.copy(
            entitlement = EntitlementPolicy.reconcile(it.entitlement, false, false),
            busy = false, price = null, message = text,
        ) }
    }
}
