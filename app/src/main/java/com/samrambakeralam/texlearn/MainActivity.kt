
package com.samrambakeralam.texlearn

import android.webkit.ConsoleMessage
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.util.Log
import android.widget.Toast
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature


class MainActivity : Activity(), PaymentResultWithDataListener {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var checkout: Checkout? = null

    companion object {
        private const val START_URL =
            "https://texlearn.in/"
        
        private const val PAYMENT_BRIDGE_NAME = "TEXLearnBridge"

        private val PAYMENT_BRIDGE_ORIGINS = setOf(
        "https://texlearn.in",
        "https://samrambakeralam.github.io"
    )

        private val INTERNAL_HOSTS = setOf(
            "texlearn.in",
            "samrambakeralam.github.io",
            "script.google.com",
            "script.googleusercontent.com",
            "accounts.google.com",
            "checkout.razorpay.com",
            "api.razorpay.com",
            "razorpay.com",
            "razorpay.in",
            "rzp.io"
        )
    }

        // -------------------------------------------------
    // ENCRYPTED NATIVE PAYMENT STORAGE
    // -------------------------------------------------

    private val PAYMENT_KEY_ALIAS =
        "TEXLearnPendingPaymentKey"

    private val PAYMENT_PREFS =
        "texlearn_secure_payment"

    private val PAYMENT_PREFS_KEY =
        "encrypted_pending_payment"

    private fun getPaymentSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existingKey =
            keyStore.getKey(PAYMENT_KEY_ALIAS, null) as? SecretKey

        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val keySpec = KeyGenParameterSpec.Builder(
            PAYMENT_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)

        return keyGenerator.generateKey()
    }

    @Synchronized
    private fun saveEncryptedPendingPayment(
        paymentJson: String
    ): Boolean {
        return try {
            val payment = JSONObject(paymentJson)

            val customerID = payment.optString("customerID")
            val token = payment.optString("token")
            val orderID = payment.optString("orderID")
            val amount = payment.optInt("amount", 0)

            if (
                customerID.isBlank() ||
                token.isBlank() ||
                orderID.isBlank() ||
                amount <= 0
            ) {
                return false
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.ENCRYPT_MODE,
                getPaymentSecretKey()
            )

            val encryptedData = cipher.doFinal(
                paymentJson.toByteArray(Charsets.UTF_8)
            )

            val iv = cipher.iv
            val combinedData = iv + encryptedData

            val encodedData = Base64.encodeToString(
                combinedData,
                Base64.NO_WRAP
            )

            getSharedPreferences(
                PAYMENT_PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putString(PAYMENT_PREFS_KEY, encodedData)
                .commit()

        } catch (error: Exception) {
            Log.e(
                "TEXLearnPayment",
                "Unable to save encrypted payment state",
                error
            )
            false
        }
    }

    @Synchronized
    private fun getEncryptedPendingPayment(): String {
        val encodedData = getSharedPreferences(
            PAYMENT_PREFS,
            Context.MODE_PRIVATE
        ).getString(PAYMENT_PREFS_KEY, null)
            ?: return ""

        return try {
            val combinedData = Base64.decode(
                encodedData,
                Base64.NO_WRAP
            )

            if (combinedData.size <= 12) {
                throw IllegalStateException(
                    "Stored pending payment data is invalid."
                )
            }

            val iv = combinedData.copyOfRange(0, 12)
            val encryptedData = combinedData.copyOfRange(
                12,
                combinedData.size
            )

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                getPaymentSecretKey(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(encryptedData),
                Charsets.UTF_8
            )

        } catch (error: Exception) {
            Log.e(
                "TEXLearnPayment",
                "Unable to retrieve encrypted payment state",
                error
            )
            throw IllegalStateException(
                "Unable to retrieve pending payment.",
                error
            )
        }
    }

   
@Synchronized
private fun clearEncryptedPendingPayment(): Boolean {
    return try {
        getSharedPreferences(
            PAYMENT_PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(PAYMENT_PREFS_KEY)
            .commit()

    } catch (error: Exception) {
        Log.e(
            "TEXLearnPayment",
            "Unable to clear encrypted pending payment",
            error
        )
        false
    }
}


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this)
        WebView.setWebContentsDebuggingEnabled(true)

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            setBackgroundColor(Color.TRANSPARENT)

            progressDrawable.setColorFilter(
                Color.rgb(22, 134, 106),
                android.graphics.PorterDuff.Mode.SRC_IN
            )

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            )
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(progressBar)
        setContentView(root)

        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.textZoom = 100
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode =
            WebSettings.MIXED_CONTENT_NEVER_ALLOW

        settings.userAgentString =
            settings.userAgentString + " TEXLearnAndroid/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return routeUrl(request.url.toString())
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                return routeUrl(url)
            }

           override fun onPageFinished(
    view: WebView,
    url: String
) {
    super.onPageFinished(view, url)

    view.evaluateJavascript(
        "document.documentElement.classList.add('texlearn-android')"
    ) { _ ->

        view.evaluateJavascript(
            "document.documentElement.classList.contains('texlearn-android')"
        ) { result ->
            Log.d(
                "TEXLearnApp",
                "Android styling enabled: $result"
            )
        }
    }

    progressBar.visibility = View.GONE
}
        }

     
