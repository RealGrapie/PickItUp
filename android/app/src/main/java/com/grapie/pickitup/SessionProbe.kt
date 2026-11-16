package com.grapie.pickitup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Research tool, sniff builds only (R.bool.session_probe): how long can an Ozon session stay idle?
 * Replaces the background schedule with one request after each gap in [GAPS_H] (the last one
 * repeats) and writes every outcome to [DebugLog] with the tag "probe". Any other request (pull
 * to refresh, the watchapp) restarts the current gap, since it touched the session too.
 * Texts are English on purpose: never shown in a normal build.
 */
object SessionProbe {
    private val GAPS_H = intArrayOf(6, 9, 12, 18, 24, 36, 48)
    private const val EARLY_MS = 10 * 60_000L  // an alarm a bit early still counts as the full gap
    private const val KEY_ACTIVE = "active"
    private const val KEY_STEP = "step"
    private const val KEY_LAST = "lastAt"
    private const val KEY_RESULT = "result"

    private var enabled = false
    private lateinit var prefs: android.content.SharedPreferences

    /** Text for the debug screen card. */
    val state = MutableStateFlow("")

    fun init(context: Context) {
        enabled = context.resources.getBoolean(R.bool.session_probe)
        prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)
        update()
    }

    val available get() = enabled
    val active get() = enabled && prefs.getBoolean(KEY_ACTIVE, false)

    private val step get() = prefs.getInt(KEY_STEP, 0)
    private fun gapMs(step: Int) = GAPS_H[step.coerceAtMost(GAPS_H.size - 1)] * 3_600_000L

    /** When the probe wants its next request (now, if the baseline request hasn't happened). */
    fun nextAt(): Long {
        val last = prefs.getLong(KEY_LAST, 0)
        return if (last == 0L) System.currentTimeMillis() else last + gapMs(step)
    }

    fun start(context: Context) {
        prefs.edit().putBoolean(KEY_ACTIVE, true).putInt(KEY_STEP, 0).putLong(KEY_LAST, 0)
            .putString(KEY_RESULT, "started ${fmt(System.currentTimeMillis())}").apply()
        DebugLog.d("probe", "START, gaps ${GAPS_H.joinToString()} h")
        update()
        RefreshJobService.schedule(context)
        RefreshJobService.startDailyRun(context)  // the baseline request, right away
    }

    fun stop(context: Context) {
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        DebugLog.d("probe", "STOPPED by hand at step ${step + 1}")
        update()
        RefreshJobService.schedule(context)
    }

    /** After every real Ozon request (BarcodeSync.refresh). */
    fun onFetch(context: Context, r: FetchResult) {
        if (!active) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0)
        val gap = if (last == 0L) 0 else now - last
        val gapText = "%.1f h".format(Locale.US, gap / 3_600_000.0)
        val want = gapMs(step)
        when (r) {
            is FetchResult.Ok -> {
                val msg = when {
                    last == 0L -> "baseline request OK"
                    gap >= want - EARLY_MS -> "ALIVE after an idle gap of $gapText (step ${step + 1}, wanted ${want / 3_600_000} h)"
                    else -> "request after only $gapText (manual / watchapp?): step ${step + 1} restarts"
                }
                val nextStep = if (last != 0L && gap >= want - EARLY_MS) step + 1 else step
                prefs.edit().putLong(KEY_LAST, now).putInt(KEY_STEP, nextStep).putString(KEY_RESULT, "${fmt(now)}: $msg").apply()
                DebugLog.d("probe", msg)
                DebugLog.tokens("probe", "tokens now")
            }
            is FetchResult.LoginNeeded -> {
                val msg = "SESSION DEAD after an idle gap of $gapText (step ${step + 1}); last good request ${if (last == 0L) "-" else fmt(last)}"
                prefs.edit().putBoolean(KEY_ACTIVE, false).putString(KEY_RESULT, "${fmt(now)}: $msg").apply()
                DebugLog.d("probe", msg)
                DebugLog.tokens("probe", "tokens now")
            }
            // Antibot page, network, VPN: the session wasn't asked; the job retries in 30 min.
            else -> DebugLog.d("probe", "no answer ($r) after $gapText, retry")
        }
        update()
        RefreshJobService.schedule(context)
    }

    private fun update() {
        if (!enabled) return
        val result = prefs.getString(KEY_RESULT, null) ?: "not started"
        state.value = if (active) "Running, step ${step + 1} (${gapMs(step) / 3_600_000} h idle), next request ${fmt(nextAt())}\nLast: $result"
        else "Stopped. Last: $result"
    }

    private fun fmt(t: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(t))
}
