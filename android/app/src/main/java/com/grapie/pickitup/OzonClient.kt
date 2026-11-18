package com.grapie.pickitup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

sealed interface FetchResult {
    data class Ok(val barcode: Barcode) : FetchResult
    data object LoginNeeded : FetchResult
    data class Error(val message: String) : FetchResult
    /** Not tried: the app's traffic goes through a VPN ([Net.vpnActive]). */
    data object Vpn : FetchResult
}

/**
 * Talks to Ozon through a hidden WebView: the site's antibot only lets real browsers
 * through, so the request is made by fetch() from an ozon.ru page, with the cookies
 * the user got when logging in via [LoginActivity].
 */
object OzonClient : ProviderClient {
    private const val TAG = "OzonClient"
    private val HOME_URL = Provider.OZON.homeUrl
    private const val TIMEOUT_MS = 60_000L
    private const val ATTEMPTS = 6
    private const val ATTEMPT_TIMEOUT_MS = 10_000L
    private const val RETRY_DELAY_MS = 3_000L
    /** Longest a page lives on after it is done with, waiting for a pair that may still come. */
    const val SETTLE_MS = 8_000L
    private const val SETTLE_POLL_MS = 250L
    /** Access tokens live 2 h: a pair we saw issued less than this ago can't be replaced yet. */
    private const val FRESH_PAIR_MS = 110 * 60_000L

    // The bridge object is visible to every frame of the page (third-party iframes too): only an
    // answer carrying this page's random key counts, so no other frame can hand us a fake code.
    private fun fetchJs(key: String) = """
        fetch('/api/composer-api.bx/_action/v2/prefetchBarcode', {
          method: 'POST', credentials: 'include',
          headers: {'content-type': 'application/json'}, body: ''
        }).then(r => r.text().then(t => OzonBridge.result('$key', r.status, t)))
          .catch(e => OzonBridge.result('$key', -1, String(e)));
    """.trimIndent()

