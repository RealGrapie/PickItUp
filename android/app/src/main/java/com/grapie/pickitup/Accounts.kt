package com.grapie.pickitup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** What a provider's client does for one account. */
interface ProviderClient {
    /** One request for the account's pickup code (Ozon: through its own WebView profile). */
    suspend fun fetch(context: Context, account: Account): FetchResult
    /** Ozon: from the account's cookies, which loads Chromium, so only where a WebView runs anyway. */
    fun isLoggedIn(account: Account): Boolean
    fun logout(account: Account)

    /** A WebView showing this marketplace's site (login page, orders), before it loads anything. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun configure(web: android.webkit.WebView, account: Account) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Look like mobile Chrome rather than an embedded WebView.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        WebProfiles.cookies(account.profile).setAcceptThirdPartyCookies(web, true)
    }
}

/**
 * A marketplace the app can get a pickup code from. [title] is the short name for headers
 * ("WB · Мама", "WB: Mama" on the watch), [fullName] the one in sentences ("Войти в Wildberries").
 */
enum class Provider(
    val id: String, val title: String, val fullName: String,
    val homeUrl: String, val loginUrl: String, val ordersUrl: String,
) {
    // The bare Ozon ID form: one tap shorter than going through the account page.
    OZON("ozon", "Ozon", "Ozon", "https://www.ozon.ru/", "https://www.ozon.ru/ozonid-lite", "https://www.ozon.ru/my/orderlist"),
    // The account page sends a guest to WB ID (phone + SMS) and comes back here once logged in.
    WB("wb", "WB", "Wildberries", "https://www.wildberries.ru/", "https://www.wildberries.ru/lk", "https://www.wildberries.ru/lk/myorders/delivery");

    val client: ProviderClient get() = when (this) {
        OZON -> OzonClient
        WB -> WbClient
    }

    /** One of the app's texts about Ozon, about this marketplace instead (the name isn't declined in RU). */
    /** An Ozon text for this marketplace: the name, and "code" where Ozon has a barcode (WB = a QR). */
    fun text(s: String) = if (this == OZON) s else s.replace("Ozon", fullName)
        .replace("штрихкод", "код").replace("Штрихкод", "Код").replace("barcode", "code").replace("Barcode", "Code")

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
    name: String,
) {
    /** The user's name for it ("Я", "Мама"); empty = just the provider's name. */
    val name = MutableStateFlow(name)
    /** Label on cards and rows: "Ozon" or "Ozon · Мама". */
    fun label(name: String = this.name.value) = if (name.isBlank()) provider.title else "${provider.title} · $name"

    /** Today's code (Ozon: the one code there is). */
    val barcode = MutableStateFlow<Barcode?>(null)
    /** What the UI and the watch show: [barcode], or a sample code in demo mode. */
    val shown = MutableStateFlow<Barcode?>(null)
    val loggedIn = MutableStateFlow(false)
    /** The last request said the login is gone (the main screen offers to log in again). */
    val loginNeeded = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    /** One code per day, oldest first (Wildberries); empty for Ozon. */
    var days: List<Barcode> = emptyList()
        private set

    init {
        days = prefs.getString("days", null)?.let(::parseDays).orEmpty()
        barcode.value = if (days.isNotEmpty()) current(days) else prefs.getString("code", null)?.let {
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
        prefs.edit().putString("code", b.code).putString("visible", b.visible).putLong("fetchedAt", b.fetchedAt).remove("days").apply()
        days = emptyList()
        barcode.value = b
        updateShown()
    }

    /** A week of day codes: [barcode] follows the calendar from now on (see [updateShown]). */
    fun saveDays(list: List<Barcode>) {
        val sorted = list.sortedBy { it.validFrom }
        prefs.edit().putString("days", daysJson(sorted)).remove("code").remove("visible").remove("fetchedAt").apply()
        days = sorted
        barcode.value = current(sorted)
        updateShown()
    }

    /** The days still to come after today's, for the watch to switch to at midnight by itself. */
    fun upcoming(now: Long = System.currentTimeMillis()): List<Barcode> = days.filter { it.validFrom > now }

    fun clear() {
        prefs.edit().remove("code").remove("visible").remove("fetchedAt").remove("days").apply()
        days = emptyList()
        barcode.value = null
        updateShown()
    }

    /** Also moves to the next day's code once its midnight has passed. */
    internal fun updateShown() {
        if (days.isNotEmpty()) current(days).let { if (it != barcode.value) barcode.value = it }
        shown.value = if (BarcodeStore.demo.value) BarcodeStore.demoBarcode(provider) else barcode.value
    }

    private companion object {
        /** The latest day that has begun; before the first one (clock off), the first. */
        fun current(days: List<Barcode>, now: Long = System.currentTimeMillis()) =
            days.lastOrNull { it.validFrom <= now } ?: days.first()

        fun daysJson(list: List<Barcode>) = org.json.JSONArray().apply {
            list.forEach {
                put(org.json.JSONObject().put("code", it.code).put("visible", it.visible).put("fetchedAt", it.fetchedAt)
                    .put("format", it.format.name).put("validFrom", it.validFrom))
            }
        }.toString()

        fun parseDays(json: String): List<Barcode> = runCatching {
            val a = org.json.JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Barcode(o.getString("code"), o.getString("visible"), o.getLong("fetchedAt"),
                    runCatching { CodeFormat.valueOf(o.getString("format")) }.getOrDefault(CodeFormat.QR), o.getLong("validFrom"))
            }
        }.getOrDefault(emptyList())
    }

    /** Per-account values of the provider's client (e.g. when the login pair was last renewed). */
    fun clientPrefs(): SharedPreferences = prefs

    /** In logs: "ozon/Default", "ozon/account2" - stable, and no digit run for the masker to eat (ids have one). */
    override fun toString() = "${provider.id}/$profile"
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

    /** The first account: the one the watch gets (until the watch learns several codes). */
    val active: Account get() = list.value.first()

    fun byId(id: String?) = list.value.firstOrNull { it.id == id }

    /** A second account needs its own WebView profile; old WebViews only have the default one. */
    fun canAdd() = WebProfiles.supported() || list.value.none { it.profile == WebProfiles.DEFAULT }

    /** A new, not yet logged in account; the caller opens its login page. */
    fun add(provider: Provider): Account {
        val used = list.value.map { it.profile }.toSet()
        // The default profile when free (its account was removed), else account2, account3...
        val profile = if (WebProfiles.DEFAULT !in used) WebProfiles.DEFAULT
        else generateSequence(2) { it + 1 }.map { "account$it" }.first { it !in used }
        val id = "${provider.id}-${System.currentTimeMillis()}"
        prefs.edit().putString("$id.provider", provider.id).putString("$id.profile", profile).commit()
        val account = load(id)!!
        // A profile left from before may still hold a login: then the account starts logged in.
        account.setLoggedIn(provider.client.isLoggedIn(account))
        account.updateShown()
        list.value = list.value + account
        saveIds()
        DebugLog.d("accounts", "added $id (profile $profile, logged in ${account.loggedIn.value})")
        return account
    }

    /** Logs out and forgets the account, its code and (if not the default one) its WebView profile. */
    fun remove(account: Account) {
        if (list.value.size <= 1 || account !in list.value) return
        runCatching { account.provider.client.logout(account) }
        list.value = list.value - account
        saveIds()
        prefs.edit().remove("${account.id}.provider").remove("${account.id}.profile").remove("${account.id}.name").apply()
        context.deleteSharedPreferences("account_${account.id}")
        // Fails while a WebView still uses it (a page settling): its cookies are gone anyway.
        if (account.profile != WebProfiles.DEFAULT) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(account.profile) }
            .onFailure { DebugLog.d("accounts", "profile ${account.profile} kept: ${it.javaClass.simpleName}") }
        DebugLog.d("accounts", "removed ${account.id}")
    }

    /** The one account, still empty, moved to another marketplace (onboarding: "log in to Wildberries"). */
    fun switchProvider(account: Account, provider: Provider): Account {
        if (account.provider == provider || account !in list.value) return account
        prefs.edit().putString("${account.id}.provider", provider.id).commit()
        val moved = load(account.id)!!
        moved.setLoggedIn(provider.client.isLoggedIn(moved))
        moved.updateShown()
        list.value = list.value.map { if (it.id == account.id) moved else it }
        DebugLog.d("accounts", "${account.id} moved to ${provider.id}")
        return moved
    }

    fun rename(account: Account, name: String) {
        val clean = name.trim().take(24)
        prefs.edit().putString("${account.id}.name", clean).apply()
        account.name.value = clean
    }

    private fun saveIds() {
        prefs.edit().putString("ids", list.value.joinToString(",") { it.id }).apply()
    }

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
        return Account(id, provider, profile, accountPrefs(id), prefs.getString("$id.name", "")!!)
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
