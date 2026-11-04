package za.co.aftrhrs.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import java.security.MessageDigest

/** Google Play client for the Premium subscription. Entitlement is granted only by the AFTRHRS server. */
class PlayBillingManager(context: Context, private val onPurchases: (List<Purchase>) -> Unit, private val onError: (String) -> Unit) : PurchasesUpdatedListener {
    companion object { const val MONTHLY = "monthly"; const val ANNUAL = "annual" }

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()
    private val details = mutableMapOf<String, ProductDetails>()
    private var accountId: String? = null

    fun connect(monthlyId: String, annualId: String, accountId: String, onReady: (Map<String, ProductDetails.PricingPhase>) -> Unit) {
        this.accountId = accountId
        if (client.isReady) { loadProducts(monthlyId, annualId, onReady); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) loadProducts(monthlyId, annualId, onReady)
                else onError("Google Play Billing is unavailable: ${result.debugMessage}")
            }
            override fun onBillingServiceDisconnected() { onError("Connection to Google Play was interrupted.") }
        })
    }

    private fun loadProducts(monthlyId: String, annualId: String, onReady: (Map<String, ProductDetails.PricingPhase>) -> Unit) {
        val ids = mapOf(MONTHLY to monthlyId, ANNUAL to annualId)
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.values.distinct().map { id ->
            QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        client.queryProductDetailsAsync(params) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { onError("Unable to load membership plans: ${result.debugMessage}"); return@queryProductDetailsAsync }
            val found = response.productDetailsList.associateBy { it.productId }
            val prices = ids.mapNotNull { (plan, id) ->
                val product = found[id] ?: return@mapNotNull null
                val offer = product.subscriptionOfferDetails.orEmpty().firstOrNull() ?: return@mapNotNull null
                details[plan] = product
                plan to offer.pricingPhases.pricingPhaseList.last()
            }.toMap()
            onReady(prices)
        }
    }

    fun buy(activity: Activity, plan: String) {
        val product = details[plan] ?: run { onError("Membership plans are still loading."); return }
        val offer = product.subscriptionOfferDetails.orEmpty().firstOrNull() ?: run { onError("This membership plan is not available in Google Play yet."); return }
        val account = accountId ?: run { onError("Unable to bind this purchase to your AFTRHRS account."); return }
        val obfuscatedId = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offer.offerToken).build()
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams)).setObfuscatedAccountId(obfuscatedId).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) onError("Unable to start Google Play checkout: ${result.debugMessage}")
    }

    fun restore() {
        if (!client.isReady) return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) onPurchases(purchases)
            else onError("Unable to restore Google Play purchases: ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.let(onPurchases)
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> onError("Google Play checkout failed: ${result.debugMessage}")
        }
    }

    fun acknowledge(purchase: Purchase, done: (Boolean) -> Unit) {
        if (purchase.isAcknowledged) { done(true); return }
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) {
            done(it.responseCode == BillingClient.BillingResponseCode.OK)
        }
    }

    fun close() { if (client.isReady) client.endConnection() }
}