    /**
     * WebViews that may still get Set-Cookie with a rotated login pair: a running hidden fetch and
     * any page in its [retire] grace time. [KeepAliveJob] keeps the process alive until this is 0,
     * and a new fetch waits for it, so two pages never rotate the same pair at once.
     */
    val alive = MutableStateFlow(0)
    /** Hidden fetch pages alive: the Service Worker guard is on only for them. */
    @Volatile private var hiddenPages = 0
    /** WebView profiles whose Service Worker guard is installed. */
    private val swGuarded = mutableSetOf<String>()
    /** Per account: token print once its last page was done; a fetch starting from another pair means a lost rotation. */
    private val lastPrint = mutableMapOf<String, String>()

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView, account: Account) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Look like mobile Chrome rather than an embedded WebView.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        WebProfiles.cookies(account.profile).setAcceptThirdPartyCookies(web, true)
    }

    override suspend fun fetch(context: Context, account: Account): FetchResult = withContext(Dispatchers.Main) {
        // A page from before (the last fetch, the orders page just closed) may still rotate the pair.
        withTimeoutOrNull(SETTLE_MS + 5_000) { alive.first { it == 0 } }
        alive.value++
        hiddenPages++
        // Until the page is destroyed the process must not be frozen or killed: a watch request
        // has nothing else keeping it alive once the watchapp closes.
        KeepAliveJob.start(context)
        installServiceWorkerGuard(account)
        val web = WebView(context.applicationContext).also { WebProfiles.apply(it, account.profile) }
        val before = tokenPrint(account)
        lastPrint[account.id]?.let { if (it != before) BarcodeStore.log(AppSettings.strings().logTokensChanged(short(it), short(before)), account) }
        DebugLog.d("fetch", "start, account $account; alive=${alive.value} loginOpen=${LoginActivity.isOpen} vpn=${Net.vpnActive(context)}")
        DebugLog.tokens("fetch", "tokens before", account)
        val cookiesBefore = DebugLog.cookies(account)
        val started = System.currentTimeMillis()
        try {
            (withTimeoutOrNull(TIMEOUT_MS) { fetchIn(web, account) } ?: FetchResult.Error(AppSettings.strings().timeout))
                .also { DebugLog.d("fetch", "result after ${System.currentTimeMillis() - started} ms: ${describe(it)}") }
        } finally {
            // A rotated pair (document load or our own answer) must reach the disk at once: Chromium
            // writes cookies lazily, and the process may die right after the answer.
            WebProfiles.cookies(account.profile).flush()
            val answered = tokenPrint(account)
            // The journal only notes a renewal; the detailed log has the full prints anyway.
            if (answered != before) BarcodeStore.log(AppSettings.strings().logTokens(short(before), short(answered)), account)
            val afterAnswer = DebugLog.cookieDiff("fetch", "cookies at the answer", cookiesBefore, account)
            retire(web, account, before, answered, hidden = true, cookiesNow = afterAnswer)
        }
    }

    private fun describe(r: FetchResult) = when (r) {
        is FetchResult.Ok -> "Ok (code …" + r.barcode.code.takeLast(2) + ")"
        else -> r.toString()
    }

    /** What an answer body is, without the pickup code. */
    private fun describeBody(status: Int, body: String): String {
        val t = body.trimStart()
        return when {
            t.startsWith("{") -> runCatching {
                val json = JSONObject(t)
                val data = json.optJSONObject("data")
                val bc = data?.optJSONObject("barcode")
                if (bc != null) "json, barcode present (code ${bc.optString("code").length} chars), " +
                    // In hours: the raw seconds are long enough for the digit mask to eat them.
                    "cacheLifetime=${((data.opt("cacheLifetime") ?: bc.opt("cacheLifetime")) as? Number)?.let { "${it.toLong() / 3600} h" }}"
                else "json keys=" + json.keys().asSequence().toList() + " body=" + t.take(300)
            }.getOrDefault("json? " + t.take(300))
            t.startsWith("<") -> "html ${body.length} chars" +
                listOf("challenge", "abt", "captcha", "fab_").filter { body.contains(it, true) }.let { if (it.isEmpty()) "" else " $it" } +
                (Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.let { " title=\"${it.take(60)}\"" } ?: "")
            else -> t.take(200)
        }.let { "HTTP $status, $it" }
    }

    /**
     * Destroys [web] once no rotated login pair can still arrive: destroying a page with a request
     * in flight would drop that pair, and Ozon already counts the old one as replaced.
     * - the pair changed while this page lived ([startPrint] -> now): that was the rotation, close;
     * - we saw the current pair issued less than [FRESH_PAIR_MS] ago: no rotation possible, close;
     * - otherwise watch the cookies for up to [SETTLE_MS] and close as soon as the pair changes.
     * Cookies go to disk on every step; [KeepAliveJob] holds the process meanwhile.
     * [hidden] = a fetch page, already counted in [alive] since it started.
     */
    fun retire(web: WebView, account: Account, startPrint: String?, answered: String? = null, hidden: Boolean = false, cookiesNow: Map<String, String>? = null) {
        val jar = WebProfiles.cookies(account.profile)
        jar.flush()
        val context = web.context.applicationContext
        val printNow = answered ?: tokenPrint(account)
        val tag = if (hidden) "fetch" else "site"
        val cookiesAt = cookiesNow ?: DebugLog.cookies(account)
        if (!hidden) {
            alive.value++
            KeepAliveJob.start(context)
        }
        val rotated = startPrint != null && startPrint != printNow && printNow != "-"
        if (rotated) markFresh(account)
        val fresh = !rotated && isFresh(account)
        val main = Handler(Looper.getMainLooper())
        val since = System.currentTimeMillis()
        var done = false
        fun destroy(why: String) {
            if (done) return
            done = true
            web.destroy()
            jar.flush()
            DebugLog.d(tag, "page destroyed after ${System.currentTimeMillis() - since} ms ($why)")
            DebugLog.cookieDiff(tag, "cookies since retiring", cookiesAt, account)
            val late = tokenPrint(account)
            if (late != printNow) {
                BarcodeStore.log(AppSettings.strings().logTokens(short(printNow), short(late)), account)
                markFresh(account)
            }
            lastPrint[account.id] = late
            if (hidden) hiddenPages--
            alive.value--
        }
        DebugLog.d(tag, "page retiring; rotated=$rotated fresh=$fresh alive=${alive.value}")
        when {
            rotated -> destroy("pair already rotated")
            fresh -> destroy("pair is fresh")
            else -> main.post(object : Runnable {
                override fun run() {
                    if (done) return
                    when {
                        tokenPrint(account) != printNow -> destroy("pair rotated while waiting")
                        System.currentTimeMillis() - since >= SETTLE_MS -> destroy("nothing came")
                        else -> main.postDelayed(this, SETTLE_POLL_MS)
                    }
                }
            })
        }
    }

    /** Our own clock, not the token's: its issue time is in Ozon's time zone. */
    private fun markFresh(account: Account) {
        account.clientPrefs().edit().putLong("pairSeenAt", System.currentTimeMillis()).apply()
    }

    private fun isFresh(account: Account): Boolean {
        val at = account.clientPrefs().getLong("pairSeenAt", 0)
        val age = System.currentTimeMillis() - at
        return at > 0 && age in 0 until FRESH_PAIR_MS
    }

    /**
     * Ozon's site has a Service Worker. Its requests skip WebViewClient.shouldInterceptRequest and
     * would carry the session past [stubbed]; while a hidden fetch runs they are stubbed here too.
     * The visible site ([LoginActivity]) is left alone. Once per WebView profile (each has its own workers).
     */
    private fun installServiceWorkerGuard(account: Account) {
        if (!swGuarded.add(account.profile)) return
        runCatching {
            WebProfiles.serviceWorkers(account.profile).setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    if (hiddenPages == 0 || LoginActivity.isOpen) {
                        if (request.url.host?.endsWith("ozon.ru") == true) DebugLog.d("sw", "pass (site) ${request.method} ${DebugLog.url(request.url.toString())}")
                        return null
                    }
                    // An error, not "{}": the worker may cache what it gets and serve it to the visible site.
                    val stub = stubbed(request)
                    if (request.url.host?.endsWith("ozon.ru") == true)
                        DebugLog.d("sw", "${if (stub) "STUB 503" else "pass"} ${request.method} ${DebugLog.url(request.url.toString())}")
                    return if (stub) unavailable() else null
                }
            })
        }.onFailure { Log.w(TAG, "service worker guard failed", it) }
    }

    private fun unavailable() =
        WebResourceResponse("text/plain", "utf-8", 503, "Unavailable", emptyMap(), "".byteInputStream())

    /** Requests the hidden page doesn't need that could still rotate the login pair. */
    private fun stubbed(request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host?.endsWith("ozon.ru") != true) return false
        val path = url.path.orEmpty()
        if (path.endsWith("/prefetchBarcode")) return false
        if (path.startsWith("/api/")) return true
        // Document prefetches by the page script ("/", "/cart"): never the page itself.
        return !request.isForMainFrame && request.requestHeaders.any { (k, v) ->
            (k.equals("purpose", true) || k.equals("sec-purpose", true)) && v.contains("prefetch", true)
        }
    }

    /** Journal-safe fingerprint of the login tokens: short name + 4 hex of SHA-256, never a value. */
    private fun tokenPrint(account: Account): String {
        val tokens = WebProfiles.cookies(account.profile).getCookie("https://www.ozon.ru")
            ?.split(';')?.map { it.trim() }
            ?.filter { it.substringBefore('=').contains("token", ignoreCase = true) }
            .orEmpty()
        if (tokens.isEmpty()) return "-"
        return tokens.joinToString(" ") { c ->
            val name = c.substringBefore('=').removePrefix("__Secure-").removeSuffix("-token")
            val value = c.substringAfter('=')
            val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            name + ":" + hash.take(2).joinToString("") { "%02x".format(it) } + if (isGuestToken(value)) "(guest)" else ""
        }
    }

    /** Remembers the current pair as the one we left the session with (after a login, say). */
    fun noteTokens(account: Account) {
        lastPrint[account.id] = tokenPrint(account)
    }

    /** A login just issued this pair: it can't be rotated for the next two hours. */
    fun noteLogin(account: Account) {
        noteTokens(account)
        markFresh(account)
    }

    /** Journal-safe print of the current pair (see [tokenPrint]). */
    fun currentPrint(account: Account) = tokenPrint(account)

    /** "access:759f refresh:496c" -> "759f/496c" for the journal. */
    private fun short(print: String) = print.split(' ').joinToString("/") { it.substringAfter(':') }

    /**
     * Ozon's tokens are "12.<account id>.<session>...": a guest (logged-out) pair has account 0.
     * This, not an error text, is what tells "Ozon ended the session" from "this request failed".
     */
    private fun isGuestToken(value: String) = value.split('.').getOrNull(1) == "0"

    private class Bridge(val key: String, val onResult: (Int, String) -> Unit) {
        @JavascriptInterface
        fun result(key: String, status: Int, body: String) {
            if (key == this.key) onResult(status, body)
        }
    }

    @SuppressLint("JavascriptInterface")
    private suspend fun fetchIn(web: WebView, account: Account): FetchResult = suspendCancellableCoroutine { cont ->
        configure(web, account)
        web.settings.blockNetworkImage = true
        // web.post() would never run: a View that is not attached to a window queues it forever.
        val main = Handler(Looper.getMainLooper())
        var attempts = 0
        var sentAt = 0L          // 0 = no fetch in flight
        var pageReady = false
        var lastError = AppSettings.strings().timeout

        val key = UUID.randomUUID().toString()
        web.addJavascriptInterface(Bridge(key) { status, body ->
            main.post {
                // Never the body: a 200 carries the pickup code, which is enough to collect the orders.
                Log.d(TAG, "fetch result: HTTP $status, ${body.length} chars")
                DebugLog.d("fetch", "answer #$attempts: " + describeBody(status, body))
                if (!cont.isActive) return@post
                sentAt = 0
                parse(status, body, account)?.let { cont.resume(it); return@post }
                lastError = "HTTP $status: ${body.take(80).trim()}"
            }
        }, "OzonBridge")

        web.webViewClient = object : WebViewClient() {
            // The page's own API calls rotate the login tokens too (seen: summaryV2 right after the
            // document already had): one still in flight when the WebView goes loses the new pair.
            // Answer them here, so only the document and our fetch ever touch the session.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val stub = stubbed(request)
                if (request.url.host?.endsWith("ozon.ru") == true) DebugLog.d("page",
                    "${if (!stub) "pass" else if (request.url.path.orEmpty().startsWith("/api/")) "STUB {}" else "STUB 503"} " +
                        "${request.method} ${DebugLog.url(request.url.toString())}${if (request.isForMainFrame) " (document)" else ""}")
                if (!stub) return null
                if (!request.url.path.orEmpty().startsWith("/api/")) return unavailable()
                return WebResourceResponse("application/json", "utf-8", 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "https://www.ozon.ru"), "{}".byteInputStream())
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                DebugLog.d("page", "started ${DebugLog.url(url)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "page finished: $url")
                DebugLog.d("page", "finished ${DebugLog.url(url)}")
                // The document load is what rotates an expired pair: on disk now, not only at the
                // answer - people swipe the app away from Recents right after tapping refresh.
                WebProfiles.cookies(account.profile).flush()
                if (url.startsWith("https://www.ozon.ru")) pageReady = true
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                DebugLog.d("page", "HTTP ${response.statusCode} ${request.method} ${DebugLog.url(request.url.toString())}${if (request.isForMainFrame) " (document)" else ""}")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                DebugLog.d("page", "error ${error.errorCode} ${error.description} ${DebugLog.url(request.url.toString())}")
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
                    DebugLog.d("fetch", "attempt $attempts")
                    web.evaluateJavascript(fetchJs(key), null)
                }
                main.postDelayed(this, RETRY_DELAY_MS)
            }
        }
        web.loadUrl(HOME_URL)
        main.postDelayed(tick, RETRY_DELAY_MS)
    }

    /** null = not a final answer (antibot page, network hiccup), worth retrying. */
    private fun parse(status: Int, body: String, account: Account): FetchResult? {
        if (status == 401) return FetchResult.LoginNeeded
        // A JSON error is Ozon's own answer, not the antibot page: retrying won't help.
        // Ended sessions get "500 rpc error" here together with a guest pair; with the account's pair
        // still in place it is just a failed request (the session may well be alive).
        if (status >= 500 && body.trimStart().startsWith("{")) {
            return if (isLoggedIn(account)) FetchResult.Error("HTTP $status") else FetchResult.LoginNeeded
        }
        if (status != 200) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = json.optJSONObject("data") ?: json
        // No code with the account's pair still in place: a strange answer, not a logout.
        val noCode = if (isLoggedIn(account)) FetchResult.Error("HTTP 200 without a barcode") else FetchResult.LoginNeeded
        val bc = data.optJSONObject("barcode") ?: return noCode
        val code = bc.optString("code")
        if (code.isEmpty()) return noCode
        return FetchResult.Ok(Barcode(code, bc.optString("visibleCode", code), System.currentTimeMillis()))
    }

    private fun cookie(account: Account, name: String): String? =
        WebProfiles.cookies(account.profile).getCookie("https://www.ozon.ru")
            ?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    /** Ozon sets `__Secure-user-id` to the account id after login (0 for guests). */
    override fun isLoggedIn(account: Account): Boolean {
        val id = cookie(account, "__Secure-user-id")
        if (id.isNullOrEmpty() || id == "0") return false
        // The id cookie may outlive the session: a guest access token means logged out.
        return accessToken(account)?.let { !isGuestToken(it) } ?: true
    }

    /** Changes on every new login (and token refresh): tells a fresh session from a dead one. */
    fun accessToken(account: Account): String? = cookie(account, "__Secure-access-token")

    override fun logout(account: Account) {
        val jar = WebProfiles.cookies(account.profile)
        jar.removeAllCookies(null)
        jar.flush()
        // The site's local storage belongs to that session too.
        WebProfiles.storage(account.profile).deleteAllData()
        account.setLoggedIn(false)
        // A page still settling may get a rotated pair after this and log us back in: clear again.
        if (alive.value > 0) Handler(Looper.getMainLooper()).postDelayed({
            if (!account.loggedIn.value) {
                jar.removeAllCookies(null)
                jar.flush()
            }
        }, SETTLE_MS + 1_000)
    }
}
