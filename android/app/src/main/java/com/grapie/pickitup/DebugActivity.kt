package com.grapie.pickitup

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen { DebugScreen(onBack = ::finish) }
    }

    override fun onStart() {
        super.onStart()
        Watches.watch(this)
    }

    override fun onStop() {
        Watches.stop()
        super.onStop()
    }

    // ---------------------------------------------------------------- debug

    @Composable
    private fun JournalDay(title: String) {
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }

    /** Time, icon, text; under it whose it is (a small label), how long it took, renewed tokens. */
    @Composable
    private fun JournalRow(r: Journal.Row, time: String) {
        val s = LocalStrings.current
        val e = r.entry
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Text(
                time.substring(0, 5), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(48.dp).padding(top = 2.dp),
            )
            Box(Modifier.width(26.dp), contentAlignment = Alignment.TopCenter) {
                Text(e.icon.ifEmpty { "•" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    e.text + if (r.count > 1) "  ×${r.count}" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                )
                val notes = listOfNotNull(r.seconds?.let(s.journalTook), r.tokens?.let { if (e.icon == "🔐") it else s.journalTokens + " " + it }).joinToString(" · ")
                if (e.account != null || notes.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    e.account?.let {
                        Text(
                            it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        if (notes.isNotEmpty()) Spacer(Modifier.width(8.dp))
                    }
                    if (notes.isNotEmpty()) Text(notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    @Composable
    private fun DebugScreen(onBack: () -> Unit) {
        val s = LocalStrings.current
        val accounts by Accounts.list.collectAsStateWithLifecycle()
        // Any account fetching: one request at a time app-wide anyway.
        val busy = accounts.map { it.busy.collectAsStateWithLifecycle().value }.any { it }
        val watchStatus by BarcodeStore.watchStatus.collectAsStateWithLifecycle()
        val exitTimeout by BarcodeStore.exitTimeout.collectAsStateWithLifecycle()
        val connected by Watches.connected.collectAsStateWithLifecycle()
        val log by BarcodeStore.log.collectAsStateWithLifecycle()
        val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
        val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        val dayFmt = remember(s) { SimpleDateFormat("d MMMM", s.locale) }
        val batteryIgnored = rememberBatteryIgnored()
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()

        TopBarScreen(s.debug, onBack) {
            SectionCard(s.demoSection, R.drawable.ic_fullscreen) {
                SwitchRow(s.demoLabel, s.demoHint, demo) {
                    BarcodeStore.setDemo(it)
                    BarcodeSync.sendToWatchAsync(this@DebugActivity)
                }
            }
            SectionCard(s.sectionState, R.drawable.ic_info) {
                val refreshHours by BarcodeStore.refreshHours.collectAsStateWithLifecycle()
                // Per account; the name as a heading once there are several.
                accounts.forEach { a ->
                    val name by a.name.collectAsStateWithLifecycle()
                    val barcode by a.barcode.collectAsStateWithLifecycle()
                    val aBusy by a.busy.collectAsStateWithLifecycle()
                    // From the session cookie (as last read), so it's known before the first request.
                    val loggedIn by a.loggedIn.collectAsStateWithLifecycle()
                    if (accounts.size > 1) Text(
                        a.label(name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    InfoRow(s.stateLogin, if (loggedIn) s.loginOkValue else s.loginNeededValue)
                    InfoRow(s.stateFetchedAt, barcode?.let { fmt.format(Date(it.fetchedAt)) } ?: "—")
                    InfoRow(s.stateStale(scheduleText(s, short = true)), if (BarcodeSync.isStale(barcode)) s.yes else s.no)
                    InfoRow(s.stateBusy, if (aBusy) s.yes else s.no)
                    if (a.provider == Provider.WB) {
                        // Re-read after a renewal: the token lives in prefs, not in a flow.
                        var renewed by remember { mutableIntStateOf(0) }
                        val until = remember(renewed, lifecycleState, aBusy) { WbClient.token(a)?.let { DebugLog.at(WbClient.expiry(it)) } ?: "—" }
                        InfoRow(s.stateWbToken, until)
                        var renewing by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = {
                                renewing = true
                                lifecycleScope.launch { WbClient.renew(this@DebugActivity, a); renewing = false; renewed++ }
                            },
                            enabled = loggedIn && !renewing,
                            modifier = Modifier.padding(start = 4.dp),
                        ) { Text(s.wbRenewNow) }
                    }
                }
                if (accounts.size > 1) Spacer(Modifier.height(10.dp))
                InfoRow(s.statePebble, s.watchStatus(watchStatus))
                InfoRow(
                    s.stateWatch,
                    connected.joinToString("\n") { "${it.name} (${it.platform}/${it.revision})" }.ifEmpty { s.watchesNone },
                )
                InfoRow(s.stateExit, if (exitTimeout == 0) s.never else s.seconds(exitTimeout))
                val refreshMode by BarcodeStore.refreshMode.collectAsStateWithLifecycle()
                val dailyMinutes by BarcodeStore.dailyMinutes.collectAsStateWithLifecycle()
                val scheduled = remember(lifecycleState, refreshHours, refreshMode, dailyMinutes) {
                    RefreshJobService.isScheduled(this@DebugActivity)
                }
                InfoRow(s.stateBackground, if (scheduled) scheduleText(s, short = false) else s.off)
                InfoRow(s.stateBattery, if (batteryIgnored) s.batteryIgnored else s.batteryOptimized)
                InfoRow(s.stateVersion, appVersion())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(s.refresh, R.drawable.ic_refresh, { BarcodeSync.refreshAllAndSend(this@DebugActivity) }, Modifier.weight(1f), tonal = true, enabled = !busy)
                PillButton(s.send, R.drawable.ic_watch, { BarcodeSync.sendToWatchAsync(this@DebugActivity) }, Modifier.weight(1f), tonal = true)
            }
            if (SessionProbe.available) {
                // Sniff builds only (research): English, not in Strings.kt.
                val probe by SessionProbe.state.collectAsStateWithLifecycle()
                val loggedIn by Accounts.active.loggedIn.collectAsStateWithLifecycle()
                SectionCard("Session probe", R.drawable.ic_info) {
                    Column(SectionInner, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Caption("One Ozon request after idle gaps of 6, 9, 12, 18, 24, 36, 48 h; nothing in between. Results go to the detailed log.")
                        Text(probe, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (SessionProbe.active) TextButton(onClick = { SessionProbe.stop(this@DebugActivity) }) { Text("Stop probe") }
                        else TextButton(onClick = { SessionProbe.start(this@DebugActivity) }, enabled = loggedIn) { Text("Start probe") }
                    }
                }
            }
            SectionCard(s.journal, R.drawable.ic_bug) {
                Column(SectionInner, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (log.isEmpty()) Caption(s.empty)
                    val rows = remember(log) { Journal.rows(log.mapNotNull(Journal::parse)) }
                    var lastDay = -1
                    rows.forEach { r ->
                        val ago = Journal.daysAgo(r.entry.time)
                        if (ago != lastDay) {
                            lastDay = ago
                            JournalDay(when (ago) { 0 -> s.journalToday; 1 -> s.journalYesterday; else -> dayFmt.format(Date(r.entry.time)) })
                        }
                        JournalRow(r, timeFmt.format(Date(r.entry.time)))
                    }
                    // Every request, cookie change and answer, without secrets: a file to send to the developer.
                    TextButton(onClick = { DebugLog.share(this@DebugActivity) }) {
                        Icon(painterResource(R.drawable.ic_share), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(s.shareDebugLog)
                    }
                    // A fork: logs get cleaned with a fork (Russian meme).
                    TextButton(onClick = { BarcodeStore.clearLog() }) {
                        Icon(painterResource(R.drawable.ic_fork), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(s.clearJournal)
                    }
                }
            }
        }
    }

    /** "6 h" / "> 6 h" or "daily at 08:00" / "before 08:00" for the current refresh settings. */
}
