package com.grapie.pickitup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
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
 * DAILY mode: a one-shot job at the next [BarcodeStore.dailyMinutes], re-armed after each run.
 * JobScheduler picks the exact moment (network required, survives reboots).
 */
class RefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartJob(params: JobParameters): Boolean {
        val daily = !params.extras.isEmpty && params.extras.containsKey(EXTRA_TARGET)
        if (!BarcodeStore.loggedIn.value) {  // logged out: nothing to do now, keep the daily chain going
            if (daily) schedule(applicationContext)
            return false
        }
        BarcodeStore.log(AppSettings.strings().logBackground)
        scope.launch {
            val r = BarcodeSync.refresh(applicationContext)
            // Delivered only if the watchapp is open right now; otherwise it asks on its next start.
            if (r is FetchResult.Ok) BarcodeSync.sendToWatch(applicationContext)
            if (daily) {
                jobFinished(params, false)
                // Next day; after a failure try again in half an hour.
                schedule(applicationContext, retryAt = if (r is FetchResult.Error) System.currentTimeMillis() + RETRY_MS else null)
            } else {
                jobFinished(params, r is FetchResult.Error)  // periodic: let the system back off and retry
            }
        }
        return true
    }

    // Android 14+ calls this for jobs with a network constraint; nothing to do, the fetch uses any network.
    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    companion object {
        private const val JOB_ID = 1001
        private const val EXTRA_TARGET = "target"
        private const val RETRY_MS = 30 * 60_000L
        private const val DAILY_WINDOW_MS = 30 * 60_000L

        /** (Re)schedules the job for the current settings; a no-op when nothing changed. */
        fun schedule(context: Context, retryAt: Long? = null) {
            // Runs from Application.onCreate: a scheduler error must never crash the app.
            runCatching { scheduleOrThrow(context, retryAt) }
                .onFailure { Log.w("RefreshJob", "schedule failed", it) }
        }

        private fun scheduleOrThrow(context: Context, retryAt: Long?) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!BarcodeStore.backgroundRefresh.value) {
                scheduler.cancel(JOB_ID)
                return
            }
            val pending = scheduler.getPendingJob(JOB_ID)
            val builder = JobInfo.Builder(JOB_ID, ComponentName(context, RefreshJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
            when (BarcodeStore.refreshMode.value) {
                RefreshMode.INTERVAL -> {
                    val periodMs = BarcodeStore.refreshHours.value * 3_600_000L
                    // schedule() restarts the period, so leave an identical job alone.
                    if (pending != null && pending.isPeriodic && pending.intervalMillis == periodMs) return
                    builder.setPeriodic(periodMs)
                }
                RefreshMode.DAILY -> {
                    val now = System.currentTimeMillis()
                    val target = retryAt ?: RefreshSchedule.nextDaily(now, BarcodeStore.dailyMinutes.value)
                    if (pending != null && !pending.isPeriodic && pending.extras.getLong(EXTRA_TARGET) == target) return
                    val delay = (target - now).coerceAtLeast(0)
                    builder.setMinimumLatency(delay)
                        .setOverrideDeadline(delay + DAILY_WINDOW_MS)
                        .setExtras(PersistableBundle().apply { putLong(EXTRA_TARGET, target) })
                }
            }
            scheduler.schedule(builder.build())
        }

        fun isScheduled(context: Context) =
            context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null
    }
}
