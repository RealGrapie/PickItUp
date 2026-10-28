package com.grapie.pickitup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A GitHub release newer than the installed app. */
data class Release(val version: String, val apkUrl: String, val notes: String)

/**
 * Updates from GitHub Releases: [check] reads the latest release (at most every [CHECK_EVERY_MS]
 * by itself), [install] downloads its APK and hands it to the system installer. Plain HTTP, no
 * WebView: this must not load Chromium. The system refuses an APK signed with another key.
 */
object Updater {
    private const val TAG = "Updater"
    private const val API_URL = "https://api.github.com/repos/RealGrapie/PickItUp/releases/latest"
    private const val CHECK_EVERY_MS = 12 * 3600_000L

    enum class State { IDLE, CHECKING, UP_TO_DATE, ERROR }

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var installed = "0"

    /** A newer release, kept across restarts so the banner doesn't wait for the next check. */
    val available = MutableStateFlow<Release?>(null)
    val state = MutableStateFlow(State.IDLE)
    /** Download progress 0..1 while the APK is downloading, else null. */
    val progress = MutableStateFlow<Float?>(null)
    val failed = MutableStateFlow(false)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"
        val v = prefs.getString("version", null)
        // Gone once that version (or a later one) is installed.
        available.value = if (v != null && isNewer(v, installed))
            Release(v, prefs.getString("apkUrl", "")!!, prefs.getString("notes", "")!!) else null
        // The downloaded APK is never reused (install() downloads again): once nothing newer is
        // pending it is installed or stale. Kept while an update is pending, the installer may
        // still be reading it. The WebView cache stays: Chromium caps it (~20 MB) and reuses it.
        if (available.value == null) File(context.cacheDir, "update").deleteRecursively()
    }

    /** "v1.10" > "1.9": numeric parts, missing ones count as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** From the main screen: a check now and then, quietly. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong("checkedAt", 0) < CHECK_EVERY_MS) return
        check()
    }

    fun check() {
        if (state.value == State.CHECKING) return
        state.value = State.CHECKING
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
                .onFailure { Log.w(TAG, "check failed", it) }
            prefs.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            val rel = r.getOrNull()
            state.value = when {
                r.isFailure -> State.ERROR
                rel != null && isNewer(rel.version, installed) -> State.IDLE
                else -> State.UP_TO_DATE
            }
            if (r.isSuccess) {
                val newer = rel?.takeIf { isNewer(it.version, installed) }
                available.value = newer
                prefs.edit().apply {
                    if (newer == null) remove("version").remove("apkUrl").remove("notes")
                    else putString("version", newer.version).putString("apkUrl", newer.apkUrl).putString("notes", newer.notes)
                }.apply()
            }
        }
    }

    /** null = the latest release has no APK attached. */
    private fun fetchLatest(): Release? {
        val c = URL(API_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            return Release(
                json.getString("tag_name").removePrefix("v"),
                apk.getString("browser_download_url"),
                json.optString("body").replace("\r\n", "\n").trim(),
            )
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the APK into the cache and opens the system installer on it. */
    fun install(context: Context) {
        val rel = available.value ?: return
        if (progress.value != null) return
        val app = context.applicationContext
        failed.value = false
        progress.value = 0f
        scope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { download(app, rel.apkUrl) } }
                .onFailure { Log.w(TAG, "download failed", it) }.getOrNull()
            progress.value = null
            if (file == null || !isOurUpdate(app, file)) {
                failed.value = true
                return@launch
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            // The first time the system asks to allow installs from PickItUp, then goes on.
            app.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "pickitup.apk")
        // GitHub redirects the asset to its CDN (https -> https: followed by HttpURLConnection).
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress.value = done.toFloat() / total
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return file
    }

    /** Same package and really newer: don't send the user to the installer with a wrong file. */
    private fun isOurUpdate(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, 0) ?: return false
        val current = pm.getPackageInfo(context.packageName, 0)
        return apk.packageName == context.packageName && apk.longVersionCode > current.longVersionCode
    }
}
