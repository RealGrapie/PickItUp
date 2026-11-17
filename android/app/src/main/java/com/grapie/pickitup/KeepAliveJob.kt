package com.grapie.pickitup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the process alive while an Ozon page may still get a rotated login pair
 * ([OzonClient.alive] > 0). A fetch started by the watch runs in no job of its own: once the
 * watchapp closes, the Pebble app unbinds us and the cached-apps freezer may stop or kill the
 * process before the new pair reaches the disk; Ozon has replaced the old one by then, so the
 * session is gone. Same for the orders page closed right after it rotated the pair.
 */
class KeepAliveJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartJob(params: JobParameters): Boolean {
        DebugLog.d("keepalive", "job started, alive=${OzonClient.alive.value}")
        scope.launch {
            val done = withTimeoutOrNull(MAX_MS) { OzonClient.alive.first { it == 0 } }
            DebugLog.d("keepalive", if (done == null) "gave up after ${MAX_MS / 1000} s" else "pages done, job finished")
            Accounts.flushCookies()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        DebugLog.d("keepalive", "STOPPED by the system, reason ${params.stopReason}")
        Accounts.flushCookies()
        scope.coroutineContext.cancelChildren()
        return false
    }

    companion object {
        private const val JOB_ID = 1002
        /** A fetch takes up to 60 s, then the page settles 8 s more. */
        private const val MAX_MS = 3 * 60_000L

        fun start(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                // Scheduling a running job's id would stop it: one guard covers every page.
                if (scheduler.getPendingJob(JOB_ID) != null) return
                DebugLog.d("keepalive", "scheduling")
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJob::class.java))
                        .setExpedited(true)
                        .build()
                )
            }.onFailure { Log.w("KeepAliveJob", "start failed", it) }
        }
    }
}
