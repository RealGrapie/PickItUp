package com.grapie.pickitup

import android.content.Context
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Wildberries. Only the login needs the site (WB ID: phone + SMS, behind an antibot): it leaves
 * the access token in the site's localStorage, [LoginActivity] reads it from there ([readToken])
 * and keeps it in the account's preferences. The code itself is a plain HTTPS GET with that token:
 * no WebView, no Chromium in the process. The answer is a week of day codes.
 */
object WbClient : ProviderClient {
    private const val CODES_URL = "https://delivery-code.wildberries.ru/delivery-code/api/v1/otp/get"
    private const val KEY_TOKEN = "wbToken"
    /** Where the site keeps the access JWT (the same token its own code request sends). */
    private const val STORAGE_KEY = "wbid-oauth-sdk-access-token"
    /** WB switches the codes at midnight Moscow time ("Обновляются ежедневно в 00:00"). */
    private val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    private const val TIMEOUT_MS = 20_000
    /** WB ID client of the website (its login sends `client_id=marketplace_web`). */
    private const val CLIENT_ID = "marketplace_web"
    /** Any www.wildberries.ru page will do for the renewal call; this one is tiny. */
    private const val RENEW_PAGE = "https://www.wildberries.ru/robots.txt"
    private const val RENEW_TIMEOUT_MS = 30_000L
    /** Renew when less than this is left of the 30 days. */
    const val RENEW_BEFORE_MS = 7L * 24 * 3600 * 1000

    fun token(account: Account): String? = account.clientPrefs().getString(KEY_TOKEN, null)

    fun saveToken(account: Account, token: String) {
        account.clientPrefs().edit().putString(KEY_TOKEN, token).apply()
        DebugLog.d("wb", "token saved for $account, valid until ${DebugLog.at(expiry(token))}")
    }

