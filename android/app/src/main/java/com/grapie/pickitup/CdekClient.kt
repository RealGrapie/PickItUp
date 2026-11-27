package com.grapie.pickitup

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * CDEK (research/cdek-pickup-code.md). The login is CDEK's own Keycloak page (phone + SMS) in
 * [LoginActivity], but started by us with PKCE and `offline_access`: the redirect back to the
 * site carries a code that we exchange ourselves for an offline refresh token (1 year, renewed on
 * every use). Each fetch: refresh -> access token (5 min) -> one GET. No WebView, no Chromium.
 * There is a code only while an order waits at a pickup point: else [FetchResult.Empty].
 */
object CdekClient : ProviderClient {
    private const val OIDC = "https://sso.cdek.ru/auth/realms/b2c/protocol/openid-connect"
    /** The web cabinet's public client and its redirect (CDEK's, not registered for us). */
    private const val CLIENT_ID = "superapp"
    const val REDIRECT = "https://my.cdek.ru/lkfl"
    private const val CODES_URL = "https://my.cdek.ru/api/lkfl/v2/pickupCodes/all"
    private const val TIMEOUT_MS = 20_000
    private const val KEY_REFRESH = "cdekRefresh"
    private const val KEY_REFRESH_UNTIL = "cdekRefreshUntil"
    private const val KEY_ACCESS = "cdekAccess"
    private const val KEY_ACCESS_UNTIL = "cdekAccessUntil"
    private const val KEY_VERIFIER = "cdekVerifier"
    private const val KEY_STATE = "cdekState"

    private fun prefs(account: Account) = account.clientPrefs()

