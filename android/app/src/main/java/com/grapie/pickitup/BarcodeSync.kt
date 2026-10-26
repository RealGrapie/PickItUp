package com.grapie.pickitup

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** AppMessage keys — must match watch/package.json messageKeys. */
object WatchKeys {
    val APP_UUID: UUID = UUID.fromString("553a866c-3355-471c-8239-a8dd9c2925a1")
    const val CODE = 10000u
    const val VISIBLE = 10001u
    const val UPDATED = 10002u
    const val REQUEST = 10003u
    const val STATUS = 10004u
    const val TIMEOUT = 10005u

    const val REQUEST_CACHED = 1
    const val REQUEST_REFRESH = 2
}

/** Fetches the barcode from Ozon and pushes it to the watchapp. */
object BarcodeSync {
    private const val TAG = "BarcodeSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fetchLock = Mutex()

    fun isStale(b: Barcode?) = b == null || RefreshSchedule.isStale(b.fetchedAt)

    /** Called from the UI. */
    fun refreshAndSend(context: Context) = scope.launch {
        refresh(context)
        sendToWatch(context)
    }

    /** Delivered only if the watchapp is open; otherwise it gets everything on its next request. */
    fun sendToWatchAsync(context: Context) = scope.launch {
        sendToWatch(context)
    }

    /** Called when the watchapp asks: answer from cache at once, re-fetch if needed. */
    fun onWatchRequest(context: Context, kind: Int) = scope.launch {
        val cached = BarcodeStore.shown.value
        val needFetch = kind == WatchKeys.REQUEST_REFRESH || isStale(cached)
        // Always answer at once (even without a code): the watch treats ~5 s of silence as
        // "companion not installed" and shows the download QR.
        sendToWatch(context, if (needFetch) "Updating..." else "")
        if (!needFetch) return@launch
        val status = when (val r = refresh(context)) {
            is FetchResult.Ok -> ""
            FetchResult.LoginNeeded -> "Log in on phone"
            is FetchResult.Error -> "Update failed"
        }
        sendToWatch(context, status)
    }

    /** Starts the watchapp (e.g. another app is open); once open it asks for the code itself. */
    fun openWatchapp(context: Context) = scope.launch {
        BarcodeStore.log(AppSettings.strings().logOpenWatchapp)
        val sender = DefaultPebbleSender(context.applicationContext)
        try {
            sender.startAppOnTheWatch(WatchKeys.APP_UUID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "start app failed", e)
        } finally {
            closeQuietly(sender)
        }
    }

    /**
     * PebbleKit's close() unbinds unconditionally and throws "Service not registered" when the
     * bind never happened (no Pebble app installed) - that crashed the whole app.
     */
    private fun closeQuietly(sender: DefaultPebbleSender) {
        runCatching { sender.close() }.onFailure { Log.w(TAG, "close failed", it) }
    }

    suspend fun refresh(context: Context): FetchResult = fetchLock.withLock {
        if (BarcodeStore.demo.value) {
            // Demo mode never talks to Ozon: spin for a moment and "receive" the sample code again.
            BarcodeStore.busy.value = true
            try {
                kotlinx.coroutines.delay(900)
                BarcodeStore.touchDemo()
            } finally {
                BarcodeStore.busy.value = false
            }
            return@withLock FetchResult.Ok(BarcodeStore.shown.value!!)
        }
        if (!BarcodeStore.loggedIn.value) {
            // Not logged in: don't bother Ozon (it answers 500 for guests anyway).
            BarcodeStore.loginNeeded.value = true
            return@withLock FetchResult.LoginNeeded
        }
        BarcodeStore.busy.value = true
        val s = AppSettings.strings()
        BarcodeStore.log(s.logFetching)
        val r = try {
            OzonClient.fetch(context)
        } finally {
            BarcodeStore.busy.value = false
        }
        // Chromium is loaded by now anyway; Ozon may have ended the session meanwhile.
        BarcodeStore.setLoggedIn(r is FetchResult.Ok || OzonClient.isLoggedIn())
        when (r) {
            is FetchResult.Ok -> {
                BarcodeStore.save(r.barcode)
                BarcodeStore.loginNeeded.value = false
                // Only the tail: the journal is on the debug screen, which ends up in screenshots.
                BarcodeStore.log(s.logFetched("…" + r.barcode.code.takeLast(4)))
            }
            FetchResult.LoginNeeded -> {
                BarcodeStore.loginNeeded.value = true
                BarcodeStore.log(s.logLoginNeeded)
            }
            is FetchResult.Error -> BarcodeStore.log(s.logError(r.message))
        }
        r
    }

    suspend fun sendToWatch(context: Context, status: String = "") {
        val b = BarcodeStore.shown.value
        val dict = buildMap {
            if (b != null) {
                put(WatchKeys.CODE, PebbleDictionaryItem.Text(b.code))
                put(WatchKeys.VISIBLE, PebbleDictionaryItem.Text(b.visible))
                put(WatchKeys.UPDATED, PebbleDictionaryItem.Int32((b.fetchedAt / 1000).toInt()))
            } else if (!BarcodeStore.loggedIn.value) {
                // Logged out: an empty code makes the watch forget the one it stored.
                put(WatchKeys.CODE, PebbleDictionaryItem.Text(""))
            }
            put(WatchKeys.STATUS, PebbleDictionaryItem.Text(status))
            put(WatchKeys.TIMEOUT, PebbleDictionaryItem.Int32(BarcodeStore.exitTimeout.value))
        }
        val sender = DefaultPebbleSender(context.applicationContext)
        val res = try {
            sender.sendDataToPebble(WatchKeys.APP_UUID, dict)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e); null
        } finally {
            closeQuietly(sender)
        }
        val r = res?.values?.firstOrNull()
        BarcodeStore.watchStatus.value = when {
            res == null -> WatchStatus.NO_PEBBLE_APP
            res.isEmpty() -> WatchStatus.NOT_CONNECTED
            r == TransmissionResult.Success -> WatchStatus.RECEIVED
            r == TransmissionResult.FailedDifferentAppOpen -> WatchStatus.OTHER_APP_OPEN
            r == TransmissionResult.FailedNoPermissions -> WatchStatus.NO_PERMISSION
            r == TransmissionResult.FailedWatchNotConnected -> WatchStatus.NOT_CONNECTED
            else -> WatchStatus.ERROR
        }
    }
}