    /** JWT `exp` in epoch ms; 0 if unreadable. */
    fun expiry(token: String): Long = runCatching {
        val payload = token.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).getLong("exp") * 1000
    }.getOrDefault(0L)

    /** From the account's preferences: no Chromium. */
    override fun isLoggedIn(account: Account): Boolean {
        val t = token(account) ?: return false
        return expiry(t) > System.currentTimeMillis()
    }

    /** The site's token in [web] (on www.wildberries.ru), or null while logged out / on another page. */
    fun readToken(web: WebView, done: (String?) -> Unit) {
        web.evaluateJavascript("(function(){try{return localStorage.getItem('$STORAGE_KEY')}catch(e){return null}})()") { raw ->
            val t = raw?.takeIf { it != "null" }?.trim('"')
            done(t?.takeIf { it.count { c -> c == '.' } == 2 })
        }
    }

    override suspend fun fetch(context: Context, account: Account): FetchResult {
        var token = token(account) ?: return FetchResult.LoginNeeded
        // The access token lives 30 days and the site never renews it by itself (it renews only
        // after a 401): do it a week ahead, while the refresh cookie (~3 months) is surely alive.
        if (expiry(token) - System.currentTimeMillis() < RENEW_BEFORE_MS) renew(context, account)?.let { token = it }
        if (expiry(token) <= System.currentTimeMillis()) {
            DebugLog.d("wb", "token of $account expired ${DebugLog.at(expiry(token))}")
            return FetchResult.LoginNeeded
        }
        val first = request(account, token)
        // Revoked early (a logout elsewhere?): one renewal, as the site's SDK does on a 401.
        if (first !is FetchResult.LoginNeeded) return first
        val renewed = renew(context, account) ?: return first
        return request(account, renewed)
    }

    /**
     * A new access token from WB ID, the way the site's own SDK (wb-id-sdk.js) renews it:
     * `POST /oauth-bff/api/v1/token` `{grantType: "refresh_token", clientId}` from a www.wildberries.ru
     * page; the refresh token is the HttpOnly cookie `wbid-sdk-refresh` (rotated in the answer).
     * Saved for the account and put into the site's localStorage. null = failed (logged in elsewhere
     * only, refresh cookie gone or expired, offline).
     */
    suspend fun renew(context: Context, account: Account): String? = withContext(Dispatchers.Main) {
        // The process must live until the rotated refresh cookie is on disk.
        OzonClient.alive.value++
        KeepAliveJob.start(context)
        val web = WebView(context.applicationContext).also { WebProfiles.apply(it, account.profile) }
        configure(web, account)
        val started = System.currentTimeMillis()
        DebugLog.d("wb", "renewing the token of $account (valid until ${DebugLog.at(token(account)?.let(::expiry) ?: 0)})")
        try {
            val (status, body) = withTimeoutOrNull(RENEW_TIMEOUT_MS) { renewIn(web) } ?: (0 to "timeout")
            val t = runCatching { JSONObject(body).optString("accessToken") }.getOrNull()
                ?.takeIf { it.count { c -> c == '.' } == 2 && expiry(it) > System.currentTimeMillis() }
            DebugLog.d("wb", "renewal after ${System.currentTimeMillis() - started} ms: HTTP $status" +
                if (t != null) ", new token valid until ${DebugLog.at(expiry(t))}" else ", ${body.take(120)}")
            if (t != null) {
                saveToken(account, t)
                web.evaluateJavascript("localStorage.setItem('$STORAGE_KEY', ${JSONObject.quote(t)})", null)
                BarcodeStore.log(AppSettings.strings().logWbRenewed(DebugLog.at(expiry(t))), account)
            }
            t
        } finally {
            WebProfiles.cookies(account.profile).flush()
            // Destroyed a moment later: the localStorage write above must land first.
            Handler(Looper.getMainLooper()).postDelayed({
                web.destroy()
                WebProfiles.cookies(account.profile).flush()
                OzonClient.alive.value--
            }, 1000)
        }
    }

    /** Loads a light www.wildberries.ru page (same origin = the refresh cookie goes along) and runs the call. */
    private suspend fun renewIn(web: WebView): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        var sent = false
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun result(status: Int, body: String) {
                Handler(Looper.getMainLooper()).post { if (cont.isActive) cont.resume(status to body) }
            }
        }, "WbBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (sent || !url.startsWith("https://www.wildberries.ru/")) return
                sent = true
                view.evaluateJavascript("""
                    fetch('/oauth-bff/api/v1/token', {method: 'POST', credentials: 'include',
                      headers: {'content-type': 'application/json', grantType: 'refresh_token'},
                      body: JSON.stringify({grantType: 'refresh_token', clientId: '$CLIENT_ID'})})
                      .then(r => r.text().then(b => WbBridge.result(r.status, b)))
                      .catch(e => WbBridge.result(-1, String(e)));
                """.trimIndent(), null)
            }
        }
        web.loadUrl(RENEW_PAGE)
    }

    private suspend fun request(account: Account, token: String): FetchResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val c = URL(CODES_URL).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            // What the site sends (wildberries.ru/lk); the token is all that matters.
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("wb-apptype", "site")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Origin", "https://www.wildberries.ru")
            c.setRequestProperty("Referer", "https://www.wildberries.ru/lk")
            try {
                val status = c.responseCode
                val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                DebugLog.d("wb", "answer after ${System.currentTimeMillis() - started} ms: HTTP $status, ${body.length} chars")
                when (status) {
                    200 -> parse(body, System.currentTimeMillis())
                        ?.let { days -> FetchResult.Ok(current(days), days) }
                        ?: FetchResult.Error(AppSettings.strings().wbNoCodes)
                    401, 403 -> FetchResult.LoginNeeded
                    else -> FetchResult.Error("HTTP $status")
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            DebugLog.d("wb", "request failed: ${e.javaClass.simpleName} ${e.message}")
            FetchResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun current(days: List<Barcode>, now: Long = System.currentTimeMillis()) =
        days.lastOrNull { it.validFrom <= now } ?: days.first()

    /**
     * `[{"date":"2026-11-18T12:00:00+00:00","code":"NNNNN","extCode":"NNNNNN","qr":"...","extQr":"..."}, ...]`.
     * The QR the site and the WB app show is `extQr`, the code to say aloud `extCode` ("NNN NNN").
     * Each entry counts from local midnight of its date. null = no usable entry.
     */
    fun parse(body: String, fetchedAt: Long): List<Barcode>? = runCatching {
        val a = JSONArray(body)
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val qr = o.optString("extQr").ifEmpty { o.optString("qr") }
            val spoken = o.optString("extCode").ifEmpty { o.optString("code") }
            if (qr.isEmpty()) return@mapNotNull null
            val day = OffsetDateTime.parse(o.getString("date")).atZoneSameInstant(ZONE).toLocalDate()
            Barcode(qr, spaced(spoken), fetchedAt, CodeFormat.QR, day.atStartOfDay(ZONE).toInstant().toEpochMilli())
        }.sortedBy { it.validFrom }.ifEmpty { null }
    }.getOrNull()

    /** "123456" -> "123 456", as the site writes it. */
    fun spaced(code: String) = if (code.length == 6 && code.all(Char::isDigit)) code.substring(0, 3) + " " + code.substring(3) else code

    override fun logout(account: Account) {
        account.clientPrefs().edit().remove(KEY_TOKEN).apply()
        // The profile is this account's own: its cookies (the WB ID refresh token) and the site's
        // storage go too, so the next login starts clean.
        val jar = WebProfiles.cookies(account.profile)
        jar.removeAllCookies(null)
        jar.flush()
        WebProfiles.storage(account.profile).deleteAllData()
        account.setLoggedIn(false)
    }
}
