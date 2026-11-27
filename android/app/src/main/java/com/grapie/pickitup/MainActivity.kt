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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {
    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK)
            BarcodeSync.refreshAndSend(this, Accounts.byId(it.data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT)) ?: Accounts.active)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coldStart = savedInstanceState == null
        if (coldStart) Updater.checkOnLaunch()
        setScreen {
            MainScreen(onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })
            var splash by rememberSaveable { mutableStateOf(coldStart) }
            // Not over the splash or the login screen: only once there is an account to work for.
            val loggedIn by Accounts.active.loggedIn.collectAsStateWithLifecycle()
            if (!splash && loggedIn) BatteryPrompt()
            if (splash) SplashOverlay(LocalStrings.current.splashTagline) { splash = false }
        }
    }

    override fun onStart() {
        super.onStart()
        watching = BarcodeStore.watchEnabled.value
        if (!watching) return
        Watches.watch(this)
        // Cold start: find out whether the watchapp is open (sending the stored code, no Ozon
        // request) instead of "Nothing sent yet". Not during a fetch: that would clear the
        // watch's "Updating...".
        if (BarcodeStore.watchStatus.value == WatchStatus.UNKNOWN && !Accounts.active.busy.value)
            BarcodeSync.sendToWatchAsync(this)
    }

    /** Whether onStart called Watches.watch (the setting may change in between). */
    private var watching = false

    override fun onStop() {
        if (watching) Watches.stop()
        watching = false
        super.onStop()
    }

    private fun login(account: Account = Accounts.active) = loginLauncher.launch(LoginActivity.loginIntent(this, account))

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
        val accounts by Accounts.list.collectAsStateWithLifecycle()
        val first = accounts.first()
        val barcode by first.shown.collectAsStateWithLifecycle()
        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()
        // The UI needs a real account id: guest cookies without one (e.g. an antibot page) still mean "log in".
        val loggedIn by first.loggedIn.collectAsStateWithLifecycle()
        // Onboarding only while the one and only account has nothing; added accounts were added on purpose.
        val onboarding = accounts.size == 1 && !(demo || loggedIn) && barcode == null
        AnimatedContent(onboarding, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "onboarding") {
            if (it) OnboardingScreen(onSettings) else BarcodeScreen(onSettings, accounts)
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
                    // One button per marketplace: the first account becomes the one picked.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(s.login, R.drawable.ic_login, { login(Accounts.switchProvider(Accounts.active, Provider.OZON)) }, Modifier.widthIn(min = 240.dp))
                        PillButton(
                            Provider.WB.text(s.login), R.drawable.ic_login,
                            { login(Accounts.switchProvider(Accounts.active, Provider.WB)) }, Modifier.widthIn(min = 240.dp), tonal = true,
                        )
                        PillButton(
                            Provider.CDEK.text(s.login), R.drawable.ic_login,
                            { login(Accounts.switchProvider(Accounts.active, Provider.CDEK)) }, Modifier.widthIn(min = 240.dp), tonal = true,
                        )
                    }
                }
                Spacer(Modifier.weight(1.3f))
                val vpn by Net.vpn.collectAsStateWithLifecycle()
                AnimatedVisibility(vpn) { VpnCard(Modifier.padding(bottom = 16.dp)) }
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

    /** "Add account" on the main screen: which marketplace (a dialog), then its login, as in settings. */
    private val choosing = mutableStateOf(false)

    private fun addAccount() {
        // Reads the WebView feature list: only here, on a tap, not while drawing the screen.
        if (!Accounts.canAdd()) {
            Toast.makeText(this, AppSettings.strings().addAccountUnsupported, Toast.LENGTH_LONG).show()
            return
        }
        choosing.value = true
    }

    private fun addAccount(provider: Provider) {
        choosing.value = false
        val account = Accounts.add(provider)
        if (account.loggedIn.value) BarcodeSync.refreshAndSend(this, account) else login(account)
    }

    /** A watch status the user has to do something about (not just "the watchapp is closed"). */
    private fun WatchStatus.isProblem() =
        this == WatchStatus.NO_PEBBLE_APP || this == WatchStatus.NOT_CONNECTED || this == WatchStatus.NO_PERMISSION || this == WatchStatus.ERROR

    /** Marketplace mark on chips and rows: colour only, no logos (the app is unofficial). */
    private fun providerColor(p: Provider) = when (p) {
        Provider.OZON -> Color(0xFF005BFF)
        Provider.WB -> Color(0xFFA73AFD)
        Provider.CDEK -> Color(0xFF1AB248)
    }

    @Composable
    private fun watchDotColor(status: WatchStatus, connected: Boolean) = when (status) {
        WatchStatus.RECEIVED -> MaterialTheme.colorScheme.primary
        WatchStatus.UNKNOWN, WatchStatus.OTHER_APP_OPEN ->
            if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BarcodeScreen(onSettings: () -> Unit, accounts: List<Account>) {
        val s = LocalStrings.current
        // Chips pick the account; a swipe anywhere below them does too: one page per account.
        val pager = rememberPagerState { accounts.size }
        val scope = rememberCoroutineScope()
        val account = accounts.getOrElse(pager.currentPage) { accounts.first() }
        val barcode by account.shown.collectAsStateWithLifecycle()
        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()
        // Refresh covers every account: the watch gets all of them anyway.
        val busy by remember(accounts) { combine(accounts.map { it.busy }) { b -> b.any { it } } }.collectAsStateWithLifecycle(false)
        val anyLoggedIn by remember(accounts) { combine(accounts.map { it.loggedIn }) { l -> l.any { it } } }.collectAsStateWithLifecycle(false)
        val canRefresh = demo || anyLoggedIn
        val vpn by Net.vpn.collectAsStateWithLifecycle()
        val watchEnabled by BarcodeStore.watchEnabled.collectAsStateWithLifecycle()
        var fullscreen by remember { mutableStateOf(false) }
        var watchSheet by remember { mutableStateOf(false) }
        // Ticks so "today" turns into "yesterday" at midnight without leaving the screen.
        val now by produceState(System.currentTimeMillis()) {
            while (true) { delay(30_000); value = System.currentTimeMillis() }
        }
        // An account was just added: show its page.
        var count by remember { mutableIntStateOf(accounts.size) }
        LaunchedEffect(accounts.size) {
            if (accounts.size > count) pager.animateScrollToPage(accounts.lastIndex)
            count = accounts.size
        }
        // Each page scrolls on its own; the FAB shrinks while the page shown is scrolled down.
        val scrolls = remember(accounts.size) { List(accounts.size) { ScrollState(0) } }
        val expanded by remember(scrolls) { derivedStateOf { (scrolls.getOrNull(pager.currentPage)?.value ?: 0) < 40 } }
        val refreshAll = { if (!busy) BarcodeSync.refreshAllAndSend(this@MainActivity) }
        val pullAt = with(LocalDensity.current) { 72.dp.toPx() }
        val refresher = remember(pullAt) { PullRefresh(scope, pullAt) { BarcodeSync.refreshAllAndSend(this@MainActivity) } }
        refresher.enabled = canRefresh && !busy
        val busyNow by rememberUpdatedState(busy)
        // Let go past the line: the gap stays until every account is fetched, then says done (or just closes).
        LaunchedEffect(refresher.phase) {
            if (refresher.phase != PullRefresh.Phase.REFRESHING) return@LaunchedEffect
            withTimeoutOrNull(1500) { snapshotFlow { busyNow }.first { it } }
            snapshotFlow { busyNow }.first { !it }
            val ok = accounts.all { !(it.loggedIn.value || demo) || !BarcodeSync.isStale(it.shown.value) }
            refresher.finished(ok)
        }

        // Fixed bar: the content fits the screen, so nothing collapses or scrolls for no reason.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(APP_NAME, style = MaterialTheme.typography.headlineMedium) },
                    actions = {
                        if (watchEnabled) WatchButton { watchSheet = true }
                        SettingsButton(onSettings)
                    },
                )
            },
            floatingActionButton = {
                if (canRefresh) {
                    val spin = rememberInfiniteTransition(label = "spin")
                    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
                    val source = remember { MutableInteractionSource() }
                    ExtendedFloatingActionButton(
                        onClick = refreshAll,
                        expanded = expanded,
                        icon = { Ic(R.drawable.ic_refresh, Modifier.graphicsLayer { rotationZ = if (busy) angle else 0f }) },
                        text = {
                            Text(
                                if (busy) s.refreshing else if (accounts.size > 1) s.refreshAll else s.refresh,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        shape = morphShape(source, rest = 20.dp, pressed = 12.dp),
                        interactionSource = source,
                        modifier = Modifier.pressScale(source),
                    )
                }
            },
        ) { padding ->
            // One page per account under the chips: a swipe anywhere on the screen switches.
            val pages: @Composable () -> Unit = {
                HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 16.dp, verticalAlignment = Alignment.Top) { page ->
                    val a = accounts.getOrNull(page) ?: return@HorizontalPager
                    val code by a.shown.collectAsStateWithLifecycle()
                    val sessionLost by a.loginNeeded.collectAsStateWithLifecycle()
                    val loggedIn by a.loggedIn.collectAsStateWithLifecycle()
                    // loginNeeded lives only in memory: after a restart the persisted loggedIn = false must
                    // still show the "session ended" card over an old code (a background run found it out).
                    val loginNeeded = (sessionLost || !loggedIn) && !demo
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scrolls.getOrElse(page) { rememberScrollState() }).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        BarcodeCard(a, now, onEnlarge = { fullscreen = true }, onWatch = { watchSheet = true })
                        AnimatedVisibility(loginNeeded) { SessionCard(a, fresh = code == null) }
                        AnimatedVisibility(vpn && !demo) { VpnCard() }
                        UpdateCard(skippable = true)
                        if (canRefresh) {
                            Text(
                                s.pullHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(72.dp))  // room for the FAB
                    }
                }
            }
            Column(Modifier.padding(padding)) {
                AccountChips(accounts, pager.currentPage, Modifier.padding(horizontal = 16.dp)) { scope.launch { pager.animateScrollToPage(it) } }
                // Pulled down, the pages come after the finger and the line in the gap says what letting go does.
                Box(Modifier.weight(1f).nestedScroll(refresher.connection)) {
                    PullRefreshLine(refresher, pullAt)
                    Box(Modifier.fillMaxSize().graphicsLayer { translationY = refresher.pull }) { pages() }
                }
            }
        }

        val b = barcode
        if (fullscreen && b != null) FullscreenBarcode(b) { fullscreen = false }
        if (watchSheet) WatchSheet(accounts) { watchSheet = false }
        if (choosing.value) {
            AlertDialog(
                onDismissRequest = { choosing.value = false },
                icon = { Ic(R.drawable.ic_add) },
                title = { Text(s.chooseMarketplace) },
                text = {
                    Column {
                        Provider.entries.forEach { p ->
                            NavRow(R.drawable.ic_shopping_bag, p.fullName, null) { addAccount(p) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { choosing.value = false }) { Text(s.cancel) } },
            )
        }
    }

    /** One chip per account (a red dot when it needs a look) and "Add account" at the end. */
    @Composable
    private fun AccountChips(accounts: List<Account>, current: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
        val s = LocalStrings.current
        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()
        Row(
            modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            accounts.forEachIndexed { i, a ->
                val name by a.name.collectAsStateWithLifecycle()
                val b by a.shown.collectAsStateWithLifecycle()
                val lost by a.loginNeeded.collectAsStateWithLifecycle()
                val loggedIn by a.loggedIn.collectAsStateWithLifecycle()
                val failed by a.lastError.collectAsStateWithLifecycle()
                val code = b
                val attention = !demo && (lost || !loggedIn || failed != null || code != null && BarcodeSync.isStale(code))
                FilterChip(
                    selected = i == current,
                    onClick = { onSelect(i) },
                    label = { Text(a.label(name), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(providerColor(a.provider))) },
                    trailingIcon = if (attention) ({ Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)) }) else null,
                )
            }
            AssistChip(
                onClick = { addAccount() },
                label = { Text(s.addAccount, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                leadingIcon = { Ic(R.drawable.ic_add, Modifier.size(18.dp)) },
            )
        }
    }

    @Composable
    private fun BarcodeCard(account: Account, now: Long, onEnlarge: () -> Unit, onWatch: () -> Unit) {
        val s = LocalStrings.current
        val barcode by account.shown.collectAsStateWithLifecycle()
        val busy by account.busy.collectAsStateWithLifecycle()
        val name by account.name.collectAsStateWithLifecycle()
        val loggedIn by account.loggedIn.collectAsStateWithLifecycle()
        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()
        val watchEnabled by BarcodeStore.watchEnabled.collectAsStateWithLifecycle()
        val error by account.lastError.collectAsStateWithLifecycle()
        val emptyAt by account.emptyAt.collectAsStateWithLifecycle()
        var errorShown by remember { mutableStateOf(false) }
        val provider = account.provider
        val title =provider.title.uppercase() + if (name.isBlank()) "" else " · $name"
        val source = remember { MutableInteractionSource() }
        val shape = MaterialTheme.shapes.extraLarge
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = Modifier.fillMaxWidth().pressScale(source, 0.98f).focusRing(source, shape),
        ) {
            Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val b = barcode
                // Logged in, checked, nothing waits at a pickup point (CDEK).
                val nothing = b == null && loggedIn && !demo && emptyAt > 0
                Column(
                    Modifier
                        .clickable(source, LocalIndication.current, enabled = b != null, onClick = onEnlarge)
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (b == null) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, maxLines = 1)
                        Text(
                            provider.text(if (busy) s.gettingBarcode else if (nothing) s.noOrders else s.noBarcode),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, maxLines = 1)
                                Text(
                                    b.visible, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium, maxLines = 1,
                                )
                            }
                            FilledTonalIconButton(onClick = onEnlarge) { Ic(R.drawable.ic_fullscreen, description = s.enlarge) }
                        }
                        val qr = b.format == CodeFormat.QR
                        PickupCodeView(b, Modifier.fillMaxWidth().height(if (qr) 200.dp else 132.dp))
                        Text(
                            if (qr) s.showQrToStaff else s.showToStaff, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // When it was updated, whether the last refresh failed, whether the watch has it.
                val failed = error.takeIf { !busy && !demo && loggedIn }
                if (b != null || failed != null || nothing) {
                    FlowRow(
                        Modifier.padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val colors = MaterialTheme.colorScheme
                        if (busy) CardTag(R.drawable.ic_refresh, s.refreshing, colors.surfaceContainerHighest, colors.onSurfaceVariant)
                        else if (b != null) {
                            val stale = BarcodeSync.isStale(b)
                            CardTag(
                                R.drawable.ic_schedule,
                                s.receivedWhen(b.fetchedAt, now) + if (stale) " · ${s.stale}" else "",
                                if (stale) colors.errorContainer else colors.surfaceContainerHighest,
                                if (stale) colors.onErrorContainer else colors.onSurfaceVariant,
                            )
                        } else if (nothing) CardTag(R.drawable.ic_schedule, s.checkedWhen(s.receivedWhen(emptyAt, now)), colors.surfaceContainerHighest, colors.onSurfaceVariant)
                        if (watchEnabled && b != null) WatchTag(account, b, onWatch)
                        if (failed != null) CardTag(R.drawable.ic_cloud_off, s.refreshFailed, colors.errorContainer, colors.onErrorContainer) { errorShown = true }
                    }
                }
                if (errorShown && failed != null) {
                    AlertDialog(
                        onDismissRequest = { errorShown = false },
                        icon = { Ic(R.drawable.ic_cloud_off) },
                        title = { Text(s.refreshFailed) },
                        text = { Text(failed) },
                        confirmButton = {
                            TextButton(onClick = { errorShown = false; BarcodeSync.refreshAndSend(this@MainActivity, account) }) { Text(s.retry) }
                        },
                        dismissButton = { TextButton(onClick = { errorShown = false }) { Text(s.ok) } },
                    )
                }
                if (loggedIn || demo) {
                    PillButton(
                        provider.text(s.myOrders), R.drawable.ic_shopping_bag,
                        {
                            if (demo) Toast.makeText(this@MainActivity, s.demoOrders, Toast.LENGTH_SHORT).show()
                            else LoginActivity.openPage(this@MainActivity, account, provider.ordersUrl)
                        },
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp), tonal = true,
                    )
                }
            }
        }
    }

    /** Small rounded label under the code; tappable when [onClick] is given. */
    @Composable
    private fun CardTag(icon: Int, text: String, container: Color, content: Color, onClick: (() -> Unit)? = null) {
        val inner: @Composable () -> Unit = {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Ic(icon, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                AnimatedContent(text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tag") {
                    Text(it, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        val c by animateColorAsState(container, label = "tag")
        // No 48 dp touch padding: it would push a tappable tag below its plain neighbour.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            if (onClick == null) Surface(color = c, contentColor = content, shape = CircleShape, content = inner)
            else Surface(onClick = onClick, color = c, contentColor = content, shape = CircleShape, content = inner)
        }
    }

    /** "On the watch" while the watch has this very code; else why not (a problem in red). */
    @Composable
    private fun WatchTag(account: Account, b: Barcode, onClick: () -> Unit) {
        val s = LocalStrings.current
        val at by account.onWatchAt.collectAsStateWithLifecycle()
        val status by BarcodeStore.watchStatus.collectAsStateWithLifecycle()
        val colors = MaterialTheme.colorScheme
        when {
            at == b.fetchedAt -> CardTag(R.drawable.ic_check, s.onWatch, colors.primaryContainer, colors.onPrimaryContainer, onClick)
            status.isProblem() -> CardTag(R.drawable.ic_watch, s.watchStatus(status), colors.errorContainer, colors.onErrorContainer, onClick)
            else -> CardTag(R.drawable.ic_watch, s.notOnWatch, colors.surfaceContainerHighest, colors.onSurfaceVariant, onClick)
        }
    }

    /** Watch icon in the top bar with a status dot; opens [WatchSheet]. */
    @Composable
    private fun WatchButton(onClick: () -> Unit) {
        val s = LocalStrings.current
        val status by BarcodeStore.watchStatus.collectAsStateWithLifecycle()
        val connected by Watches.connected.collectAsStateWithLifecycle()
        val dot by animateColorAsState(watchDotColor(status, connected.isNotEmpty()), label = "watchDot")
        IconButton(onClick = onClick) {
            BadgedBox(badge = { Badge(containerColor = dot) }) {
                Ic(R.drawable.ic_watch, description = s.watchTitle)
            }
        }
    }

    /** The watch: model, last send result, which codes it has, and a way to open the watchapp. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WatchSheet(accounts: List<Account>, onClose: () -> Unit) {
        val s = LocalStrings.current
        val status by BarcodeStore.watchStatus.collectAsStateWithLifecycle()
        val connected by Watches.connected.collectAsStateWithLifecycle()
        val lastWatch by Watches.last.collectAsStateWithLifecycle()
        val deliveredAt by BarcodeStore.deliveredAt.collectAsStateWithLifecycle()
        val demo by BarcodeStore.demo.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val watch = connected.firstOrNull() ?: lastWatch
                val title = when {
                    connected.size > 1 -> connected.joinToString(" + ") { it.model }
                    watch != null -> watch.model
                    demo -> "Pebble 2 Duo"
                    else -> "Pebble"
                }
                StatusItem(R.drawable.ic_watch, title, s.watchStatus(status).replaceFirstChar { it.uppercase() }) {
                    StatusDot(watchDotColor(status, connected.isNotEmpty()))
                }
                if (deliveredAt > 0) {
                    Text(
                        s.lastDelivered(s.receivedWhen(deliveredAt)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Text(
                    s.watchCodes, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                accounts.forEach { a ->
                    val name by a.name.collectAsStateWithLifecycle()
                    val b by a.shown.collectAsStateWithLifecycle()
                    val at by a.onWatchAt.collectAsStateWithLifecycle()
                    val code = b
                    val on = code != null && at == code.fetchedAt
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(providerColor(a.provider)))
                        Spacer(Modifier.width(12.dp))
                        Text(a.label(name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (on) s.onWatch else s.notOnWatch, style = MaterialTheme.typography.bodyMedium,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (connected.isNotEmpty() || demo) {
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        s.openWatchapp, R.drawable.ic_watch,
                        { BarcodeSync.openWatchapp(this@MainActivity); onClose() },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp), tonal = true,
                    )
                }
            }
        }
    }

    @Composable
    private fun VpnCard(modifier: Modifier = Modifier) {
        val s = LocalStrings.current
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large, modifier = modifier) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.vpnTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(s.vpnText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(4.dp))
                PillButton(s.vpnSettings, R.drawable.ic_settings, { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }, tonal = true)
            }
        }
    }

    @Composable
    private fun SessionCard(account: Account, fresh: Boolean) {
        val s = LocalStrings.current
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.provider.text(if (fresh) s.newLoginTitle else s.sessionTitle), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(account.provider.text(if (fresh) s.newLoginText else s.sessionText), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(4.dp))
                PillButton(account.provider.text(s.login), R.drawable.ic_login, { login(account) })
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
                    PickupCodeView(b, Modifier.fillMaxWidth().height(if (b.format == CodeFormat.QR) 340.dp else 220.dp), RoundedCornerShape(0.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(b.visible, color = Color.Black, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(32.dp))
                    Text(s.tapToClose, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
