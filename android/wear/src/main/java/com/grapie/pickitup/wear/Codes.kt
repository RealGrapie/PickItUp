package com.grapie.pickitup.wear

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/** One account's code as the phone sent it. */
data class WearAccount(
    val label: String,
    val code: String,
    val visible: String,
    val updated: Long,
    val loggedIn: Boolean,
)

/**
 * Data Layer contract with the phone app (WearSync.kt there; keep both in sync):
 * the phone keeps one data item at [PATH_CODES] with a DataMap list "accounts" (label, code,
 * visible, updated, loggedIn); the watch asks with a message to [PATH_REQUEST], payload
 * [kind, index] (kind 1 = refresh what's stale, 2 = refresh account [index]).
 */
object Codes {
    const val PATH_CODES = "/pickitup/codes"
    const val PATH_REQUEST = "/pickitup/request"
    const val REQUEST_CACHED: Byte = 1
    const val REQUEST_REFRESH: Byte = 2
    private const val TAG = "Codes"

    /** null = the phone never sent anything. Kept in prefs, so the codes show without the phone. */
    val accounts = MutableStateFlow<List<WearAccount>?>(null)
    /** A phone with the Data Layer is reachable right now. */
    val phoneConnected = MutableStateFlow(true)

    /** QR instead of Code 128 (tap the code to switch); remembered. */
    val qr = MutableStateFlow(false)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("codes", Context.MODE_PRIVATE)
        accounts.value = prefs.getString("accounts", null)?.let { runCatching { fromJson(it) }.getOrNull() }
        qr.value = prefs.getBoolean("qr", false)
    }

    fun toggleQr() {
        qr.value = !qr.value
        prefs.edit().putBoolean("qr", qr.value).apply()
    }

    fun fromPhone(map: DataMap) {
        val list = map.getDataMapArrayList("accounts").orEmpty().map {
            WearAccount(
                it.getString("label", ""), it.getString("code", ""), it.getString("visible", ""),
                it.getLong("updated", 0), it.getBoolean("loggedIn", false),
            )
        }
        accounts.value = list
        prefs.edit().putString("accounts", toJson(list)).apply()
    }

    /** Reads the phone's data item (it may have changed while the app was closed). */
    suspend fun load(context: Context) {
        runCatching {
            val items = Wearable.getDataClient(context).dataItems.await()
            try {
                items.firstOrNull { it.uri.path == PATH_CODES }?.let {
                    fromPhone(com.google.android.gms.wearable.DataMapItem.fromDataItem(it).dataMap)
                }
            } finally {
                items.release()
            }
        }.onFailure { Log.w(TAG, "load failed", it) }
    }

    /** Asks the phone app; false if no phone is connected. */
    suspend fun request(context: Context, kind: Byte, index: Int): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        phoneConnected.value = nodes.isNotEmpty()
        val client = Wearable.getMessageClient(context)
        nodes.forEach { client.sendMessage(it.id, PATH_REQUEST, byteArrayOf(kind, index.toByte())).await() }
        nodes.isNotEmpty()
    }.getOrElse { Log.w(TAG, "request failed", it); phoneConnected.value = false; false }

    private fun toJson(list: List<WearAccount>) = JSONArray(list.map {
        JSONObject().put("label", it.label).put("code", it.code).put("visible", it.visible)
            .put("updated", it.updated).put("loggedIn", it.loggedIn)
    }).toString()

    private fun fromJson(s: String): List<WearAccount> {
        val a = JSONArray(s)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            WearAccount(o.getString("label"), o.getString("code"), o.getString("visible"), o.getLong("updated"), o.getBoolean("loggedIn"))
        }
    }

    /** Sample codes for the demo (emulator, screenshots): never a real one. */
    fun demo(now: Long) = listOf(
        WearAccount("Ozon · Mama", "12345678*9012", "1234 5678 * 9012", now, true),
        WearAccount("Ozon · Papa", "87654321*2109", "8765 4321 * 2109", now - 26 * 3_600_000L, true),
    )
}
