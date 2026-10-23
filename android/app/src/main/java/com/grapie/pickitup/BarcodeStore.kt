package com.grapie.pickitup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Barcode(val code: String, val visible: String, val fetchedAt: Long)

/** Last fetched barcode (persisted) plus process-wide UI state. */
object BarcodeStore {
    private lateinit var prefs: SharedPreferences

    val barcode = MutableStateFlow<Barcode?>(null)
    /** What the UI and the watch show: [barcode], or a sample code in demo mode. */
    val shown = MutableStateFlow<Barcode?>(null)
    /** Demo mode for screenshots: sample code "12345678*9012", no real orders page. */
    val demo = MutableStateFlow(false)
    private var demoAt = System.currentTimeMillis()
    val busy = MutableStateFlow(false)
    val loginNeeded = MutableStateFlow(false)
    /**
     * [OzonClient.isLoggedIn], remembered: reading the cookie loads all of Chromium (~40 MB) into
     * the process, so it's re-read only where a WebView runs anyway (login page, fetch, logout).
     */
    val loggedIn = MutableStateFlow(false)
    val watchStatus = MutableStateFlow(WatchStatus.UNKNOWN)
    /** Seconds without button presses before the watchapp closes itself; 0 = never. */
    val exitTimeout = MutableStateFlow(60)
    /** Refresh every [refreshHours] or daily at [dailyMinutes] (see [RefreshSchedule]). */
    val refreshMode = MutableStateFlow(RefreshMode.INTERVAL)
    val refreshHours = MutableStateFlow(RefreshSchedule.DEFAULT_INTERVAL_HOURS)
    val dailyMinutes = MutableStateFlow(RefreshSchedule.DEFAULT_DAILY_MINUTES)
    /** Also refresh on that schedule in the background ([RefreshJobService]). */
    val backgroundRefresh = MutableStateFlow(true)
    val log = MutableStateFlow<List<String>>(emptyList())

    fun init(context: Context) {
        prefs = context.getSharedPreferences("barcode", Context.MODE_PRIVATE)
        val code = prefs.getString("code", null)
        exitTimeout.value = prefs.getInt("exitTimeout", 60)
        refreshMode.value = runCatching { RefreshMode.valueOf(prefs.getString("refreshMode", null)!!) }
            .getOrDefault(RefreshMode.INTERVAL)
        // Old builds offered 20 h / 2 days; anything not offered any more falls back to the default.
        refreshHours.value = prefs.getInt("refreshHours", RefreshSchedule.DEFAULT_INTERVAL_HOURS)
            .takeIf { it in RefreshSchedule.INTERVAL_OPTIONS } ?: RefreshSchedule.DEFAULT_INTERVAL_HOURS
        dailyMinutes.value = prefs.getInt("dailyMinutes", RefreshSchedule.DEFAULT_DAILY_MINUTES)
        backgroundRefresh.value = prefs.getBoolean("backgroundRefresh", true)
        barcode.value = code?.let {
            Barcode(it, prefs.getString("visible", it)!!, prefs.getLong("fetchedAt", 0))
        }
        demo.value = prefs.getBoolean("demo", false)
        // Builds before the cache had only the cookie: read it once.
        if (prefs.contains("loggedIn")) loggedIn.value = prefs.getBoolean("loggedIn", false)
        else setLoggedIn(OzonClient.isLoggedIn())
        updateShown()
    }

    fun setLoggedIn(on: Boolean) {
        prefs.edit().putBoolean("loggedIn", on).apply()
        loggedIn.value = on
    }

    fun save(b: Barcode) {
        prefs.edit().putString("code", b.code).putString("visible", b.visible)
            .putLong("fetchedAt", b.fetchedAt).apply()
        barcode.value = b
        updateShown()
    }

    fun setDemo(on: Boolean) {
        prefs.edit().putBoolean("demo", on).apply()
        demo.value = on
        demoAt = System.currentTimeMillis()
        updateShown()
    }

    /** A fake refresh in demo mode: the sample code just gets a new time. */
    fun touchDemo() {
        demoAt = System.currentTimeMillis()
        updateShown()
    }

    private fun updateShown() {
        shown.value = if (demo.value) Barcode(DEMO_CODE, DEMO_VISIBLE, demoAt) else barcode.value
    }

    const val DEMO_CODE = "12345678*9012"
    const val DEMO_VISIBLE = "1234 5678 * 9012"

    fun setRefreshHours(hours: Int) {
        prefs.edit().putInt("refreshHours", hours).apply()
        refreshHours.value = hours
    }

    fun setRefreshMode(mode: RefreshMode) {
        prefs.edit().putString("refreshMode", mode.name).apply()
        refreshMode.value = mode
    }

    fun setDailyMinutes(minutes: Int) {
        prefs.edit().putInt("dailyMinutes", minutes).apply()
        dailyMinutes.value = minutes
    }

    fun setBackgroundRefresh(on: Boolean) {
        prefs.edit().putBoolean("backgroundRefresh", on).apply()
        backgroundRefresh.value = on
    }

    fun setExitTimeout(seconds: Int) {
        prefs.edit().putInt("exitTimeout", seconds).apply()
        exitTimeout.value = seconds
    }

    fun clear() {
        prefs.edit().remove("code").remove("visible").remove("fetchedAt").apply()
        barcode.value = null
        updateShown()
    }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun log(msg: String) {
        log.value = (log.value + "${fmt.format(Date())}  $msg").takeLast(100)
    }
}
