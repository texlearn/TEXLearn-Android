
package com.samrambakeralam.texlearn

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
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

class MainActivity : Activity(), PaymentResultWithDataListener {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var checkout: Checkout? = null

    companion object {
        private const val START_URL =
            "https://samrambakeralam.github.io/TEXLearn/"

        private val INTERNAL_HOSTS = setOf(
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(234, 248, 240)
        window.navigationBarColor = Color.WHITE

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this)

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

        // Native payment bridge. Checkout requests are validated below.
        webView.addJavascriptInterface(
            NativePaymentBridge(),
            "TEXLearnNative"
        )

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

        if (savedInstanceState == null) {
            val incomingUrl = intent?.dataString

            if (!incomingUrl.isNullOrBlank() &&
                isTEXLearnAccessUrl(incomingUrl)
            ) {
                webView.loadUrl(incomingUrl)
            } else {
                webView.loadUrl(START_URL)
            }
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private inner class NativePaymentBridge {

        @JavascriptInterface
        fun openCheckout(optionsJson: String) {
            runOnUiThread {
                try {
                    val currentUri = Uri.parse(webView.url ?: "")

                    // Accept checkout requests only from our checkout page.
                    if (
                        currentUri.host != "samrambakeralam.github.io" ||
                        currentUri.path != "/TEXLearn/checkout.html"
                    ) {
                        throw IllegalStateException(
                            "Native checkout is not allowed on this page."
                        )
                    }

                    val options = JSONObject(optionsJson)

                    val key = options.optString("key")
                    val orderId = options.optString("order_id")
                    val amount = options.optInt("amount", 0)
                    val currency = options.optString("currency")

                    if (
                        key.isBlank() ||
                        orderId.isBlank() ||
                        amount <= 0 ||
                        currency.isBlank()
                    ) {
                        throw IllegalArgumentException(
                            "Incomplete payment order details."
                        )
                    }

                    val nativeCheckout = Checkout()
                    nativeCheckout.setKeyID(key)
                    checkout = nativeCheckout

                    nativeCheckout.open(this@MainActivity, options)

                } catch (error: Exception) {
                    notifyWebError(
                        error.message ?: "Unable to open native checkout."
                    )
                }
            }
        }
    }

    override fun onPaymentSuccess(
        razorpayPaymentID: String?,
        paymentData: PaymentData
    ) {
        runOnUiThread {
            try {
                val data = paymentData.data

                // Forward the SDK response to the website's existing
                // verification function. The server must verify it.
                webView.evaluateJavascript(
                    "window.onNativeRazorpaySuccess && " +
                        "window.onNativeRazorpaySuccess($data);",
                    null
                )
            } catch (_: Exception) {
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
        runOnUiThread {
            notifyWebError(
                response ?: "Payment was not completed."
            )
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
        val path = uri.path

        uri.scheme == "https" &&
            uri.host == "samrambakeralam.github.io" &&
            (path == "/TEXLearn" || path == "/TEXLearn/") &&
            !uri.getQueryParameter("cid").isNullOrBlank() &&
            !uri.getQueryParameter("t").isNullOrBlank()
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
        isTEXLearnAccessUrl(incomingUrl)
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
            webView.removeJavascriptInterface("TEXLearnNative")
            webView.destroy()
        }

        checkout = null
        super.onDestroy()
    }
}
