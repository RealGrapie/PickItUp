package com.grapie.pickitup

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed log for diagnosing the Ozon session, shared from the debug screen as a file.
 * Never a secret: cookie values become 4-hex SHA-256 prints, URLs lose their query, long digit
 * runs (codes, ids, order numbers) are masked, and no barcode is ever written.
 */
object DebugLog {
    private const val MAX_BYTES = 768 * 1024
    private const val KEEP_BYTES = 384 * 1024
    private lateinit var file: File
    /** Sniff builds: a copy in the app's external dir, readable over adb without a debuggable app. */
    private var mirror: File? = null
    private val time = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.US)
    private val digits = Regex("\\d{6,}")

    fun init(context: Context) {
        file = File(context.filesDir, "debug.log")
        if (context.resources.getBoolean(R.bool.session_probe)) mirror = context.getExternalFilesDir(null)?.let { File(it, "debug.log") }
    }

    fun mask(text: String) = digits.replace(text) { "#".repeat(it.value.length.coerceAtMost(4)) + "…" }

    /** A moment as "dd.MM HH:mm" (e.g. when a token expires); "-" for 0. */
    fun at(ms: Long): String = if (ms <= 0) "-" else SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(ms))

    /** Path only: queries may carry ids and tokens. */
    fun url(u: String?): String = u?.substringBefore('?')?.substringBefore('#')?.let(::mask) ?: "-"

    @Synchronized
    fun d(tag: String, msg: String) {
        if (!::file.isInitialized) return
        runCatching {
            if (file.length() > MAX_BYTES) {
                val tail = file.readBytes().let { it.copyOfRange(it.size - KEEP_BYTES, it.size) }
                val cut = tail.indexOf('\n'.code.toByte()) + 1
                file.writeBytes(tail.copyOfRange(cut, tail.size))
            }
            val line = "${time.format(Date())} [$tag] ${mask(msg)}\n"
            file.appendText(line)
            mirror?.let { m -> runCatching { if (m.length() > 4 * MAX_BYTES) m.delete(); m.appendText(line) } }
        }.onFailure { Log.w("DebugLog", "write failed", it) }
    }

    /** name -> print of every ozon.ru cookie; values never leave this map unhashed. */
    fun cookies(account: Account = Accounts.active): Map<String, String> =
        WebProfiles.cookies(account.profile).getCookie("https://www.ozon.ru")
            ?.split(';')?.map { it.trim() }?.filter { '=' in it }
            ?.associate { c -> c.substringBefore('=') to describe(c.substringBefore('='), c.substringAfter('=')) }
            .orEmpty()

    private fun describe(name: String, value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(2).joinToString("") { "%02x".format(it) }
        // user-id: a print too, so two accounts (WebView profiles) can be told apart.
        if (!name.contains("token", true)) return if (name == "__Secure-user-id") (if (value == "0") "0" else "set:$hash") else hash
        // "12.<account>.<session>.<..>.<payload>.<date>.<issued>...": account 0 = guest, issue time is no secret.
        val f = value.split('.')
        return hash + (if (f.getOrNull(1) == "0") " guest" else "") + (f.getOrNull(6)?.let { " @" + issued(it) } ?: "")
    }

    /** "20261108210314" -> "08.11 21:03:14" (the digit-run mask would eat the raw form). */
    private fun issued(t: String) =
        if (t.length == 14 && t.all { it.isDigit() }) "${t.substring(6, 8)}.${t.substring(4, 6)} ${t.substring(8, 10)}:${t.substring(10, 12)}:${t.substring(12, 14)}" else "?"

    /** Logs which cookies appeared, changed or went away since [before]. */
    fun cookieDiff(tag: String, what: String, before: Map<String, String>, account: Account = Accounts.active): Map<String, String> {
        val now = cookies(account)
        val changes = (before.keys + now.keys).sorted().mapNotNull { k ->
            val a = before[k]; val b = now[k]
            when {
                a == b -> null
                a == null -> "+$k=$b"
                b == null -> "-$k"
                else -> "$k: $a -> $b"
            }
        }
        d(tag, "$what: " + if (changes.isEmpty()) "no cookie changes" else changes.joinToString("; "))
        return now
    }

    fun tokens(tag: String, what: String, account: Account = Accounts.active) {
        d(tag, "$what: " + cookies(account).filterKeys { it.contains("token", true) || it == "__Secure-user-id" }
            .entries.joinToString(" ") { "${it.key.removePrefix("__Secure-")}=${it.value}" }.ifEmpty { "no login cookies" })
    }

    /**
     * Why might the system hold a background job back? Network as this app sees it (BLOCKED =
     * Doze / data saver cut this app off), Doze, battery saver, our battery exemption, standby
     * bucket, background restriction, screen and charger. No ids or addresses.
     */
    fun deviceState(context: Context): String = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        @Suppress("DEPRECATION") val info = cm.activeNetworkInfo
        val transports = caps?.let { c ->
            listOfNotNull(
                "wifi".takeIf { c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) },
                "cell".takeIf { c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) },
                "vpn".takeIf { c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) },
                "eth".takeIf { c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) },
            ).joinToString("+").ifEmpty { "other" } +
                (if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " validated" else " NOT validated")
        } ?: "none"
        @Suppress("DEPRECATION") val detailed = info?.detailedState?.name ?: "no info"
        val bucket = when (val b = context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket) {
            10 -> "active"; 20 -> "working_set"; 30 -> "frequent"; 40 -> "rare"; 45 -> "RESTRICTED"; 5 -> "exempted"; else -> "$b"
        }
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val restricted = context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        val dataSaver = cm.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        "net=$transports ($detailed)" +
            " doze=" + (if (pm.isDeviceIdleMode) "DEEP" else if (pm.isDeviceLightIdleMode) "light" else "off") +
            " saver=" + (if (pm.isPowerSaveMode) "ON" else "off") +
            " exempt=" + (if (pm.isIgnoringBatteryOptimizations(context.packageName)) "yes" else "NO") +
            " bucket=$bucket" + (if (restricted) " BG-RESTRICTED" else "") + (if (dataSaver) " DATA-SAVER" else "") +
            " screen=" + (if (pm.isInteractive) "on" else "off") +
            " battery=$level%" + (if (plugged) " charging" else "")
    }.getOrElse { "state unavailable: ${it.javaClass.simpleName}" }

    /** How the previous process ended: swiped from Recents, killed for memory, crash, update... */
    fun lastExit(context: Context) = runCatching {
        val info = context.getSystemService(android.app.ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 10)
            // The list also has WebView's isolated renderer processes: only the app's own one counts.
            .firstOrNull { it.processName == context.packageName } ?: return@runCatching
        val reason = android.app.ApplicationExitInfo::class.java.fields
            .firstOrNull { it.name.startsWith("REASON_") && it.getInt(null) == info.reason }
            ?.name?.removePrefix("REASON_") ?: "${info.reason}"
        // importance at death: 100 foreground, 125 fg service, 200 visible, 300+ background / cached.
        d("app", "previous process ended ${time.format(Date(info.timestamp))}: $reason" +
            (info.description?.let { " ($it)" } ?: "") + ", importance ${info.importance}")
    }

    /** JobScheduler's own answer to "why isn't job [id] running?" (Android 15+). */
    fun pendingReason(context: Context, id: Int): String {
        val js = context.getSystemService(android.app.job.JobScheduler::class.java)
        if (js.getPendingJob(id) == null) return "not pending (ran or gone)"
        if (android.os.Build.VERSION.SDK_INT < 35) return "pending (reason needs Android 15)"
        return runCatching {
            val code = js.getPendingJobReason(id)
            // Names straight from the framework, so no hand-kept table can go wrong.
            val name = android.app.job.JobScheduler::class.java.fields
                .firstOrNull { it.name.startsWith("PENDING_JOB_REASON_") && it.getInt(null) == code }
                ?.name?.removePrefix("PENDING_JOB_REASON_") ?: "$code"
            "pending, reason $name"
        }.getOrElse { "pending, reason unavailable" }
    }

    @Synchronized
    fun clear() {
        if (::file.isInitialized) file.delete()
        mirror?.delete()
    }

    fun share(context: Context) {
        if (!::file.isInitialized || !file.exists()) return
        val copy = File(context.cacheDir, "share/pickitup-debug.log").apply { parentFile?.mkdirs() }
        synchronized(this) { file.copyTo(copy, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }
}
