package com.example

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.android.billingclient.api.*
import com.example.data.AppDatabase
import com.example.data.SettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BillingManager(private val context: Context, private val coroutineScope: CoroutineScope) {

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("aura_billing_prefs", Context.MODE_PRIVATE)
    private var billingClient: BillingClient? = null
    
    private val _isProUnlocked = MutableStateFlow(false)
    val isProUnlocked: StateFlow<Boolean> = _isProUnlocked

    companion object {
        const val PRO_PRODUCT_ID = "pro_unlock_tier_1"
        private const val TAG = "BillingManager"
    }

    init {
        // Run initial check for local bypass and play billing settings
        checkProStatus()
        initializeBillingClient()
    }

    fun checkProStatus() {
        val hasBypass = sharedPrefs.getBoolean("pro_bypass_active", false)
        if (hasBypass) {
            _isProUnlocked.value = true
            updateRoomProState(true)
        } else {
            // Check Room values or rely on Google Play Billing
            coroutineScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                val settings = db.settingsDao.getSettings()
                if (settings?.isProUnlocked == true) {
                    _isProUnlocked.value = true
                } else {
                    _isProUnlocked.value = false
                }
            }
        }
    }

    fun enableDeveloperBypass() {
        sharedPrefs.edit().putBoolean("pro_bypass_active", true).apply()
        _isProUnlocked.value = true
        updateRoomProState(true)
    }

    fun resetBypass() {
        sharedPrefs.edit().remove("pro_bypass_active").apply()
        _isProUnlocked.value = false
        updateRoomProState(false)
    }

    private fun updateRoomProState(unlocked: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val settings = db.settingsDao.getSettings() ?: SettingsEntity()
            db.settingsDao.saveSettings(settings.copy(isProUnlocked = unlocked))
        }
    }

    private fun initializeBillingClient() {
        val pendingParams = PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(pendingParams)
            .build()

        connectToGooglePlay()
    }

    private fun connectToGooglePlay() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Google Play Billing client connected.")
                    queryPurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected. Reconnecting...")
                // In production, we'd add backoff logic
            }
        })
    }

    private fun queryPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var proFound = false
                for (purchase in purchases) {
                    if (purchase.products.contains(PRO_PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        proFound = true
                        handleAcknowledge(purchase)
                    }
                }
                
                // If Play says yes, or we have manual bypass, keep it unlocked
                val isBypass = sharedPrefs.getBoolean("pro_bypass_active", false)
                val status = proFound || isBypass
                _isProUnlocked.value = status
                updateRoomProState(status)
            } else {
                Log.e(TAG, "Error querying purchases: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handleAcknowledge(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            
            billingClient?.acknowledgePurchase(acknowledgeParams) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Purchase successfully acknowledged.")
                }
            }
        }
    }

    fun launchBillingFlow(activity: Activity) {
        // If developer bypass is active, skip billing and show success immediately
        if (sharedPrefs.getBoolean("pro_bypass_active", false)) {
            Log.i(TAG, "Bypass active, skipping Play launch and treating as premium.")
            _isProUnlocked.value = true
            updateRoomProState(true)
            return
        }

        val client = billingClient ?: return
        if (!client.isReady) {
            connectToGooglePlay()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params, object : ProductDetailsResponseListener {
            override fun onProductDetailsResponse(billingResult: BillingResult, productDetailsResult: QueryProductDetailsResult) {
                val productDetailsList = productDetailsResult.productDetailsList
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList != null && productDetailsList.isNotEmpty()) {
                    val details = productDetailsList[0]
                    val flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(details)
                                    .build()
                            )
                        )
                        .build()
                    
                    client.launchBillingFlow(activity, flowParams)
                } else {
                    Log.e(TAG, "Error querying details or list empty: ${billingResult.debugMessage}")
                }
            }
        })
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            var isPurchased = false
            for (purchase in purchases) {
                if (purchase.products.contains(PRO_PRODUCT_ID)) {
                    isPurchased = true
                    handleAcknowledge(purchase)
                }
            }
            if (isPurchased) {
                _isProUnlocked.value = true
                updateRoomProState(true)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "User canceled billing flow.")
        } else {
            Log.e(TAG, "Purchase error update: ${billingResult.debugMessage}")
        }
    }
}
