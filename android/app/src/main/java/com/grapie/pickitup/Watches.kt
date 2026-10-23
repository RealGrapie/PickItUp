package com.grapie.pickitup

import android.content.Context
import android.content.SharedPreferences
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** A watch as reported by the Pebble app: user-editable name plus hardware platform/revision. */
data class WatchInfo(val name: String, val platform: String, val revision: String) {
    /** Marketing name from the platform codename ("flint" -> "Pebble 2 Duo"), else the watch's own name. */
    val model: String get() = modelName(revision) ?: modelName(platform) ?: name.ifBlank { "Pebble" }

    companion object {
        // Board revisions first (they tell e.g. Pebble 2 Duo from Pebble 2), then SDK platforms.
        private val MODELS = mapOf(
            "asterix" to "Pebble 2 Duo", "obelix" to "Pebble Time 2", "getafix" to "Pebble Round 2",
            "silk" to "Pebble 2", "robert" to "Pebble Time 2", "spalding" to "Pebble Time Round",
            "snowy" to "Pebble Time", "tintin" to "Pebble", "bianca" to "Pebble Steel",
            "aplite" to "Pebble", "basalt" to "Pebble Time", "chalk" to "Pebble Time Round",
            "diorite" to "Pebble 2", "emery" to "Pebble Time 2", "flint" to "Pebble 2 Duo",
            "gabbro" to "Pebble Round 2",
        )

        fun modelName(code: String?): String? {
            val key = code?.lowercase()?.trim().orEmpty()
            return MODELS[key] ?: MODELS.entries.firstOrNull { key.startsWith(it.key) }?.value
        }
    }
}

/** Watches connected to the Pebble app right now, and the last one seen (kept while offline). */
object Watches {
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var users = 0  // screens showing watches; the next one starts before the last one stops

    val connected = MutableStateFlow<List<WatchInfo>>(emptyList())
    val last = MutableStateFlow<WatchInfo?>(null)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("watches", Context.MODE_PRIVATE)
        last.value = prefs.getString("platform", null)?.let {
            WatchInfo(prefs.getString("name", "")!!, it, prefs.getString("revision", "")!!)
        }
    }

    /** Follows the Pebble app's provider while a screen is visible; pair every call with [stop]. */
    fun watch(context: Context) {
        users++
        if (job?.isActive == true) return
        job = scope.launch {
            DefaultPebbleInfoRetriever(context.applicationContext).getConnectedWatches()
                .flowOn(Dispatchers.IO)
                .catch { emit(emptyList()) }
                .collect { list ->
                    val infos = list.map { WatchInfo(it.name, it.platform, it.revision) }
                    connected.value = infos
                    infos.firstOrNull()?.let { remember(it) }
                }
        }
    }

    fun stop() {
        if (--users > 0) return
        users = 0
        job?.cancel()
        job = null
    }

    private fun remember(w: WatchInfo) {
        if (w == last.value) return
        last.value = w
        prefs.edit().putString("name", w.name).putString("platform", w.platform).putString("revision", w.revision).apply()
    }
}
