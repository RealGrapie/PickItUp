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
    /** Several accounts: which one a message is about (0 = first), how many there are, its header line. */
    const val INDEX = 10006u
    const val COUNT = 10007u
    const val NAME = 10008u
    /** How to draw the code: 0 = Code 128 (Ozon), 1 = QR (Wildberries). */
    const val FORMAT = 10009u
    /** The days after today's code: "<from epoch s>|<code>|<text>" lines, for the midnight switch. */
    const val DAYS = 10010u

    const val REQUEST_CACHED = 1
    const val REQUEST_REFRESH = 2
}

/** Fetches the barcode from Ozon and pushes it to the watchapp. */
object BarcodeSync {
    private const val TAG = "BarcodeSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fetchLock = Mutex()
    /** A watch REFRESH (UP) within this of the last fetch is answered from the cache: no new rotation. */
    private const val FRESH_MS = 5 * 60_000L
    /** Watch footer while "I have a Pebble" is off (ASCII, fits a 144 px line). */
    private const val STATUS_WATCH_OFF = "Off in phone app"

    fun isStale(b: Barcode?) = b == null || RefreshSchedule.isStale(b.fetchedAt)

    /** Called from the UI. */
    fun refreshAndSend(context: Context, account: Account = Accounts.active) = scope.launch {
        refresh(context, account)
        sendToWatch(context)
    }

    /**
     * Every logged-in account, one after another (background runs). The worst outcome counts:
     * an error or a VPN anywhere means "try again later"; Ok if at least one code came.
     */
    suspend fun refreshAll(context: Context): List<FetchResult> =
        Accounts.list.value.filter { it.loggedIn.value || BarcodeStore.demo.value }.map { refresh(context, it) }

    /** Debug screen: every account, then one send. */
    fun refreshAllAndSend(context: Context) = scope.launch {
        refreshAll(context)
        sendToWatch(context)
    }

    /** Delivered only if the watchapp is open; otherwise it gets everything on its next request. */
    fun sendToWatchAsync(context: Context) = scope.launch {
        sendToWatch(context)
    }

    /**
     * Called when the watchapp asks: answer from cache at once, re-fetch what's needed.
     * CACHED = every stale account; REFRESH (UP) = the account shown on the watch ([index]; old
     * watchapps send none and show the first one).
     */
    fun onWatchRequest(context: Context, kind: Int, index: Int? = null) = scope.launch {
        if (!BarcodeStore.watchEnabled.value) {
            // "I have a Pebble" is off: no code and no Ozon request, just a reason. Answering at all
            // keeps the watch from showing the download QR; a code stored on it stays as it is.
            DebugLog.d("watch", "request kind=$kind ignored: watch features off")
            sendToWatch(context, STATUS_WATCH_OFF, force = true, withCode = false)
            return@launch
        }
        val accounts = Accounts.list.value
        val target = accounts.getOrNull(index ?: 0) ?: accounts.first()
        // With a site open in the app Ozon codes come from the cache: that page owns the Ozon session
        // (two pages rotating one login pair lose it). Wildberries is a plain request, never blocked.
        val toFetch = accounts.filter { a ->
            val cached = a.shown.value
            val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < FRESH_MS
            !(LoginActivity.isOpen && a.provider == Provider.OZON) &&
                (a.loggedIn.value || BarcodeStore.demo.value) &&
                (kind == WatchKeys.REQUEST_REFRESH && a == target && !fresh || isStale(cached))
        }
        DebugLog.d("watch", "request kind=$kind index=$index loginOpen=${LoginActivity.isOpen} -> fetch ${toFetch.map { accounts.indexOf(it) }}")
        // Always answer at once (even without a code): the watch treats ~5 s of silence as
        // "companion not installed" and shows the download QR.
        // force: the watchapp asking proves there is a Pebble, whatever the setting says.
        val pending = toFetch.toMutableSet()
        val results = mutableMapOf<Account, String>()
        sendToWatch(context, { if (it in pending) "Updating..." else "" }, force = true)
        for (a in toFetch) {
            results[a] = when (refresh(context, a)) {
                is FetchResult.Ok -> ""
                FetchResult.LoginNeeded -> "Log in on phone"
                FetchResult.Vpn -> "VPN on phone"
                is FetchResult.Error -> "Update failed"
            }
            pending -= a
            sendToWatch(context, { results[it] ?: if (it in pending) "Updating..." else "" }, force = true, only = a)
        }
    }

