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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    private fun DebugScreen(onBack: () -> Unit) {
        val s = LocalStrings.current
        val barcode by BarcodeStore.barcode.collectAsStateWithLifecycle()
        val busy by BarcodeStore.busy.collectAsStateWithLifecycle()
        val loginNeeded by BarcodeStore.loginNeeded.collectAsStateWithLifecycle()
        val watchStatus by BarcodeStore.watchStatus.collectAsStateWithLifecycle()
        val exitTimeout by BarcodeStore.exitTimeout.collectAsStateWithLifecycle()
        val connected by Watches.connected.collectAsStateWithLifecycle()
        val log by BarcodeStore.log.collectAsStateWithLifecycle()
        val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
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
                // From Ozon's session cookie (as last read), so it's known before the first request.
                val loggedIn by BarcodeStore.loggedIn.collectAsStateWithLifecycle()
                InfoRow(s.stateLogin, if (loggedIn) s.loginOkValue else s.loginNeededValue)
                InfoRow(s.stateFetchedAt, barcode?.let { fmt.format(Date(it.fetchedAt)) } ?: "—")
                val refreshHours by BarcodeStore.refreshHours.collectAsStateWithLifecycle()
                InfoRow(s.stateStale(scheduleText(s, short = true)), if (BarcodeSync.isStale(barcode)) s.yes else s.no)
                InfoRow(s.stateBusy, if (busy) s.yes else s.no)
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
                PillButton(s.refresh, R.drawable.ic_refresh, { BarcodeSync.refreshAndSend(this@DebugActivity) }, Modifier.weight(1f), tonal = true, enabled = !busy)
                PillButton(s.send, R.drawable.ic_watch, { BarcodeSync.sendToWatchAsync(this@DebugActivity) }, Modifier.weight(1f), tonal = true)
            }
            SectionCard(s.journal, R.drawable.ic_bug) {
                Column(SectionInner, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (log.isEmpty()) Caption(s.empty)
                    log.asReversed().forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { BarcodeStore.log.value = emptyList() }) { Text(s.clearJournal) }
                }
            }
        }
    }

    /** "6 h" / "> 6 h" or "daily at 08:00" / "before 08:00" for the current refresh settings. */
}
