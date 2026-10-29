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
        BarcodeStore.log(AppSettings.strings().logBackground)
        scope.launch {
            val r = BarcodeSync.refresh(applicationContext)
            // Delivered only if the watchapp is open right now; otherwise it asks on its next start.
            if (r is FetchResult.Ok) BarcodeSync.sendToWatch(applicationContext)
            if (daily) {
                jobFinished(params, false)
                // After a failure try again in half an hour (the next day's alarm is already set).
                if (r is FetchResult.Error) schedule(applicationContext, retryAt = System.currentTimeMillis() + RETRY_MS)
            } else {
                jobFinished(params, r is FetchResult.Error)  // periodic: let the system back off and retry
            }
        }
        return true
    }

    // Android 14+ calls this for jobs with a network constraint; nothing to do, the fetch uses any network.
    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onStopJob(params: JobParameters): Boolean {
        BarcodeStore.log(AppSettings.strings().logBackgroundStopped)
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

        /** (Re)schedules the job / alarm for the current settings; a no-op when nothing changed. */
        fun schedule(context: Context, retryAt: Long? = null) {
            // Runs from Application.onCreate: a scheduler error must never crash the app.
            runCatching { scheduleOrThrow(context, retryAt) }
                .onFailure { Log.w("RefreshJob", "schedule failed", it) }
        }

        private fun scheduleOrThrow(context: Context, retryAt: Long?) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val pending = scheduler.getPendingJob(JOB_ID)
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
                .onFailure { Log.w("RefreshJob", "daily run failed", it) }
        }

        fun isScheduled(context: Context): Boolean {
            if (context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null) return true
            return BarcodeStore.backgroundRefresh.value && BarcodeStore.refreshMode.value == RefreshMode.DAILY &&
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
        if (intent.action == RefreshJobService.ACTION_DAILY) RefreshJobService.startDailyRun(context)
        // The next alarm (Application.onCreate did it too; this one sees the daily time as passed).
        RefreshJobService.schedule(context)
    }
}
