package com.grapie.pickitup

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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

class MainActivity : BaseActivity() {
    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) BarcodeSync.refreshAndSend(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coldStart = savedInstanceState == null
        if (coldStart) Updater.checkOnLaunch()
        setScreen {
            MainScreen(onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })
            var splash by rememberSaveable { mutableStateOf(coldStart) }
            // Not over the splash or the login screen: only once there is an account to work for.
            val loggedIn by BarcodeStore.loggedIn.collectAsStateWithLifecycle()
            if (!splash && loggedIn) BatteryPrompt()
            if (splash) SplashOverlay(LocalStrings.current.splashTagline) { splash = false }
        }
    }

    override fun onStart() {
        super.onStart()
        Watches.watch(this)
        // Cold start: find out whether the watchapp is open (sending the stored code, no Ozon
        // request) instead of "Nothing sent yet". Not during a fetch: that would clear the
        // watch's "Updating...".
        if (BarcodeStore.watchStatus.value == WatchStatus.UNKNOWN && !BarcodeStore.busy.value)
            BarcodeSync.sendToWatchAsync(this)
    }

    override fun onStop() {
        Watches.stop()
        super.onStop()
    }

    private fun login() = loginLauncher.launch(Intent(this, LoginActivity::class.java))

    /** Once, after the first login: explain, then hand over to the system dialog. */
    @Composable
    private fun BatteryPrompt() {
        val s = LocalStrings.current
        var show by remember { mutableStateOf(!AppSettings.batteryPromptShown && !ignoringBatteryOptimizations()) }
        if (!show) return
        fun close() {
            AppSettings.batteryPromptShown = true
            show = false
        }
        AlertDialog(
            onDismissRequest = ::close,
            icon = { Ic(R.drawable.ic_bolt) },
            title = { Text(s.batteryTitle) },
            text = { Text(s.batteryText) },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    requestIgnoreBatteryOptimizations()
                }) { Text(s.allow) }
            },
            dismissButton = { TextButton(onClick = ::close) { Text(s.later) } },
        )
    }

    // ---------------------------------------------------------------- main

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen(onSettings: () -> Unit) {
        val s = LocalStrings.current
        val barcode by BarcodeStore.shown.collectAsStateWithLifecycle()
        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()
        val busy by BarcodeStore.busy.collectAsStateWithLifecycle()
        val sessionLost by BarcodeStore.loginNeeded.collectAsStateWithLifecycle()
        val loginNeeded = sessionLost && !demo
        // The UI needs a real account id: guest cookies without one (e.g. an antibot page) still mean "log in".
        val loggedIn by BarcodeStore.loggedIn.collectAsStateWithLifecycle()
        val canRefresh = demo || loggedIn

        val onboarding = !canRefresh && barcode == null
        AnimatedContent(onboarding, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "onboarding") {
            if (it) OnboardingScreen(onSettings) else BarcodeScreen(onSettings, canRefresh, busy, loginNeeded)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OnboardingScreen(onSettings: () -> Unit) {
        val s = LocalStrings.current
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        val pulse = rememberInfiniteTransition(label = "pulse")
        val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = EaseOutCubic)), label = "ring")
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(APP_NAME, style = MaterialTheme.typography.headlineSmall) },
                    actions = { SettingsButton(onSettings) },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(shown, enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), 0.6f) + fadeIn()) {
                    val primary = MaterialTheme.colorScheme.primary
                    Box(
                        Modifier.size(176.dp).drawBehind {
                            // A soft ring spreading out of the badge.
                            drawCircle(primary.copy(alpha = 0.22f * (1f - ring)), radius = size.minDimension / 2 * (0.62f + 0.38f * ring))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Ic(R.drawable.ic_login, Modifier.size(48.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                AnimatedVisibility(shown, enter = fadeIn(tween(400, 150)) + slideInVertically(tween(400, 150)) { it / 2 }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.onboardTitle, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            s.onboardText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                AnimatedVisibility(shown, enter = fadeIn(tween(400, 300)) + slideInVertically(tween(400, 300)) { it }) {
                    PillButton(s.login, R.drawable.ic_login, ::login, Modifier.widthIn(min = 220.dp))
                }
                Spacer(Modifier.weight(1.3f))
                UpdateCard(Modifier.padding(bottom = 16.dp), skippable = true)
            }
        }
    }

    @Composable
    private fun SettingsButton(onSettings: () -> Unit) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val turn by animateFloatAsState(if (pressed) 60f else 0f, label = "gear")
        IconButton(onClick = onSettings, interactionSource = source) {
            Ic(R.drawable.ic_settings, Modifier.graphicsLayer { rotationZ = turn }, description = LocalStrings.current.settings)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BarcodeScreen(onSettings: () -> Unit, canRefresh: Boolean, busy: Boolean, loginNeeded: Boolean) {
        val s = LocalStrings.current
        val barcode by BarcodeStore.shown.collectAsStateWithLifecycle()
        val watchStatus by BarcodeStore.watchStatus.collectAsStateWithLifecycle()
        val connected by Watches.connected.collectAsStateWithLifecycle()
        val lastWatch by Watches.last.collectAsStateWithLifecycle()
        var fullscreen by remember { mutableStateOf(false) }
        // Ticks so "today" turns into "yesterday" at midnight without leaving the screen.
        val now by produceState(System.currentTimeMillis()) {
            while (true) { delay(30_000); value = System.currentTimeMillis() }
        }
        val scroll = rememberScrollState()
        val expanded by remember { derivedStateOf { scroll.value < 40 } }

        // Fixed bar: the content fits the screen, so nothing collapses or scrolls for no reason.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(APP_NAME, style = MaterialTheme.typography.headlineMedium) },
                    actions = { SettingsButton(onSettings) },
                )
            },
            floatingActionButton = {
                if (canRefresh) {
                    val spin = rememberInfiniteTransition(label = "spin")
                    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
                    val source = remember { MutableInteractionSource() }
                    ExtendedFloatingActionButton(
                        onClick = { if (!busy) BarcodeSync.refreshAndSend(this@MainActivity) },
                        expanded = expanded,
                        icon = { Ic(R.drawable.ic_refresh, Modifier.graphicsLayer { rotationZ = if (busy) angle else 0f }) },
                        text = { Text(if (busy) s.refreshing else s.refresh, style = MaterialTheme.typography.titleMedium) },
                        shape = morphShape(source, rest = 20.dp, pressed = 12.dp),
                        interactionSource = source,
                        modifier = Modifier.pressScale(source),
                    )
                }
            },
        ) { padding ->
            val content: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    BarcodeCard(barcode, busy, onEnlarge = { fullscreen = true })

                    AnimatedVisibility(loginNeeded && barcode != null) { SessionCard() }
                    UpdateCard(skippable = true)

                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            val b = barcode
                            val stale = BarcodeSync.isStale(b)
                            StatusItem(
                                icon = R.drawable.ic_schedule,
                                title = s.receivedTitle,
                                text = b?.let { s.receivedWhen(it.fetchedAt, now) } ?: "—",
                            ) {
                                when {
                                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                    b != null && stale -> Badge(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                                        Text(s.stale, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(start = 76.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            val watch = connected.firstOrNull() ?: lastWatch
                            val title = when {
                                connected.size > 1 -> connected.joinToString(" + ") { it.model }
                                watch != null -> watch.model
                                else -> "Pebble"
                            }
                            val otherApp = watchStatus == WatchStatus.OTHER_APP_OPEN
                            StatusItem(
                                icon = R.drawable.ic_watch,
                                title = title,
                                text = s.watchStatus(watchStatus).replaceFirstChar { it.uppercase() },
                                action = if (otherApp) s.tapToOpenWatchapp else null,
                                onClick = if (otherApp) ({ BarcodeSync.openWatchapp(this@MainActivity) }) else null,
                            ) {
                                StatusDot(
                                    when (watchStatus) {
                                        WatchStatus.RECEIVED -> MaterialTheme.colorScheme.primary
                                        WatchStatus.UNKNOWN, WatchStatus.OTHER_APP_OPEN ->
                                            if (connected.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }

                    if (canRefresh) {
                        PillButton(
                            s.myOrders, R.drawable.ic_shopping_bag,
                            {
                                if (BarcodeStore.demo.value) Toast.makeText(this@MainActivity, s.demoOrders, Toast.LENGTH_SHORT).show()
                                else LoginActivity.openPage(this@MainActivity, LoginActivity.ORDERS_URL)
                            },
                            Modifier.fillMaxWidth(), tonal = true,
                        )
                        Text(
                            s.pullHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(72.dp))  // room for the FAB
                }
            }
            Box(Modifier.padding(padding)) {
                if (canRefresh) {
                    PullToRefreshBox(
                        isRefreshing = busy,
                        onRefresh = { BarcodeSync.refreshAndSend(this@MainActivity) },
                        modifier = Modifier.fillMaxSize(),
                    ) { content() }
                } else content()
            }
        }

        val b = barcode
        if (fullscreen && b != null) FullscreenBarcode(b) { fullscreen = false }
    }

    @Composable
    private fun BarcodeCard(barcode: Barcode?, busy: Boolean, onEnlarge: () -> Unit) {
        val s = LocalStrings.current
        val source = remember { MutableInteractionSource() }
        val shape = MaterialTheme.shapes.extraLarge
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = Modifier.fillMaxWidth().pressScale(source, 0.98f).focusRing(source, shape),
        ) {
            Column(
                Modifier
                    .clickable(source, LocalIndication.current, enabled = barcode != null, onClick = onEnlarge)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (barcode == null) {
                    Text(
                        if (busy) s.gettingBarcode else s.noBarcode,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OZON", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                        Text(
                            barcode.visible, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                        )
                    }
                    FilledTonalIconButton(onClick = onEnlarge) { Ic(R.drawable.ic_fullscreen, description = s.enlarge) }
                }
                BarcodeView(barcode.code, Modifier.fillMaxWidth().height(132.dp))
                Text(
                    s.showToStaff, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun SessionCard() {
        val s = LocalStrings.current
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.sessionTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(s.sessionText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(4.dp))
                PillButton(s.login, R.drawable.ic_login, ::login)
            }
        }
    }

    @Composable
    private fun StatusItem(icon: Int, title: String, text: String, action: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
        val source = remember { MutableInteractionSource() }
        // Own row instead of ListItem: a three-line ListItem pins the trailing dot to the top.
        Row(
            modifier = (if (onClick == null) Modifier
            else Modifier.pressScale(source, 0.98f).clip(MaterialTheme.shapes.large)
                .clickable(source, LocalIndication.current, onClick = onClick))
                .fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                AnimatedContent(text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(action != null) {
                    Text(action.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }

    /** Barcode on white over the whole screen, at full brightness, for the pickup scanner. */
    @Composable
    private fun FullscreenBarcode(b: Barcode, onClose: () -> Unit) {
        val s = LocalStrings.current
        Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            DisposableEffect(window) {
                window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
                onDispose { }
            }
            val shown = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
                Column(
                    Modifier.fillMaxSize().background(Color.White).clickable(onClick = onClose).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BarcodeView(b.code, Modifier.fillMaxWidth().height(220.dp), RoundedCornerShape(0.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(b.visible, color = Color.Black, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(32.dp))
                    Text(s.tapToClose, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