webView.webChromeClient = object : WebChromeClient() {

    override fun onProgressChanged(
        view: WebView,
        newProgress: Int
    ) {
        progressBar.progress = newProgress

        progressBar.visibility =
            if (newProgress in 1..99) View.VISIBLE
            else View.GONE
    }

    override fun onConsoleMessage(
        consoleMessage: ConsoleMessage
    ): Boolean {
        Log.d(
            "TEXLearnPayment",
            "JS ${consoleMessage.messageLevel()} | " +
                "${consoleMessage.message()} | " +
                "${consoleMessage.sourceId()}:" +
                consoleMessage.lineNumber()
        )

        return true
    }
}


        webView.setDownloadListener(
            DownloadListener {
                    url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val request = DownloadManager.Request(
                        Uri.parse(url)
                    )
                        .setMimeType(mimeType)
                        .addRequestHeader("User-Agent", userAgent)
                        .setTitle(
                            URLUtil.guessFileName(
                                url,
                                contentDisposition,
                                mimeType
                            )
                        )
                        .setDescription("Downloading from TEXLearn")
                        .setNotificationVisibility(
                            DownloadManager.Request
                                .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(
                                url,
                                contentDisposition,
                                mimeType
                            )
                        )

                    val manager = getSystemService(
                        Context.DOWNLOAD_SERVICE
                    ) as DownloadManager

                    manager.enqueue(request)

                    Toast.makeText(
                        this,
                        "Download started",
                        Toast.LENGTH_SHORT
                    ).show()

                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Unable to start download",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

               Checkout.preload(applicationContext)
               registerPaymentMessageBridge()

        if (savedInstanceState == null) {
            val incomingUrl = intent?.dataString

           if (!incomingUrl.isNullOrBlank() &&
    (
        isTEXLearnAccessUrl(incomingUrl) ||
        isTEXLearnReferralUrl(incomingUrl) ||
        isTEXLearnAffiliateUrl(incomingUrl)
    )
) {
            webView.loadUrl(incomingUrl)
        } else {
            webView.loadUrl(START_URL)
        }
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun registerPaymentMessageBridge() {

    if (
        !WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_LISTENER
        )
    ) {
        Log.e(
            "TEXLearnPayment",
            "Secure WebMessageListener is not supported."
        )
        return
    }

    WebViewCompat.addWebMessageListener(
        webView,
        PAYMENT_BRIDGE_NAME,
        PAYMENT_BRIDGE_ORIGINS
    ) { _, message, sourceOrigin, isMainFrame, replyProxy ->

        if (!isMainFrame) {
            Log.w(
                "TEXLearnPayment",
                "Rejected payment bridge message from iframe."
            )
            return@addWebMessageListener
        }

        Log.d(
            "TEXLearnPayment",
            "Received secure bridge message from ${sourceOrigin.host}"
        )

        handlePaymentBridgeMessage(
            message,
            sourceOrigin,
            isMainFrame,
            replyProxy
        )
    }
}

    
private fun handlePaymentBridgeMessage(
    message: WebMessageCompat,
    sourceOrigin: Uri,
    isMainFrame: Boolean,
    replyProxy: JavaScriptReplyProxy
) {
    runOnUiThread {
    var requestId = ""

        try {
            if (!isMainFrame) {
                throw SecurityException("Main frame required.")
            }

          val currentPageUri = Uri.parse(webView.url ?: "")

val sourcePort = if (sourceOrigin.port == -1) {
    443
} else {
    sourceOrigin.port
}

val currentPagePort = if (currentPageUri.port == -1) {
    443
} else {
    currentPageUri.port
}

if (
    sourceOrigin.scheme != "https" ||
    sourceOrigin.host !in setOf(
        "texlearn.in",
        "samrambakeralam.github.io"
    ) ||
    currentPageUri.scheme != sourceOrigin.scheme ||
    currentPageUri.host != sourceOrigin.host ||
    currentPagePort != sourcePort
) {
    throw SecurityException("Untrusted or mismatched origin.")
}

            // Restrict every payment-storage operation to checkout.html.
            if (!isTrustedCheckoutPage()) {
                throw SecurityException(
                    "Payment bridge is only available on the checkout page."
                )
            }

            val request = JSONObject(message.data)
            requestId = request.optString("requestId", "")

            if (
                requestId.isBlank() ||
                requestId.length > 128
            ) {
                throw IllegalArgumentException("Invalid request ID.")
            }

            val action = request.optString("action")
            val payload = request.optJSONObject("payload")
                ?: JSONObject()

            when (action) {

                "openCheckout" -> {
                    val key = payload.optString("key")
                    val orderId = payload.optString("order_id")
                    val amount = payload.optInt("amount", 0)
                    val currency = payload.optString("currency")

                    if (
                        key.isBlank() ||
                        orderId.isBlank() ||
                        amount <= 0 ||
                        currency != "INR"
                    ) {
                        throw IllegalArgumentException(
                            "Invalid payment order details."
                        )
                    }

                    val nativeCheckout = Checkout()
                    nativeCheckout.setKeyID(key)
                    checkout = nativeCheckout

                    nativeCheckout.open(
                        this@MainActivity,
                        payload
                    )

                    sendPaymentBridgeResponse(
                        replyProxy,
                        requestId,
                        true
                    )
                }

                "savePendingPayment" -> {
                    val saved = saveEncryptedPendingPayment(
                        payload.toString()
                    )

                    if (!saved) {
                        throw IllegalStateException(
                            "Unable to save pending payment."
                        )
                    }

                    sendPaymentBridgeResponse(
                        replyProxy,
                        requestId,
                        true
                    )
                }

                "getPendingPayment" -> {
                    val raw = getEncryptedPendingPayment()

                    val result = if (raw.isBlank()) {
                        null
                    } else {
                        JSONObject(raw)
                    }

                    sendPaymentBridgeResponse(
                        replyProxy,
                        requestId,
                        true,
                        result
                    )
                }

             
                "clearPendingPayment" -> {
                    val cleared = clearEncryptedPendingPayment()

                    if (!cleared) {
                        throw IllegalStateException(
                            "Unable to clear pending payment."
                        )
                    }

                    sendPaymentBridgeResponse(
                        replyProxy,
                        requestId,
                        true
                    )
                }

                else -> {
                    throw IllegalArgumentException(
                        "Unsupported payment bridge action."
                    )
                }
            }

        } catch (error: Exception) {
            Log.e(
                "TEXLearnPayment",
                "Payment bridge request failed",
                error
            )

            if (requestId.isNotBlank()) {
                sendPaymentBridgeResponse(
                    replyProxy,
                    requestId,
                    false,
                    error = "Payment bridge request failed."
                )
            }
        }
    }
}

private fun isTrustedCheckoutPage(): Boolean {
    val uri = Uri.parse(webView.url ?: "")

    return uri.scheme == "https" && (
        (
            uri.host == "texlearn.in" &&
            uri.path == "/checkout.html"
        ) ||
        (
            uri.host == "samrambakeralam.github.io" &&
            uri.path == "/TEXLearn/checkout.html"
        )
    )
}

private fun sendPaymentBridgeResponse(
    replyProxy: JavaScriptReplyProxy,
    requestId: String,
    success: Boolean,
    result: JSONObject? = null,
    error: String? = null
) {
    val response = JSONObject()
        .put("requestId", requestId)
        .put("success", success)

    if (result != null) {
        response.put("result", result)
    }

    if (!error.isNullOrBlank()) {
        response.put("error", error)
    }

    replyProxy.postMessage(response.toString())
}

override fun onPaymentSuccess(
    razorpayPaymentID: String?,
    paymentData: PaymentData
) {
    Log.i(
        "TEXLearnPayment",
        "Native onPaymentSuccess ENTERED"
    )

    runOnUiThread {
        try {
            val data = paymentData.data

            Log.i(
    "TEXLearnPayment",
    "Native payment success callback | " +
        "time=${System.currentTimeMillis()} | " +
        "hasOrderID=${data.has("razorpay_order_id")} | " +
        "hasPaymentID=${data.has("razorpay_payment_id")} | " +
        "hasSignature=${data.has("razorpay_signature")}"
)

            val script =
                """
                (() => {
                    if (
                        typeof window.onNativeRazorpaySuccess
                        !== 'function'
                    ) {
                        return 'HANDLER_MISSING';
                    }

                    try {
                        window.onNativeRazorpaySuccess($data);
                        return 'HANDLER_INVOKED';
                    } catch (e) {
                        return 'JS_THROW: ' +
                            String(e && (e.stack || e));
                    }
                })()
                """.trimIndent()

            webView.evaluateJavascript(script) { result ->
                Log.i(
                    "TEXLearnPayment",
                    "Success bridge result: $result"
                )
            }

        } catch (error: Exception) {
            Log.e(
                "TEXLearnPayment",
                "Success callback forwarding failed",
                error
            )

            notifyWebError(
                "Payment returned, but its response could not be read."
            )
        }
    }
}


   

override fun onPaymentError(
    code: Int,
    response: String?,
    paymentData: PaymentData
) {
    Log.e(
        "TEXLearnPayment",
        "Native onPaymentError ENTERED | code=$code"
    )

 val data = paymentData.data

Log.e(
    "TEXLearnPayment",
    "Native payment error callback | " +
        "time=${System.currentTimeMillis()} | " +
        "code=$code | " +
        "hasOrderID=${data.has("razorpay_order_id")} | " +
        "hasPaymentID=${data.has("razorpay_payment_id")}"
)

    runOnUiThread {
        val safeMessage = JSONObject.quote(
            response ?: "Payment was not completed."
        )

        val script =
            """
            (() => {
                if (
                    typeof window.onNativeRazorpayError
                    !== 'function'
                ) {
                    return 'HANDLER_MISSING';
                }

                try {
                    window.onNativeRazorpayError($safeMessage);
                    return 'HANDLER_INVOKED';
                } catch (e) {
                    return 'JS_THROW: ' +
                        String(e && (e.stack || e));
                }
            })()
            """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.i(
                "TEXLearnPayment",
                "Error bridge result: $result"
            )
        }
    }
}



    private fun notifyWebError(message: String) {
        if (!::webView.isInitialized) return

        val safeMessage = JSONObject.quote(message)

        runOnUiThread {
            webView.evaluateJavascript(
                "window.onNativeRazorpayError && " +
                    "window.onNativeRazorpayError($safeMessage);",
                null
            )
        }
    }


private fun isTEXLearnAccessUrl(rawUrl: String): Boolean {
    return try {
        val uri = Uri.parse(rawUrl)

        val validUrl =
            uri.scheme == "https" &&
            (
                (
                    uri.host == "texlearn.in" &&
                    (uri.path.isNullOrEmpty() || uri.path == "/")
                ) ||
                (
                    uri.host == "samrambakeralam.github.io" &&
                    (uri.path == "/TEXLearn" ||
                     uri.path == "/TEXLearn/")
                )
            )

        validUrl &&
            !uri.getQueryParameter("cid").isNullOrBlank() &&
            !uri.getQueryParameter("t").isNullOrBlank()

    } catch (_: Exception) {
        false
    }
}



private fun isTEXLearnReferralUrl(rawUrl: String): Boolean {
    return try {
        val uri = Uri.parse(rawUrl)
        val partnerID = uri.getQueryParameter("ref") ?: ""

        val validHost = uri.host in setOf(
            "texlearn.in",
            "samrambakeralam.github.io"
        )

        val validPath =
            (uri.host == "texlearn.in" &&
                (uri.path.isNullOrEmpty() || uri.path == "/")) ||
            (uri.host == "samrambakeralam.github.io" &&
                (uri.path == "/TEXLearn" ||
                    uri.path == "/TEXLearn/"))

        uri.scheme == "https" &&
            validHost &&
            validPath &&
            Regex("^TXL\\d+$").matches(partnerID)

    } catch (_: Exception) {
        false
    }
}

private fun isTEXLearnAffiliateUrl(rawUrl: String): Boolean {
    return try {
        val uri = Uri.parse(rawUrl)

        uri.scheme == "https" &&
            (
                (
                    uri.host == "texlearn.in" &&
                    uri.path == "/affiliate.html"
                ) ||
                (
                    uri.host == "samrambakeralam.github.io" &&
                    uri.path == "/TEXLearn/affiliate.html"
                )
            )

    } catch (_: Exception) {
        false
    }
}

    private fun routeUrl(rawUrl: String): Boolean {
        val uri = try {
            Uri.parse(rawUrl)
        } catch (_: Exception) {
            return true
        }

        val scheme = uri.scheme?.lowercase() ?: return true

        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase() ?: return false

            if (
                INTERNAL_HOSTS.any {
                    host == it || host.endsWith(".$it")
                }
            ) {
                return false
            }

            openExternal(uri)
            return true
        }

        if (
            scheme == "about" ||
            scheme == "blob" ||
            scheme == "data" ||
            scheme == "javascript"
        ) {
            return false
        }

        return try {
            val intent = if (scheme == "intent") {
                Intent.parseUri(
                    rawUrl,
                    Intent.URI_INTENT_SCHEME
                )
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }

            startActivity(intent)
            true

        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No app is available to open this link",
                Toast.LENGTH_SHORT
            ).show()
            true

        } catch (_: Exception) {
            true
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Unable to open this link",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)

    val incomingUrl = intent.dataString

    if (!incomingUrl.isNullOrBlank() &&
        (
            isTEXLearnAccessUrl(incomingUrl) ||
            isTEXLearnReferralUrl(incomingUrl) ||
            isTEXLearnAffiliateUrl(incomingUrl)
        )
    ) {
        webView.loadUrl(incomingUrl)
    }
}

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        checkout = null
        super.onDestroy()
    }
}
