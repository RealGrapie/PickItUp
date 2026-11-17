package com.grapie.pickitup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** What a provider's client does for one account; Ozon is the only one so far. */
interface ProviderClient {
    /** One request for the account's pickup code, through its own WebView profile. */
    suspend fun fetch(context: Context, account: Account): FetchResult
    /** From the account's cookies: loads Chromium, so only where a WebView runs anyway. */
    fun isLoggedIn(account: Account): Boolean
    fun logout(account: Account)
}

/** A marketplace the app can get a pickup code from. */
enum class Provider(val id: String, val title: String, val homeUrl: String, val loginUrl: String, val ordersUrl: String) {
    // The bare Ozon ID form: one tap shorter than going through the account page.
    OZON("ozon", "Ozon", "https://www.ozon.ru/", "https://www.ozon.ru/ozonid-lite", "https://www.ozon.ru/my/orderlist");

    val client: ProviderClient get() = when (this) {
        OZON -> OzonClient
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * One login at one marketplace: its own WebView profile (cookie jar) and its own last code.
 * [loggedIn] is the provider's login state as last read: reading the cookie loads all of
 * Chromium (~40 MB), so it's re-read only where a WebView runs anyway (login page, fetch, logout).
 */
class Account internal constructor(
    val id: String,
    val provider: Provider,
    /** WebView profile name; the account from before multi-account keeps the default one. */
    val profile: String,
    private val prefs: SharedPreferences,
) {
    val barcode = MutableStateFlow<Barcode?>(null)
    /** What the UI and the watch show: [barcode], or a sample code in demo mode. */
    val shown = MutableStateFlow<Barcode?>(null)
    val loggedIn = MutableStateFlow(false)
    /** The last request said the login is gone (the main screen offers to log in again). */
    val loginNeeded = MutableStateFlow(false)
    val busy = MutableStateFlow(false)

    init {
        barcode.value = prefs.getString("code", null)?.let {
            Barcode(it, prefs.getString("visible", it)!!, prefs.getLong("fetchedAt", 0))
        }
        loggedIn.value = prefs.getBoolean("loggedIn", false)
    }

    internal fun hasLoginState() = prefs.contains("loggedIn")

    fun setLoggedIn(on: Boolean) {
        prefs.edit().putBoolean("loggedIn", on).apply()
        loggedIn.value = on
    }

    fun save(b: Barcode) {
        prefs.edit().putString("code", b.code).putString("visible", b.visible).putLong("fetchedAt", b.fetchedAt).apply()
        barcode.value = b
        updateShown()
    }

    fun clear() {
        prefs.edit().remove("code").remove("visible").remove("fetchedAt").apply()
        barcode.value = null
        updateShown()
    }

    internal fun updateShown() {
        shown.value = if (BarcodeStore.demo.value) BarcodeStore.demoBarcode() else barcode.value
    }

    /** Per-account values of the provider's client (e.g. when the login pair was last renewed). */
    fun clientPrefs(): SharedPreferences = prefs

    override fun toString() = id
}

/**
 * All accounts. Before multi-account there was exactly one Ozon login in the default WebView
 * profile with its code in the "barcode" preferences: it becomes the account "ozon" as it is.
 */
object Accounts {
    private const val FIRST_ID = "ozon"
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    val list = MutableStateFlow<List<Account>>(emptyList())

    /** The account the screens and the watch work with (the only one, for now). */
    val active: Account get() = list.value.first()

    fun byId(id: String?) = list.value.firstOrNull { it.id == id }

    /** After BarcodeStore and WebProfiles. */
    fun init(context: Context) {
        this.context = context.applicationContext
        prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
        var ids = prefs.getString("ids", null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        if (ids.isEmpty()) {
            migrate()
            ids = listOf(FIRST_ID)
        }
        list.value = ids.mapNotNull(::load)
        if (list.value.isEmpty()) list.value = listOf(load(FIRST_ID)!!)
        list.value.forEach { a ->
            // Builds before the login cache had only the cookie: read it once.
            if (!a.hasLoginState()) a.setLoggedIn(a.provider.client.isLoggedIn(a))
            a.updateShown()
        }
    }

    private fun accountPrefs(id: String) = context.getSharedPreferences("account_$id", Context.MODE_PRIVATE)

    private fun load(id: String): Account? {
        val provider = Provider.byId(prefs.getString("$id.provider", null)) ?: return null
        val profile = prefs.getString("$id.profile", null) ?: return null
        return Account(id, provider, profile, accountPrefs(id))
    }

    /** The single Ozon login of 1.x: default profile, code and login state from "barcode". */
    private fun migrate() {
        val old = context.getSharedPreferences("barcode", Context.MODE_PRIVATE)
        val to = accountPrefs(FIRST_ID).edit()
        old.getString("code", null)?.let { to.putString("code", it) }
        old.getString("visible", null)?.let { to.putString("visible", it) }
        if (old.contains("fetchedAt")) to.putLong("fetchedAt", old.getLong("fetchedAt", 0))
        if (old.contains("loggedIn")) to.putBoolean("loggedIn", old.getBoolean("loggedIn", false))
        // When the login pair was last renewed (OzonClient), kept in its own file before.
        val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        if (session.contains("pairSeenAt")) to.putLong("pairSeenAt", session.getLong("pairSeenAt", 0))
        to.commit()
        prefs.edit()
            .putString("$FIRST_ID.provider", Provider.OZON.id)
            .putString("$FIRST_ID.profile", WebProfiles.DEFAULT)
            .putString("ids", FIRST_ID)
            .commit()
        // The copies are the truth now; the old keys would only go stale.
        old.edit().remove("code").remove("visible").remove("fetchedAt").remove("loggedIn").apply()
        DebugLog.d("accounts", "migrated the 1.x login to account $FIRST_ID")
    }

    /** Every account's cookies to disk (the process may be killed any moment). */
    fun flushCookies() {
        list.value.forEach { runCatching { WebProfiles.cookies(it.profile).flush() } }
    }
}
