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
import androidx.compose.ui.graphics.luminance
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

/** The app name as people see it (package, classes and the repo stay "PickItUp"). */
internal const val APP_NAME = "PickItUp!"

internal const val SOURCE_URL = "https://github.com/RealGrapie/PickItUp"

internal val LocalStrings = staticCompositionLocalOf { Strings.RU }

/**
 * Common base of the companion's screens: each screen is its own activity, so back (and the
 * predictive back gesture) is the system's standard cross-activity animation.
 */
abstract class BaseActivity : ComponentActivity() {
    /** Theme, strings and transparent system bars that follow the app theme (not the system one). */
    protected fun setScreen(content: @Composable () -> Unit) = setContent {
        val language by AppSettings.language.collectAsStateWithLifecycle()
        val dark = isAppDark(AppSettings.theme.collectAsStateWithLifecycle().value)
        LaunchedEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
            else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
            enableEdgeToEdge(style, style)
        }
        CompositionLocalProvider(LocalStrings provides AppSettings.strings(language)) {
            PickItUpTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    protected fun ignoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** System dialog "let the app run in background without restrictions". */
    @SuppressLint("BatteryLife")  // needed: the Pebble app wakes us on demand from the background
    protected fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    /** Re-checked whenever the screen is shown again (e.g. back from the system dialog). */
    @Composable
    protected fun rememberBatteryIgnored(): Boolean {
        val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        return remember(state) { ignoringBatteryOptimizations() }
    }

    /** "6 h" / "> 6 h" or "daily at 08:00" / "before 08:00" for the current refresh settings. */
    protected fun scheduleText(s: Strings, short: Boolean): String {
        val time = RefreshSchedule.formatTime(BarcodeStore.dailyMinutes.value)
        val hours = s.hours(BarcodeStore.refreshHours.value)
        return when (BarcodeStore.refreshMode.value) {
            RefreshMode.INTERVAL -> if (short) "> $hours" else s.every(hours)
            RefreshMode.DAILY -> if (short) s.before(time) else s.dailyAt(time)
        }
    }

    protected fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
}

// ---------------------------------------------------------------- shared screen parts

/** Large collapsing top bar with a back arrow over a scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopBarScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Ic(R.drawable.ic_arrow_back, description = LocalStrings.current.back) }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<T, Int> = emptyMap(),
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {
                    SegmentedButtonDefaults.Icon(value == selected) {
                        icons[value]?.let { Ic(it, Modifier.size(SegmentedButtonDefaults.IconSize)) }
                    }
                },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked, onCheckedChange = onChange,
                thumbContent = if (checked) ({ Ic(R.drawable.ic_check, Modifier.size(SwitchDefaults.IconSize)) }) else null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
internal fun NavRow(icon: Int, title: String, subtitle: String?, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val iconTint = if (!enabled) disabled else tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        leadingContent = { Ic(icon, tint = iconTint) },
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (!enabled) disabled else tint ?: Color.Unspecified)
        },
        supportingContent = subtitle?.let { { Text(it, color = if (enabled) Color.Unspecified else disabled) } },
        trailingContent = { if (enabled) Ic(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/** A clearly red "destructive" colour: M3's dark-theme error (tone 80) reads as pink. */
@Composable
internal fun dangerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.error
