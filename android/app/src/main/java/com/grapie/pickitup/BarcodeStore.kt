package com.grapie.pickitup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Barcode(val code: String, val visible: String, val fetchedAt: Long)

/** App-wide settings and state; each account's code and login live in [Account]. */
object BarcodeStore {
    private lateinit var prefs: SharedPreferences

    /** Demo mode for screenshots: sample code "12345678*9012", no real orders page. */
    val demo = MutableStateFlow(false)
    private var demoAt = System.currentTimeMillis()
    val watchStatus = MutableStateFlow(WatchStatus.UNKNOWN)
    /** Seconds without button presses before the watchapp closes itself; 0 = never. */
    val exitTimeout = MutableStateFlow(120)
    /** Refresh every [refreshHours] or daily at [dailyMinutes] (see [RefreshSchedule]). */
    val refreshMode = MutableStateFlow(RefreshMode.DAILY)
    val refreshHours = MutableStateFlow(RefreshSchedule.DEFAULT_INTERVAL_HOURS)
    val dailyMinutes = MutableStateFlow(RefreshSchedule.DEFAULT_DAILY_MINUTES)
    /** Also refresh on that schedule in the background ([RefreshJobService]). */
    val backgroundRefresh = MutableStateFlow(true)
    /** After a background refresh the watchapp isn't open for: start it, so the new code lands on the watch. */
    val openWatchappAfterRefresh = MutableStateFlow(false)
    /** Off for people without a Pebble: no sends to the watch, no watch UI. */
    val watchEnabled = MutableStateFlow(true)
    val log = MutableStateFlow<List<String>>(emptyList())

    fun init(context: Context) {
        prefs = context.getSharedPreferences("barcode", Context.MODE_PRIVATE)
        exitTimeout.value = prefs.getInt("exitTimeout", 120)
        refreshMode.value = runCatching { RefreshMode.valueOf(prefs.getString("refreshMode", null)!!) }
            .getOrDefault(RefreshMode.DAILY)
        // Old builds offered 20 h / 2 days; anything not offered any more falls back to the default.
        refreshHours.value = prefs.getInt("refreshHours", RefreshSchedule.DEFAULT_INTERVAL_HOURS)
            .takeIf { it in RefreshSchedule.INTERVAL_OPTIONS } ?: RefreshSchedule.DEFAULT_INTERVAL_HOURS
        dailyMinutes.value = prefs.getInt("dailyMinutes", RefreshSchedule.DEFAULT_DAILY_MINUTES)
        backgroundRefresh.value = prefs.getBoolean("backgroundRefresh", true)
        openWatchappAfterRefresh.value = prefs.getBoolean("openWatchappAfterRefresh", false)
        watchEnabled.value = prefs.getBoolean("watchEnabled", true)
        demo.value = prefs.getBoolean("demo", false)
        log.value = prefs.getString("journal", null)?.split('\n').orEmpty()
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
        Accounts.list.value.forEach { it.updateShown() }
    }

    fun demoBarcode() = Barcode(DEMO_CODE, DEMO_VISIBLE, demoAt)

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

    fun setOpenWatchappAfterRefresh(on: Boolean) {
        prefs.edit().putBoolean("openWatchappAfterRefresh", on).apply()
        openWatchappAfterRefresh.value = on
    }

    fun setWatchEnabled(on: Boolean) {
        prefs.edit().putBoolean("watchEnabled", on).apply()
        watchEnabled.value = on
        if (!on) watchStatus.value = WatchStatus.UNKNOWN
    }

    fun setExitTimeout(seconds: Int) {
        prefs.edit().putInt("exitTimeout", seconds).apply()
        exitTimeout.value = seconds
    }

    // With the date: the journal is kept across restarts (background runs start the app anew).
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    fun log(msg: String) {
        DebugLog.d("journal", msg)
        log.value = (log.value + "${fmt.format(Date())}  $msg").takeLast(100)
        prefs.edit().putString("journal", log.value.joinToString("\n")).apply()
    }

    fun clearLog() {
        DebugLog.clear()
        log.value = emptyList()
        prefs.edit().remove("journal").apply()
    }
}