    private fun random(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    /** A fresh PKCE login: the verifier and state wait in the account's prefs for the redirect. */
    override fun loginUrl(account: Account): String {
        val verifier = random(48)
        val state = random(16)
        prefs(account).edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse("$OIDC/auth").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", "openid offline_access")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build().toString()
    }

    /** Our login's redirect back (not the site's own logins when it is just browsed). */
    fun isLoginRedirect(account: Account, url: Uri): Boolean =
        url.toString().startsWith(REDIRECT) && url.getQueryParameter("code") != null &&
            url.getQueryParameter("state") == prefs(account).getString(KEY_STATE, null)

    /** The code from [isLoginRedirect] -> tokens. false = failed (the user can try again). */
    suspend fun finishLogin(account: Account, url: Uri): Boolean {
        val verifier = prefs(account).getString(KEY_VERIFIER, null) ?: return false
        val (status, body) = post(
            "$OIDC/token",
            "grant_type" to "authorization_code", "client_id" to CLIENT_ID,
            "code" to url.getQueryParameter("code").orEmpty(), "redirect_uri" to REDIRECT, "code_verifier" to verifier,
        )
        DebugLog.d("cdek", "login code exchange: HTTP $status")
        prefs(account).edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
        return status == 200 && saveTokens(account, body)
    }

    private fun saveTokens(account: Account, body: String): Boolean {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val refresh = o.optString("refresh_token").ifEmpty { return false }
        val access = o.optString("access_token").ifEmpty { return false }
        val now = System.currentTimeMillis()
        // refresh_expires_in 0 = no expiry of its own (offline token): count a year.
        val refreshFor = o.optLong("refresh_expires_in", 0).takeIf { it > 0 } ?: (365L * 24 * 3600)
        prefs(account).edit()
            .putString(KEY_REFRESH, refresh).putLong(KEY_REFRESH_UNTIL, now + refreshFor * 1000)
            .putString(KEY_ACCESS, access).putLong(KEY_ACCESS_UNTIL, now + o.optLong("expires_in", 60) * 1000)
            .apply()
        DebugLog.d("cdek", "tokens saved for $account, refresh valid until ${DebugLog.at(now + refreshFor * 1000)}")
        return true
    }

    override fun isLoggedIn(account: Account): Boolean =
        prefs(account).getString(KEY_REFRESH, null) != null &&
            prefs(account).getLong(KEY_REFRESH_UNTIL, 0) > System.currentTimeMillis()

    fun refreshUntil(account: Account) = prefs(account).getLong(KEY_REFRESH_UNTIL, 0)

    override suspend fun fetch(context: Context, account: Account): FetchResult {
        if (!isLoggedIn(account)) return FetchResult.LoginNeeded
        val p = prefs(account)
        var access = p.getString(KEY_ACCESS, null)?.takeIf { p.getLong(KEY_ACCESS_UNTIL, 0) - System.currentTimeMillis() > 30_000 }
        if (access == null) access = renew(account) ?: return renewFailure
        val first = request(access)
        if (first !is FetchResult.LoginNeeded) return first
        // Revoked early: one renewal, then it really is a new login.
        return request(renew(account) ?: return renewFailure)
    }

    /** Why the last [renew] gave nothing: a dead login or just no network. */
    private var renewFailure: FetchResult = FetchResult.LoginNeeded

    /** A new access token (and a new refresh token, saved at once). null = see [renewFailure]. */
    private suspend fun renew(account: Account): String? {
        val refresh = prefs(account).getString(KEY_REFRESH, null) ?: return null.also { renewFailure = FetchResult.LoginNeeded }
        val (status, body) = post("$OIDC/token", "grant_type" to "refresh_token", "client_id" to CLIENT_ID, "refresh_token" to refresh)
        DebugLog.d("cdek", "renewal: HTTP $status")
        if (status == 200 && saveTokens(account, body)) return prefs(account).getString(KEY_ACCESS, null)
        renewFailure = when (status) {
            // invalid_grant: the session is over (logged out, revoked, expired).
            400, 401 -> FetchResult.LoginNeeded.also { forget(account) }
            0 -> FetchResult.Error(body)
            else -> FetchResult.Error("HTTP $status")
        }
        return null
    }

    private suspend fun request(access: String): FetchResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val c = URL(CODES_URL).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Authorization", "Bearer $access")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("x-site-code", "ru")
            try {
                val status = c.responseCode
                val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                DebugLog.d("cdek", "answer after ${System.currentTimeMillis() - started} ms: HTTP $status, ${body.length} chars")
                when (status) {
                    200 -> parse(body, System.currentTimeMillis())
                    401 -> FetchResult.LoginNeeded
                    else -> FetchResult.Error("HTTP $status")
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            DebugLog.d("cdek", "request failed: ${e.javaClass.simpleName} ${e.message}")
            FetchResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * `{"data": {"pickupCodeInfo": {"barCode": "...", "code": "..."}, "courierCodeInfo": {...},
     * "postamatCodes": [...]}}`, or `{"data": null}` with no order waiting. The pickup point code is
     * one for all orders and drawn as Code 128 (as the site does); courier and parcel locker codes
     * are per order and not shown yet.
     */
    fun parse(body: String, fetchedAt: Long): FetchResult {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return FetchResult.Error("HTTP 200, not JSON")
        val data = root.optJSONObject("data")
        val info = data?.optJSONObject("pickupCodeInfo")
        val bar = info?.optString("barCode").orEmpty()
        DebugLog.d("cdek", "codes: pickup=${bar.length} chars, courier=${data?.optJSONObject("courierCodeInfo") != null}, " +
            "lockers=${data?.optJSONArray("postamatCodes")?.length() ?: 0}")
        if (bar.isEmpty()) return FetchResult.Empty
        val spoken = info?.optString("code").orEmpty().ifEmpty { bar }
        return FetchResult.Ok(Barcode(bar, spoken, fetchedAt, CodeFormat.CODE128))
    }

    private suspend fun post(url: String, vararg form: Pair<String, String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { out ->
                out.write(form.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray())
            }
            try {
                val status = c.responseCode
                status to ((if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            DebugLog.d("cdek", "POST failed: ${e.javaClass.simpleName} ${e.message}")
            0 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun forget(account: Account) {
        prefs(account).edit().remove(KEY_REFRESH).remove(KEY_REFRESH_UNTIL).remove(KEY_ACCESS).remove(KEY_ACCESS_UNTIL).apply()
    }

    override fun logout(account: Account) {
        // Ends the offline session at CDEK too (best effort: offline, it just stays until it expires).
        prefs(account).getString(KEY_REFRESH, null)?.let { refresh ->
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val (status, _) = post("$OIDC/logout", "client_id" to CLIENT_ID, "refresh_token" to refresh)
                DebugLog.d("cdek", "logout at CDEK: HTTP $status")
            }
        }
        forget(account)
        // The profile is this account's own: its SSO cookies go too, so the next login asks again.
        val jar = WebProfiles.cookies(account.profile)
        jar.removeAllCookies(null)
        jar.flush()
        WebProfiles.storage(account.profile).deleteAllData()
        account.setLoggedIn(false)
    }
}
