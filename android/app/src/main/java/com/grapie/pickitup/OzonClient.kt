package com.grapie.pickitup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

sealed interface FetchResult {
    data class Ok(val barcode: Barcode) : FetchResult
    data object LoginNeeded : FetchResult
    data class Error(val message: String) : FetchResult
}

/**
 * Talks to Ozon through a hidden WebView: the site's antibot only lets real browsers
 * through, so the request is made by fetch() from an ozon.ru page, with the cookies
 * the user got when logging in via [LoginActivity].
 */
object OzonClient {
    private const val TAG = "OzonClient"
    const val HOME_URL = "https://www.ozon.ru/"
    // The bare Ozon ID form: one tap shorter than going through the account page.
    const val LOGIN_URL = "https://www.ozon.ru/ozonid-lite"
    private const val TIMEOUT_MS = 60_000L
    private const val ATTEMPTS = 6
    private const val ATTEMPT_TIMEOUT_MS = 10_000L
    private const val RETRY_DELAY_MS = 3_000L

    private val FETCH_JS = """
        fetch('/api/composer-api.bx/_action/v2/prefetchBarcode', {
          method: 'POST', credentials: 'include',
          headers: {'content-type': 'application/json'}, body: ''
        }).then(r => r.text().then(t => OzonBridge.result(r.status, t)))
          .catch(e => OzonBridge.result(-1, String(e)));
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Look like mobile Chrome rather than an embedded WebView.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }

    suspend fun fetch(context: Context): FetchResult = withContext(Dispatchers.Main) {
        val web = WebView(context.applicationContext)
        try {
            withTimeoutOrNull(TIMEOUT_MS) { fetchIn(web) } ?: FetchResult.Error(AppSettings.strings().timeout)
        } finally {
            web.stopLoading()
            web.destroy()
            CookieManager.getInstance().flush()
        }
    }

    private class Bridge(val onResult: (Int, String) -> Unit) {
        @JavascriptInterface
        fun result(status: Int, body: String) = onResult(status, body)
    }

    @SuppressLint("JavascriptInterface")
    private suspend fun fetchIn(web: WebView): FetchResult = suspendCancellableCoroutine { cont ->
        configure(web)
        web.settings.blockNetworkImage = true
        // web.post() would never run: a View that is not attached to a window queues it forever.
        val main = Handler(Looper.getMainLooper())
        var attempts = 0
        var sentAt = 0L          // 0 = no fetch in flight
        var pageReady = false
        var lastError = AppSettings.strings().timeout

        web.addJavascriptInterface(Bridge { status, body ->
            main.post {
                // Never the body: a 200 carries the pickup code, which is enough to collect the orders.
                Log.d(TAG, "fetch result: HTTP $status, ${body.length} chars")
                if (!cont.isActive) return@post
                sentAt = 0
                parse(status, body)?.let { cont.resume(it); return@post }
                lastError = "HTTP $status: ${body.take(80).trim()}"
            }
        }, "OzonBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "page finished: $url")
                if (url.startsWith("https://www.ozon.ru")) pageReady = true
            }
        }

        // Ozon replaces the document right after the first load (and the antibot page
        // redirects when solved), which kills an in-flight fetch without any callback.
        // So poll: (re)send the fetch whenever none is pending or the last one went silent.
        val tick = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val now = System.currentTimeMillis()
                if (pageReady && (sentAt == 0L || now - sentAt > ATTEMPT_TIMEOUT_MS)) {
                    if (attempts >= ATTEMPTS) {
                        cont.resume(FetchResult.Error(lastError))
                        return
                    }
                    attempts++
                    sentAt = now
                    Log.d(TAG, "fetch attempt $attempts")
                    web.evaluateJavascript(FETCH_JS, null)
                }
                main.postDelayed(this, RETRY_DELAY_MS)
            }
        }
        web.loadUrl(HOME_URL)
        main.postDelayed(tick, RETRY_DELAY_MS)
    }

    /** null = not a final answer (antibot page, network hiccup), worth retrying. */
    private fun parse(status: Int, body: String): FetchResult? {
        if (status == 401) return FetchResult.LoginNeeded
        // A JSON error is Ozon's own answer, not the antibot page: retrying won't help.
        // Logged-out sessions get "500 rpc error" here.
        if (status >= 500 && body.trimStart().startsWith("{")) {
            return if (isLoggedIn()) FetchResult.Error("HTTP $status") else FetchResult.LoginNeeded
        }
        if (status != 200) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = json.optJSONObject("data") ?: json
        val bc = data.optJSONObject("barcode") ?: return FetchResult.LoginNeeded
        val code = bc.optString("code")
        if (code.isEmpty()) return FetchResult.LoginNeeded
        return FetchResult.Ok(Barcode(code, bc.optString("visibleCode", code), System.currentTimeMillis()))
    }

    private fun cookie(name: String): String? =
        CookieManager.getInstance().getCookie("https://www.ozon.ru")
            ?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    /** Ozon sets `__Secure-user-id` to the account id after login (0 for guests). */
    fun isLoggedIn(): Boolean {
        val id = cookie("__Secure-user-id")
        return !id.isNullOrEmpty() && id != "0"
    }

    /** Changes on every new login (and token refresh): tells a fresh session from a dead one. */
    fun accessToken(): String? = cookie("__Secure-access-token")

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        BarcodeStore.setLoggedIn(false)
    }
}