    /** Starts the watchapp (e.g. another app is open); once open it asks for the code itself. */
    fun openWatchapp(context: Context) = scope.launch {
        if (!BarcodeStore.watchEnabled.value) return@launch
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

    /** One request for [account]'s code; one at a time app-wide (each rotates a login pair). */
    suspend fun refresh(context: Context, account: Account = Accounts.active): FetchResult = fetchLock.withLock {
        if (BarcodeStore.demo.value) {
            // Demo mode never talks to Ozon: spin for a moment and "receive" the sample code again.
            account.busy.value = true
            try {
                kotlinx.coroutines.delay(900)
                BarcodeStore.touchDemo()
            } finally {
                account.busy.value = false
            }
            return@withLock FetchResult.Ok(account.shown.value!!)
        }
        if (!account.loggedIn.value) {
            DebugLog.d("sync", "refresh skipped: account $account logged out")
            // Not logged in: don't bother Ozon (it answers 500 for guests anyway).
            account.loginNeeded.value = true
            return@withLock FetchResult.LoginNeeded
        }
        if (LoginActivity.isOpen && account.provider == Provider.OZON) {
            // Two pages rotating one login pair at once: the cookie jar may keep the older new pair.
            BarcodeStore.log(AppSettings.strings().logLoginOpen, account)
            return@withLock FetchResult.Error(AppSettings.strings().logLoginOpen)
        }
        if (Net.vpnActive(context)) {
            // Ozon sees the VPN's address: a login from abroad may get ended. Split tunneling is fine.
            BarcodeStore.log(account.provider.text(AppSettings.strings().logVpn), account)
            return@withLock FetchResult.Vpn
        }
        account.busy.value = true
        val s = AppSettings.strings()
        BarcodeStore.log(account.provider.text(s.logFetching), account)
        val r = try {
            account.provider.client.fetch(context, account)
        } finally {
            account.busy.value = false
        }
        // Chromium is loaded by now anyway; Ozon may have ended the session meanwhile.
        account.setLoggedIn(r is FetchResult.Ok || account.provider.client.isLoggedIn(account))
        // The probe follows the first account's Ozon session only.
        if (account == Accounts.active && account.provider == Provider.OZON) SessionProbe.onFetch(context, r)
        when (r) {
            is FetchResult.Ok -> {
                if (r.days.isNotEmpty()) account.saveDays(r.days) else account.save(r.barcode)
                account.loginNeeded.value = false
                // Only the tail: the journal is on the debug screen, which ends up in screenshots.
                BarcodeStore.log(account.provider.text(s.logFetched("…" + r.barcode.code.takeLast(4))), account)
            }
            FetchResult.LoginNeeded -> {
                account.loginNeeded.value = true
                BarcodeStore.log(account.provider.text(s.logLoginNeeded), account)
            }
            is FetchResult.Error -> BarcodeStore.log(s.logError(r.message), account)
            FetchResult.Vpn -> Unit  // never comes from OzonClient
        }
        r
    }

    /** Every account's code to the watch, or a status alone (one for all). */
    suspend fun sendToWatch(context: Context, status: String = "", force: Boolean = false, withCode: Boolean = true) =
        if (withCode) sendToWatch(context, { status }, force) else sendStatusOnly(context, status, force)

    /**
     * One message per account (or just [only]): index, count, header line, code, status. The first
     * account goes last, so a watchapp from before multi-account (which overwrites its one code with
     * every message) ends up with it.
     */
    suspend fun sendToWatch(context: Context, status: (Account) -> String, force: Boolean = false, only: Account? = null) {
        // No Pebble (setting off): don't even bind to the Pebble app.
        if (!force && !BarcodeStore.watchEnabled.value) return
        val accounts = Accounts.list.value
        // A day code whose midnight has passed: today's goes out, not yesterday's.
        accounts.forEach { it.updateShown() }
        val sender = DefaultPebbleSender(context.applicationContext)
        var res: Map<*, TransmissionResult>? = null
        try {
            for (i in accounts.indices.reversed()) {
                val account = accounts[i]
                if (only != null && account != only) continue
                val b = account.shown.value
                val st = status(account).ifEmpty { if (!account.loggedIn.value && !BarcodeStore.demo.value) "Log in on phone" else "" }
                val dict = buildMap {
                    put(WatchKeys.INDEX, PebbleDictionaryItem.Int32(i))
                    put(WatchKeys.COUNT, PebbleDictionaryItem.Int32(accounts.size))
                    put(WatchKeys.NAME, PebbleDictionaryItem.Text(WatchNames.label(account, accounts)))
                    if (b != null) {
                        put(WatchKeys.CODE, PebbleDictionaryItem.Text(b.code))
                        put(WatchKeys.VISIBLE, PebbleDictionaryItem.Text(b.visible))
                        put(WatchKeys.UPDATED, PebbleDictionaryItem.Int32((b.fetchedAt / 1000).toInt()))
                        put(WatchKeys.FORMAT, PebbleDictionaryItem.Int32(b.format.watchValue))
                        // Only for day codes: a watchapp before 1.6 has a 256-byte inbox and would drop
                        // the whole message (Ozon messages stay as small as they were).
                        val next = if (BarcodeStore.demo.value) emptyList() else account.upcoming()
                        if (b.format == CodeFormat.QR) put(WatchKeys.DAYS, PebbleDictionaryItem.Text(WatchNames.days(next)))
                    } else if (!account.loggedIn.value) {
                        // Logged out: an empty code makes the watch forget the one it stored.
                        put(WatchKeys.CODE, PebbleDictionaryItem.Text(""))
                    }
                    put(WatchKeys.STATUS, PebbleDictionaryItem.Text(st))
                    put(WatchKeys.TIMEOUT, PebbleDictionaryItem.Int32(BarcodeStore.exitTimeout.value))
                }
                res = sender.sendDataToPebble(WatchKeys.APP_UUID, dict)
                if (res?.values?.firstOrNull() == TransmissionResult.Success) {
                    account.markOnWatch(b)
                    BarcodeStore.setDeliveredAt(System.currentTimeMillis())
                } else if (res == null && BarcodeStore.demo.value) {
                    // Demo without the Pebble app (emulator screenshots): as if every code went.
                    accounts.forEach { it.markOnWatch(it.shown.value) }
                    BarcodeStore.setDeliveredAt(System.currentTimeMillis())
                }
                // Not delivered (watch gone, other app open): the rest won't go either.
                if (res?.values?.firstOrNull() != TransmissionResult.Success) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e); res = null
        } finally {
            closeQuietly(sender)
        }
        noteResult(res)
    }

    /** Status only, for every account: the watch keeps whatever codes it has stored. */
    private suspend fun sendStatusOnly(context: Context, status: String, force: Boolean) {
        if (!force && !BarcodeStore.watchEnabled.value) return
        val dict = mapOf(
            WatchKeys.STATUS to PebbleDictionaryItem.Text(status),
            WatchKeys.TIMEOUT to PebbleDictionaryItem.Int32(BarcodeStore.exitTimeout.value),
        )
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
        noteResult(res)
    }

    private fun noteResult(res: Map<*, TransmissionResult>?) {
        val r = res?.values?.firstOrNull()
        BarcodeStore.watchStatus.value = when {
            // Demo mode on a phone without the Pebble app (the emulator): screenshots show a delivery.
            res == null && BarcodeStore.demo.value -> WatchStatus.RECEIVED
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
