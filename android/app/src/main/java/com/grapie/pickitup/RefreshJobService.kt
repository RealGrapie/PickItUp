package com.grapie.pickitup

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background refresh, so the code is already current when the watchapp opens (even after days).
 * INTERVAL mode: a periodic job every [BarcodeStore.refreshHours].
 * DAILY mode: an allow-while-idle alarm at the next [BarcodeStore.dailyMinutes] starts an
 * expedited job, re-armed after each run. A plain job with a minimum latency is not enough: in
 * Doze its timer waits for the next maintenance window, hours late at night, even for an app
 * exempt from battery optimization. The expedited job then waits for a network if there is none.
 */
class RefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartJob(params: JobParameters): Boolean {
        val daily = params.extras.containsKey(EXTRA_TARGET)
        // Logged out: nothing to do; the alarm chain goes on by itself. Demo mode refreshes (fake) for tests.
        if (!BarcodeStore.loggedIn.value && !BarcodeStore.demo.value) return false
        val waited = params.extras.getLong(EXTRA_TARGET, 0).takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000 }
        DebugLog.d("job", "started daily=$daily" + (waited?.let { ", ${it} s after the alarm" } ?: "") +
            ", job network=" + (if (params.network != null) "yes" else "none") + "; " + DebugLog.deviceState(applicationContext))
        BarcodeStore.log(AppSettings.strings().logBackground)
        scope.launch {
            val r = BarcodeSync.refresh(applicationContext)
            // Delivered only if the watchapp is open right now; otherwise it asks on its next start.
            if (r is FetchResult.Ok) {
                BarcodeSync.sendToWatch(applicationContext)
                // Not open (watchface or another app): start it if asked to; it then requests the new code.
                // Not connected / no Pebble app: nothing to start.
                if (BarcodeStore.openWatchappAfterRefresh.value && BarcodeStore.watchStatus.value == WatchStatus.OTHER_APP_OPEN) {
                    DebugLog.d("job", "watchapp not open: starting it (setting on)")
                    BarcodeSync.openWatchapp(applicationContext).join()
                }
            }
            // Keep the process alive while the hidden page settles (rotated login tokens).
            if (r !is FetchResult.Vpn && !BarcodeStore.demo.value) withTimeoutOrNull(OzonClient.SETTLE_MS + 2_000) { OzonClient.alive.first { it == 0 } }
            if (daily) {
                jobFinished(params, false)
                // After a failure try again in half an hour (the next day's alarm is already set).
                if (r is FetchResult.Error || r is FetchResult.Vpn) schedule(applicationContext, retryAt = System.currentTimeMillis() + RETRY_MS)
            } else {
                jobFinished(params, r is FetchResult.Error || r is FetchResult.Vpn)  // periodic: let the system back off and retry
            }
        }
        return true
    }

    // Android 14+ calls this for jobs with a network constraint; nothing to do, the fetch uses any network.
    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onStopJob(params: JobParameters): Boolean {
        DebugLog.d("job", "STOPPED by the system, reason ${params.stopReason}; " + DebugLog.deviceState(applicationContext))
        BarcodeStore.log(AppSettings.strings().logBackgroundStopped)
        // The hidden page lives on (KeepAliveJob holds the process), but write what we have now.
        android.webkit.CookieManager.getInstance().flush()
        scope.coroutineContext.cancelChildren()
        return true
    }

    companion object {
        private const val JOB_ID = 1001
        private const val EXTRA_TARGET = "target"
        private const val RETRY_MS = 30 * 60_000L
        private const val KEY_ALARM_AT = "alarmAt"
        private const val KEY_RETRY_AT = "retryAt"
        const val ACTION_DAILY = "com.grapie.pickitup.DAILY_REFRESH"
        /** Debug only: a look at the expedited job a while after the alarm, if it still hasn't run. */
        const val ACTION_CHECK = "com.grapie.pickitup.CHECK_RUN"
        private val CHECK_AFTER_MS = longArrayOf(60_000L, 10 * 60_000L, 30 * 60_000L)

        /** (Re)schedules the job / alarm for the current settings; a no-op when nothing changed. */
        fun schedule(context: Context, retryAt: Long? = null) {
            // Runs from Application.onCreate: a scheduler error must never crash the app.
            runCatching { scheduleOrThrow(context, retryAt) }
                .onFailure { Log.w("RefreshJob", "schedule failed", it) }
        }

        private fun scheduleOrThrow(context: Context, retryAt: Long?) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val pending = scheduler.getPendingJob(JOB_ID)
            if (SessionProbe.active) {
                // The probe owns the schedule: one request after each idle gap, nothing in between.
                if (pending != null && !pending.isExpedited) scheduler.cancel(JOB_ID)
                if (!BarcodeStore.loggedIn.value) { cancelAlarm(context); return }
                val now = System.currentTimeMillis()
                val prefs = prefs(context)
                val retry = retryAt ?: prefs.getLong(KEY_RETRY_AT, 0).takeIf { it > now }
                var target = if (retry != null) minOf(retry, SessionProbe.nextAt()) else SessionProbe.nextAt()
                // Passed but no answer yet (the job may be waiting for a network): look again later.
                if (target <= now) target = now + RETRY_MS
                DebugLog.d("job", "probe alarm at " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(target)))
                prefs.edit().putLong(KEY_ALARM_AT, target).putLong(KEY_RETRY_AT, retry ?: 0).apply()
                val alarms = context.getSystemService(AlarmManager::class.java)
                if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, alarmIntent(context))
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, alarmIntent(context))
                return
            }
            if (!BarcodeStore.backgroundRefresh.value) {
                scheduler.cancel(JOB_ID)
                cancelAlarm(context)
                return
            }
            when (BarcodeStore.refreshMode.value) {
                RefreshMode.INTERVAL -> {
                    cancelAlarm(context)
                    val periodMs = BarcodeStore.refreshHours.value * 3_600_000L
                    // schedule() restarts the period, so leave an identical job alone.
                    if (pending != null && pending.isPeriodic && pending.intervalMillis == periodMs) return
                    scheduler.schedule(jobBuilder(context).setPeriodic(periodMs).build())
                }
                RefreshMode.DAILY -> {
                    // Keep only a run waiting for a network (an older version's delayed job goes too).
                    if (pending != null && !pending.isExpedited) scheduler.cancel(JOB_ID)
                    val now = System.currentTimeMillis()
                    val prefs = prefs(context)
                    // A pending retry survives the app restarting (every start calls schedule()).
                    val retry = retryAt ?: prefs.getLong(KEY_RETRY_AT, 0).takeIf { it > now }
                    val next = RefreshSchedule.nextDaily(now, BarcodeStore.dailyMinutes.value)
                    val target = if (retry != null) minOf(retry, next) else next
                    DebugLog.d("job", "daily alarm at " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(target)))
                    prefs.edit().putLong(KEY_ALARM_AT, target).putLong(KEY_RETRY_AT, retry ?: 0).apply()
                    // Both fire in Doze; the inexact one may come up to an hour late.
                    val alarms = context.getSystemService(AlarmManager::class.java)
                    if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, alarmIntent(context))
                    else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, alarmIntent(context))
                }
            }
        }

        /** From the daily alarm: refresh now, or as soon as there is a network. */
        fun startDailyRun(context: Context) {
            prefs(context).edit().putLong(KEY_RETRY_AT, 0).apply()
            // Expedited jobs run in Doze too; they can't have a delay, the alarm took care of that.
            val job = jobBuilder(context)
                .setExpedited(true)
                .setExtras(PersistableBundle().apply { putLong(EXTRA_TARGET, System.currentTimeMillis()) })
                .build()
            runCatching { context.getSystemService(JobScheduler::class.java).schedule(job) }
                .onSuccess { DebugLog.d("job", "expedited run requested: " + if (it == JobScheduler.RESULT_SUCCESS) "ok" else "REFUSED ($it)") }
                .onFailure { Log.w("RefreshJob", "daily run failed", it); DebugLog.d("job", "expedited run FAILED: ${it.javaClass.simpleName}") }
            // If it waits, these say why (JobScheduler's reason + device state) instead of a silent gap.
            runCatching {
                val alarms = context.getSystemService(AlarmManager::class.java)
                val now = System.currentTimeMillis()
                CHECK_AFTER_MS.forEachIndexed { i, after ->
                    // Inexact on purpose: Doze limits exact idle alarms to ~1 per 9 min, keep that for the refresh.
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + after, checkIntent(context, i))
                }
            }
        }

        /** Logs whether the run is still waiting and why. */
        fun logCheck(context: Context, label: String) {
            val reason = DebugLog.pendingReason(context, JOB_ID)
            // Once it ran, later checks have nothing to say.
            if (reason.startsWith("not pending") && label != "alarm+8s") return
            DebugLog.d("job", "check $label: $reason; " + DebugLog.deviceState(context))
        }

        private fun checkIntent(context: Context, i: Int): PendingIntent = PendingIntent.getBroadcast(
            context, 10 + i, Intent(context, RefreshAlarmReceiver::class.java).setAction(ACTION_CHECK).putExtra("i", i),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun checkLabel(i: Int) = "alarm+" + (CHECK_AFTER_MS.getOrNull(i)?.div(60_000)?.let { "${it}min" } ?: "?")

        fun isScheduled(context: Context): Boolean {
            if (context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null) return true
            return (SessionProbe.active || BarcodeStore.backgroundRefresh.value && BarcodeStore.refreshMode.value == RefreshMode.DAILY) &&
                prefs(context).getLong(KEY_ALARM_AT, 0) > System.currentTimeMillis()
        }

        private fun jobBuilder(context: Context) =
            JobInfo.Builder(JOB_ID, ComponentName(context, RefreshJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, RefreshAlarmReceiver::class.java).setAction(ACTION_DAILY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
            prefs(context).edit().remove(KEY_ALARM_AT).remove(KEY_RETRY_AT).apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences("refresh", Context.MODE_PRIVATE)
    }
}

/** The daily alarm; also re-arms it after a reboot or an app update (both drop alarms). */
class RefreshAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RefreshJobService.ACTION_CHECK) {
            RefreshJobService.logCheck(context, RefreshJobService.checkLabel(intent.getIntExtra("i", -1)))
            return
        }
        DebugLog.d("job", "alarm received (${intent.action?.substringAfterLast('.')}); " + DebugLog.deviceState(context))
        if (intent.action == RefreshJobService.ACTION_DAILY) {
            RefreshJobService.startDailyRun(context)
            // A receiver may live ~10 s: one quick look whether the job got going.
            val done = goAsync()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { RefreshJobService.logCheck(context, "alarm+8s") }
                done.finish()
            }, 8_000)
        }
        // The next alarm (Application.onCreate did it too; this one sees the daily time as passed).
        RefreshJobService.schedule(context)
    }
}
