package za.co.aftrhrs.data

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlin.coroutines.resume

data class PlayPlanOffer(
    val details: ProductDetails,
    val offerToken: String,
    val price: String,
)

data class GooglePlayBillingState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val purchasesEnabled: Boolean = false,
    val monthly: PlayPlanOffer? = null,
    val annual: PlayPlanOffer? = null,
    val hasPremium: Boolean = false,
    val provider: String = "none",
    val managementUrl: String? = null,
    val message: String? = null,
)

/** Google Play is the payment UI; the AFTRHRS API remains authoritative for Premium access. */
class GooglePlayBilling(
    context: Context,
    private val accountId: String,
    private val onEntitlementChanged: suspend () -> Unit,
) : PurchasesUpdatedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    var state by mutableStateOf(GooglePlayBillingState())
        private set

    private var connecting = false
    private var connected = false
    private var loadingAvailability = false
    private var availabilityLoaded = false
    private var productIds: List<String> = emptyList()
    private var monthlyProductId: String? = null
    private var annualProductId: String? = null

    fun load() {
        if (loadingAvailability || availabilityLoaded) return
        loadingAvailability = true
        state = state.copy(loading = true, message = null)
        scope.launch {
            try {
                val availability = ApiClient.request("billing/google/availability").jsonObject
                monthlyProductId = availability.strOrNull("monthlyProductId")?.takeIf(String::isNotBlank)
                annualProductId = availability.strOrNull("annualProductId")?.takeIf(String::isNotBlank)
                productIds = listOfNotNull(monthlyProductId, annualProductId).distinct()
                val subscription = ApiClient.request("subscription").jsonObject
                state = state.copy(
                    configured = availability.bool("configured"),
                    purchasesEnabled = availability.bool("purchasesEnabled"),
                    hasPremium = subscription.bool("hasPremium") || subscription.str("effectiveTier") == "PREMIUM",
                    provider = subscription.strOrNull("billingProvider") ?: availability.str("provider"),
                    managementUrl = subscription.strOrNull("manageUrl") ?: availability.strOrNull("managementUrl"),
                    loading = false,
                    message = null,
                )
                availabilityLoaded = true
                loadingAvailability = false
                onEntitlementChanged()
                connectAndLoad()
            } catch (error: Exception) {
                loadingAvailability = false
                state = state.copy(loading = false, message = error.message ?: "Membership options could not be loaded.")
            }
        }
    }

    fun refreshPurchases() {
        if (!availabilityLoaded) {
            load()
            return
        }
        if (!state.configured) return
        scope.launch {
            connectAndLoad()
            queryOwnedPurchases()
        }
    }

    fun purchase(activity: Activity, offer: PlayPlanOffer) {
        if (!state.configured || !state.purchasesEnabled || state.hasPremium) return
        if (!connected) {
            state = state.copy(message = "Connecting to Google Play. Please try again in a moment.")
            connectAndLoad()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(offer.details)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .setObfuscatedAccountId(obfuscatedAccountId(accountId))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            state = state.copy(message = billingMessage(result, "Google Play could not start the purchase."))
        } else {
            state = state.copy(message = null)
        }
    }

    private fun connectAndLoad() {
        if (connecting) return
        if (connected) {
            queryProductDetails()
            return
        }
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    state = state.copy(message = billingMessage(result, "Google Play Billing is unavailable right now."))
                    return
                }
                connected = true
                queryProductDetails()
                scope.launch { queryOwnedPurchases() }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
            }
        })
    }

    private fun queryProductDetails() {
        if (!connected || productIds.isEmpty()) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        client.queryProductDetailsAsync(params) { result, query ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                state = state.copy(message = billingMessage(result, "Premium plans could not be loaded from Google Play."))
                return@queryProductDetailsAsync
            }
            val offers = query.productDetailsList.associateBy { it.productId }
            state = state.copy(
                monthly = monthlyProductId?.let(offers::get)?.toOffer(),
                annual = annualProductId?.let(offers::get)?.toOffer(),
                message = if (offers.isEmpty()) "Premium subscriptions are not available on Google Play yet." else state.message,
            )
        }
    }

    private fun ProductDetails.toOffer(): PlayPlanOffer? {
        val details = subscriptionOfferDetails.orEmpty()
        val selected = details.firstOrNull { it.offerId == null } ?: details.firstOrNull()
        val price = selected?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
        return if (selected != null && !price.isNullOrBlank()) PlayPlanOffer(this, selected.offerToken, price) else null
    }

    private suspend fun queryOwnedPurchases() = suspendCancellableCoroutine { continuation ->
        if (!connected) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                scope.launch {
                    purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .forEach { processPurchase(it, refreshing = true) }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            } else {
                state = state.copy(message = billingMessage(result, "Google Play purchases could not be restored."))
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch {
                purchases.orEmpty().forEach { processPurchase(it, refreshing = false) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> state = state.copy(message = null)
            else -> state = state.copy(message = billingMessage(result, "Google Play could not complete the purchase."))
        }
    }

    private suspend fun processPurchase(purchase: Purchase, refreshing: Boolean) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                state = state.copy(message = "Your payment is pending. Premium will unlock after Google Play confirms it.")
                return
            }
            Purchase.PurchaseState.PURCHASED -> Unit
            else -> return
        }

        state = state.copy(loading = true, message = if (refreshing) "Checking your Google Play membership…" else "Confirming your purchase…")
        try {
            val response = ApiClient.request(
                if (refreshing) "billing/google/refresh" else "billing/google/verify",
                io.ktor.http.HttpMethod.Post,
                obj("purchaseToken" to purchase.purchaseToken),
            ).jsonObject
            if (response.bool("verified") && response.bool("active")) {
                val acknowledged = purchase.isAcknowledged || acknowledge(purchase)
                onEntitlementChanged()
                val availability = ApiClient.request("billing/google/availability").jsonObject
                val subscription = ApiClient.request("subscription").jsonObject
                state = state.copy(
                    loading = false,
                    hasPremium = subscription.bool("hasPremium") || subscription.str("effectiveTier") == "PREMIUM",
                    provider = subscription.strOrNull("billingProvider") ?: availability.str("provider"),
                    managementUrl = subscription.strOrNull("manageUrl") ?: availability.strOrNull("managementUrl"),
                    purchasesEnabled = availability.bool("purchasesEnabled"),
                    message = if (acknowledged) "Your Premium membership is active." else "Your membership is active, but Google Play needs a confirmation. Tap Restore purchases to retry.",
                )
            } else {
                state = state.copy(loading = false, message = "Google Play has not confirmed an active Premium membership yet.")
            }
        } catch (error: Exception) {
            state = state.copy(loading = false, message = error.message ?: "Your purchase could not be verified yet. Try Restore purchases.")
        }
    }

    private suspend fun acknowledge(purchase: Purchase): Boolean = suspendCancellableCoroutine { continuation ->
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
        }
    }

    private fun obfuscatedAccountId(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun billingMessage(result: BillingResult, fallback: String): String =
        result.debugMessage.takeIf { it.isNotBlank() } ?: fallback

    fun close() {
        connected = false
        client.endConnection()
        scope.cancel()
    }
}
