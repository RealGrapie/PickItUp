package com.grapie.pickitup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.SharedPreferences
import android.util.Log
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
import java.security.MessageDigest

/** A GitHub release newer than the installed app. */
data class Release(val version: String, val apkUrl: String, val notes: String, val apkSha256: String = "")

/**
 * Updates from GitHub Releases: [check] reads the latest release (by itself once per app launch,
 * see [checkOnLaunch]), [install] downloads its APK (kept and reused while its SHA-256 matches
 * GitHub's digest) and installs it with a PackageInstaller session. Plain HTTP, no WebView: this
 * must not load Chromium. The system refuses an APK signed with another key.
 */
object Updater {
    private const val TAG = "Updater"
    private const val API_URL = "https://api.github.com/repos/RealGrapie/PickItUp/releases/latest"

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
    /** The APK is handed to the system installer; cleared when it fails or the user cancels. */
    val installing = MutableStateFlow(false)
    /** A version hidden from the main screen with the card's close button. */
    val skipped = MutableStateFlow<String?>(null)
    /** Settings switch: check by itself on every app launch and show the card on the main screen. */
    val autoCheck = MutableStateFlow(true)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"
        skipped.value = prefs.getString("skipped", null)
        autoCheck.value = prefs.getBoolean("autoCheck", true)
        val v = prefs.getString("version", null)
        // Gone once that version (or a later one) is installed.
        available.value = if (v != null && isNewer(v, installed))
            Release(v, prefs.getString("apkUrl", "")!!, prefs.getString("notes", "")!!, prefs.getString("apkSha", "")!!) else null
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

    /** From the main screen when it opens anew (not on every return to it): one small GitHub request. */
    fun checkOnLaunch() {
        if (autoCheck.value) check()
    }

    fun skip(version: String) {
        prefs.edit().putString("skipped", version).apply()
        skipped.value = version
    }

    fun setAutoCheck(on: Boolean) {
        prefs.edit().putBoolean("autoCheck", on).apply()
        autoCheck.value = on
        if (on) check()
    }

    /** [manual]: "Check for updates" in settings brings a skipped version back. */
    fun check(manual: Boolean = false) {
        if (manual && skipped.value != null) {
            prefs.edit().remove("skipped").apply()
            skipped.value = null
        }
        if (state.value == State.CHECKING) return
        state.value = State.CHECKING
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
                .onFailure { Log.w(TAG, "check failed", it) }
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
                    if (newer == null) remove("version").remove("apkUrl").remove("notes").remove("apkSha")
                    else putString("version", newer.version).putString("apkUrl", newer.apkUrl).putString("notes", newer.notes)
                        .putString("apkSha", newer.apkSha256)
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
                // "sha256:<hex>" since GitHub publishes asset digests; older assets have none.
                apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:").orEmpty(),
            )
        } finally {
            c.disconnect()
        }
    }

    /**
     * Gets the APK (the one already downloaded, if its checksum still matches) and installs it.
     * A missed or cancelled confirmation is not fatal: "Update" again reuses the file.
     */
    fun install(context: Context) {
        val rel = available.value ?: return
        if (progress.value != null) return  // a second tap while installing starts a fresh session
        val app = context.applicationContext
        failed.value = false
        progress.value = 0f
        scope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { apkFor(app, rel) } }
                .onFailure { Log.w(TAG, "download failed", it) }.getOrNull()
            progress.value = null
            if (file == null || !isOurUpdate(app, file)) {
                failed.value = true
                return@launch
            }
            installing.value = true
            runCatching { withContext(Dispatchers.IO) { commitSession(app, file) } }.onFailure {
                Log.w(TAG, "install session failed", it)
                installing.value = false
                failed.value = true
            }
        }
    }

    /** From [InstallReceiver]: the session ended without replacing the app. */
    fun onInstallEnded(status: Int, message: String?) {
        installing.value = false
        // The user closing the confirmation is not an error: the card stays, the file too.
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Log.w(TAG, "install failed: $status $message")
            failed.value = true
        }
    }

    /**
     * Our own installer session instead of ACTION_VIEW: no FileProvider, and on Android 12+ no
     * confirmation at all once PickItUp! installed the current version itself (it is then the
     * installer of record). Otherwise the system asks, via [InstallReceiver].
     */
    private fun commitSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("pickitup.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                // Mutable: the system adds the status extras.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    /** The release's APK in cache/update/: reused while it matches GitHub's SHA-256, else downloaded. */
    private fun apkFor(context: Context, rel: Release): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "pickitup-${rel.version}.apk")
        if (rel.apkSha256.isNotEmpty() && file.exists() && sha256(file) == rel.apkSha256) return file
        dir.listFiles()?.forEach { it.delete() }  // an older version's APK or a broken download
        val part = File(dir, file.name + ".part")
        download(rel.apkUrl, part)
        if (rel.apkSha256.isNotEmpty() && sha256(part) != rel.apkSha256) {
            part.delete()
            error("checksum mismatch")
        }
        if (!part.renameTo(file)) error("rename failed")
        return file
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, file: File) {
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
    }

    /** Same package and really newer: don't send the user to the installer with a wrong file. */
    private fun isOurUpdate(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, 0) ?: return false
        val current = pm.getPackageInfo(context.packageName, 0)
        return apk.packageName == context.packageName && apk.longVersionCode > current.longVersionCode
    }
}
