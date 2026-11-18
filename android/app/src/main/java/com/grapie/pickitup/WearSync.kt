package com.grapie.pickitup

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Wear OS watches (module :wear): every account's code goes into one Data Layer item, kept in
 * step with the accounts here; the watch reads it whenever it opens, also without the phone.
 * Contract (keep in sync with wear/.../Codes.kt): item [PATH_CODES] with a DataMap list
 * "accounts" (label, code, visible, updated, loggedIn); requests come as messages to
 * [PATH_REQUEST], payload [kind, index] like the Pebble watchapp's Request / Index.
 * Without a Wear OS watch (no Wear OS app on the phone) the Data Layer calls just fail quietly.
 */
object WearSync {
    const val PATH_CODES = "/pickitup/codes"
    const val PATH_REQUEST = "/pickitup/request"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    /** What was last pushed (or tried): sent again on every request from the watch. */
    private var last: List<Item>? = null

    private data class Item(val label: String, val code: String, val visible: String, val updated: Long, val loggedIn: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            Accounts.list.flatMapLatest { list ->
                if (list.isEmpty()) flowOf(emptyList())
                else combine(list.map { a ->
                    combine(a.shown, a.name, a.loggedIn, BarcodeStore.demo) { b, _, logged, demo ->
                        Item(a.label(), b?.code.orEmpty(), b?.visible.orEmpty(), b?.fetchedAt ?: 0, logged || demo)
                    }
                }) { it.toList() }
            }.distinctUntilChanged().debounce(300).collect { last = it; push(app, it) }
        }
    }

    private suspend fun push(context: Context, items: List<Item>) {
        runCatching {
            val req = PutDataMapRequest.create(PATH_CODES)
            req.dataMap.putDataMapArrayList("accounts", ArrayList(items.map {
                DataMap().apply {
                    putString("label", it.label)
                    putString("code", it.code)
                    putString("visible", it.visible)
                    putLong("updated", it.updated)
                    putBoolean("loggedIn", it.loggedIn)
                }
            }))
            Wearable.getDataClient(context).putDataItem(req.asPutDataRequest().setUrgent()).await()
            DebugLog.d("wear", "codes sent: ${items.size} account(s)")
        }.onFailure { DebugLog.d("wear", "no Data Layer: ${it.message}") }
    }

    /** The Wear OS app opened (kind 1: re-fetch what's stale) or asked to refresh account [index] (2). */
    fun onRequest(context: Context, kind: Int, index: Int) {
        val s = AppSettings.strings()
        val accounts = Accounts.list.value
        // The Data Layer may not have been there when the codes last changed (Wear OS app
        // installed later, watch paired later): every request gets the current codes again.
        // An unchanged item costs nothing, the Data Layer drops it.
        last?.let { items -> scope.launch { push(appContext ?: context.applicationContext, items) } }
        if (kind == 2) {
            val a = accounts.getOrNull(index) ?: return
            BarcodeStore.log(s.logWearAskedRefresh, a)
            BarcodeSync.refreshAndSend(context, a)
        } else {
            BarcodeStore.log(s.logWearAsked)
            accounts.filter { BarcodeSync.isStale(it.barcode.value) && (it.loggedIn.value || BarcodeStore.demo.value) }
                .forEach { BarcodeSync.refreshAndSend(context, it) }
        }
    }
}

/** Messages from the Wear OS app (started by Google Play services, also with the app closed). */
class WearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearSync.PATH_REQUEST) return
        val kind = event.data.getOrNull(0)?.toInt() ?: 1
        val index = event.data.getOrNull(1)?.toInt() ?: 0
        // Called on a binder thread: hop to the main thread like the rest of the app's state.
        android.os.Handler(mainLooper).post { WearSync.onRequest(this, kind, index) }
    }
}
