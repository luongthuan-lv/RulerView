package com.lvt.rulerview.iap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@DelicateCoroutinesApi
class BillingService(
    private val context: Context,
    private val nonConsumableKeys: List<String>,
    private val consumableKeys: List<String>,
    private val subscriptionSkuKeys: List<String>
) : IBillingService(), PurchasesUpdatedListener {

    private lateinit var mBillingClient: BillingClient
    private var decodedKey: String? = null

    private var enableDebug: Boolean = false

    private val productDetails = mutableMapOf<String, ProductDetails?>()

    override fun init(key: String?) {
        decodedKey = key
        mBillingClient =
            BillingClient.newBuilder(context).setListener(this).enablePendingPurchases().build()
        mBillingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                log("onBillingServiceDisconnected")
            }

            override fun onBillingSetupFinished(billingResult: BillingResult) {
                log("onBillingSetupFinishedOkay: billingResult: $billingResult")

                when {
                    billingResult.isOk() -> {

                        isBillingClientConnected(true, billingResult.responseCode)
                        GlobalScope.launch {
                            queryPurchasesDone()
                        }

                        nonConsumableKeys.queryProductDetails(BillingClient.ProductType.INAPP) {
                            GlobalScope.launch {
                                //queryPurchases()
                                queryPurchasesInApp()
                            }
                        }

//                        consumableKeys.queryProductDetails(BillingClient.ProductType.INAPP) {
//                            GlobalScope.launch {
//                                queryPurchases()
//                            }
//                        }

                        subscriptionSkuKeys.queryProductDetails(BillingClient.ProductType.SUBS) {
                            GlobalScope.launch {
                                //queryPurchases()
                                queryPurchasesSubs()
                            }
                        }


                    }

                    else -> {
                        isBillingClientConnected(false, billingResult.responseCode)
                    }

                }

            }

        })
    }

    /**
     * Query Google Play Billing for existing purchases.
     * New purchases will be provided to the PurchasesUpdatedListener.
     */
    private suspend fun queryPurchases() {
        val inAppResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        processPurchases(inAppResult.purchasesList, isRestore = true)
        val subsResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        processPurchases(subsResult.purchasesList, isRestore = true)
    }

    private suspend fun queryPurchasesDone() {
        log("run queryPurchasesDone")
        val inAppResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val subsResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )

        queryPurchases(inAppResult, subsResult)
    }

    private suspend fun queryPurchasesInApp() {
        val inAppResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        processPurchases(inAppResult.purchasesList, isRestore = true)
    }

    private suspend fun queryPurchasesSubs() {
        val subsResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        processPurchases(subsResult.purchasesList, isRestore = true)
    }

    override fun buy(activity: Activity, sku: String) {
        if (!sku.isProductReady()) {
            log("buy. Google billing service is not ready yet. (SKU is not ready yet -1)")
            return
        }

        launchBillingFlow(activity, sku, BillingClient.ProductType.INAPP)
    }

    override fun subscribe(activity: Activity, sku: String) {
        if (!sku.isProductReady()) {
            log("buy. Google billing service is not ready yet. (SKU is not ready yet -2)")
            return
        }

        launchBillingFlow(activity, sku, BillingClient.ProductType.SUBS)
    }

    private fun launchBillingFlow(activity: Activity, sku: String, type: String) {
        sku.toProductDetails(type) { productDetails ->
            if (productDetails != null) {

                val productDetailsParamsList =
                    mutableListOf<BillingFlowParams.ProductDetailsParams>()
                val builder = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)

                if (type == BillingClient.ProductType.SUBS) {
                    builder.setOfferToken(productDetails.subscriptionOfferDetails!![0].offerToken)
                }
                productDetailsParamsList.add(builder.build())
                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList).build()

                mBillingClient.launchBillingFlow(activity, billingFlowParams)
            }
        }
    }

    override fun unsubscribe(activity: Activity, sku: String) {
        try {
            val intent = Intent()
            intent.action = Intent.ACTION_VIEW
            val subscriptionUrl =
                ("http://play.google.com/store/account/subscriptions" + "?package=" + activity.packageName + "&sku=" + sku)
            intent.data = Uri.parse(subscriptionUrl)
            activity.startActivity(intent)
            activity.finish()
        } catch (e: Exception) {
            log("Unsubscribing failed.")
        }
    }

    override fun unPurchase(purchaseToken: String, listener: ConsumeResponseListener) {
        val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        mBillingClient.consumeAsync(consumeParams, listener)
    }

    override fun enableDebugLogging(enable: Boolean) {
        this.enableDebug = enable
    }

    /**
     * Called by the Billing Library when new purchases are detected.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        log("onPurchasesUpdated: responseCode:$responseCode debugMessage: $debugMessage")
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                log("onPurchasesUpdated. purchase: $purchases")
                processPurchases(purchases)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> log("onPurchasesUpdated: User canceled the purchase")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                log("onPurchasesUpdated: The user already owns this item")
                //item already owned? call queryPurchases to verify and process all such items
                GlobalScope.launch {
                    queryPurchases()
                }
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> log(
                "onPurchasesUpdated: Developer error means that Google Play " +
                        "does not recognize the configuration. If you are just getting started, " +
                        "make sure you have configured the application correctly in the " +
                        "Google Play Console. The SKU product ID must match and the APK you " +
                        "are using must be signed with release keys."
            )
        }
    }

    private fun processPurchases(purchasesList: List<Purchase>?, isRestore: Boolean = false) {
        if (!purchasesList.isNullOrEmpty()) {
            log("processPurchases: " + purchasesList.size + " purchase(s)")
            purchases@ for (purchase in purchasesList) {
                // The purchase is considered successful in both PURCHASED and PENDING states.
                val purchaseSuccess =
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED || purchase.purchaseState == Purchase.PurchaseState.PENDING

                if (purchaseSuccess && purchase.products[0].isProductReady()) {
                    if (!isSignatureValid(purchase)) {
                        log("processPurchases. Signature is not valid for: $purchase")
                        continue@purchases
                    }

                    // If the state is PURCHASED, acknowledge the purchase if it hasn't been acknowledged yet.
                    if (!purchase.isAcknowledged && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken).build()
                        mBillingClient.acknowledgePurchase(acknowledgePurchaseParams) { acknowledgeBillingResult ->
                            if (acknowledgeBillingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                purchaseDone(purchase, isRestore)
                            } else {
                                log("acknowledge purchase failed. response code: ${acknowledgeBillingResult.responseCode}")
                            }
                        }
                    } else {
                        purchaseDone(purchase, isRestore)
                    }
                } else {
                    log(
                        "processPurchases failed. purchase: $purchase " + "purchaseState: " +
                                "${purchase.purchaseState} isSkuReady: ${purchase.products[0].isProductReady()}")
                }
            }
        } else {
            log("processPurchases: with no purchases")
        }
    }

    private fun purchaseDone(purchase: Purchase, isRestore: Boolean){
        // Grant entitlement to the user.
        val productDetails = productDetails[purchase.products[0]]
        when (productDetails?.productType) {
            BillingClient.ProductType.INAPP -> { // Consume the purchase
                when {
                    consumableKeys.contains(purchase.products[0]) -> {
                        mBillingClient.consumeAsync(ConsumeParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()) { result, _ ->
                            when (result.responseCode) {
                                BillingClient.BillingResponseCode.OK -> {
                                    productOwned(getPurchaseInfo(purchase), false)
                                }
                                else -> {
                                    log("Handling consumables : Error during consumption attempt -> ${result.debugMessage}")
                                }
                            }
                        }
                    }
                    else -> {
                        productOwned(getPurchaseInfo(purchase), isRestore)
                    }
                }
            }
            BillingClient.ProductType.SUBS -> {
                subscriptionOwned(getPurchaseInfo(purchase), isRestore)
            }
        }
    }

    private fun queryPurchases(
        resultInApp: PurchasesResult, resultSub: PurchasesResult
    ) {
        val listPurchaseInfo = ArrayList<DataWrappers.PurchaseInfo>(emptyList())

        val purchasesList = resultInApp.purchasesList.plus(resultSub.purchasesList)

        if (purchasesList.isNotEmpty()) {
            log("run processPurchases: " + purchasesList.size + " purchase(s)")
            purchases@ for (purchase in purchasesList) {
                if (resultInApp.billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    || resultInApp.billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
                ) {
                    listPurchaseInfo.add(getPurchaseInfo(purchase))
                } else {
                    log(
                        "run processPurchases INAPP failed. purchase: $purchase " +
                                "purchaseState: ${purchase.purchaseState} isSkuReady: ${purchase.products[0].isProductReady()}"
                    )
                }

                if (resultSub.billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    || resultSub.billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
                ) {
                    listPurchaseInfo.add(getPurchaseInfo(purchase))

                } else {
                    log(
                        "run processPurchases SUBS failed. purchase: $purchase " +
                                "purchaseState: ${purchase.purchaseState} isSkuReady: ${purchase.products[0].isProductReady()}"
                    )
                }
            }
        } else {
            log("processPurchases: with no purchases")
        }
        productDoneOwned(listPurchaseInfo)
    }

    private fun getPurchaseInfo(purchase: Purchase): DataWrappers.PurchaseInfo {
        return DataWrappers.PurchaseInfo(
            purchase.purchaseState,
            purchase.developerPayload,
            purchase.isAcknowledged,
            purchase.isAutoRenewing,
            purchase.orderId,
            purchase.originalJson,
            purchase.packageName,
            purchase.purchaseTime,
            purchase.purchaseToken,
            purchase.signature,
            purchase.products[0],
            purchase.accountIdentifiers
        )
    }

    private fun isSignatureValid(purchase: Purchase): Boolean {
        val key = decodedKey ?: return true
        return Security.verifyPurchase(key, purchase.originalJson, purchase.signature)
    }

    /**
     * Update Sku details after initialization.
     * This method has cache functionality.
     */
    private fun List<String>.queryProductDetails(type: String, done: () -> Unit) {
        if (::mBillingClient.isInitialized.not() || !mBillingClient.isReady) {
            log("queryProductDetails. Google billing service is not ready yet.")
            done()
            return
        }

        val productList = mutableListOf<QueryProductDetailsParams.Product>()
        this.forEach {
            productList.add(
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type)
                    .build()
            )
        }

        val params = QueryProductDetailsParams.newBuilder().setProductList(productList)

        mBillingClient.queryProductDetailsAsync(params.build()) { billingResult, productDetailsList ->
            if (billingResult.isOk()) {
                isBillingClientConnected(true, billingResult.responseCode)
                productDetailsList.forEach {
                    productDetails[it.productId] = it
                }

                productDetails.mapNotNull { entry ->
                    entry.value?.let {
                        when (it.productType) {
                            BillingClient.ProductType.SUBS -> {
                                entry.key to DataWrappers.ProductDetails(
                                    title = it.title,
                                    description = it.description,
                                    priceCurrencyCode = it.subscriptionOfferDetails?.get(0)?.pricingPhases?.pricingPhaseList?.get(
                                        0
                                    )?.priceCurrencyCode,
                                    price = it.subscriptionOfferDetails?.get(0)?.pricingPhases?.pricingPhaseList?.get(
                                        0
                                    )?.formattedPrice,
                                    priceAmount = it.subscriptionOfferDetails?.get(0)?.pricingPhases?.pricingPhaseList?.get(
                                        0
                                    )?.priceAmountMicros?.div(1000000.0)
                                )
                            }
                            else -> {
                                entry.key to DataWrappers.ProductDetails(
                                    title = it.title,
                                    description = it.description,
                                    priceCurrencyCode = it.oneTimePurchaseOfferDetails?.priceCurrencyCode,
                                    price = it.oneTimePurchaseOfferDetails?.formattedPrice,
                                    priceAmount = it.oneTimePurchaseOfferDetails?.priceAmountMicros?.div(
                                        1000000.0
                                    )
                                )
                            }
                        }
                    }
                }.let {
                    updatePrices(it.toMap())
                }
            }
            done()
        }
    }

    /**
     * Get Sku details by sku and type.
     * This method has cache functionality.
     */
    private fun String.toProductDetails(
        type: String, done: (productDetails: ProductDetails?) -> Unit = {}
    ) {
        if (::mBillingClient.isInitialized.not() || !mBillingClient.isReady) {
            log("buy. Google billing service is not ready yet.(mBillingClient is not ready yet - 001)")
            done(null)
            return
        }

        val productDetailsCached = productDetails[this]
        if (productDetailsCached != null) {
            done(productDetailsCached)
            return
        }

        val productList = mutableListOf<QueryProductDetailsParams.Product>()
        this.forEach {
            productList.add(
                QueryProductDetailsParams.Product.newBuilder().setProductId(it.toString())
                    .setProductType(type).build()
            )
        }

        val params = QueryProductDetailsParams.newBuilder().setProductList(productList)

        mBillingClient.queryProductDetailsAsync(params.build()) { billingResult, productDetailsList ->
            when {
                billingResult.isOk() -> {
                    isBillingClientConnected(true, billingResult.responseCode)
                    val productDetails: ProductDetails? =
                        productDetailsList.find { it.productId == this }
                    // productDetails[this] = productDetails
                    done(productDetails)
                }
                else -> {
                    log("launchBillingFlow. Failed to get details for sku: $this")
                    done(null)
                }
            }
        }
    }

    private fun String.isProductReady(): Boolean {
        return productDetails.containsKey(this) && productDetails[this] != null
    }

    override fun close() {
        mBillingClient.endConnection()
        super.close()
    }

    private fun BillingResult.isOk(): Boolean {
        return this.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun log(message: String) {
        when {
            enableDebug -> {
                Log.d(TAG, message)
            }
        }
    }

    companion object {
        const val TAG = "GoogleBillingService"
    }
}