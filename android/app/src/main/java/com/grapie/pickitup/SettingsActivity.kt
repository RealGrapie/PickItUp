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

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen { SettingsScreen(onBack = ::finish, onDebug = { startActivity(Intent(this, DebugActivity::class.java)) }) }
    }

    // ---------------------------------------------------------------- settings

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(onBack: () -> Unit, onDebug: () -> Unit) {
        val s = LocalStrings.current
        val exitTimeout by BarcodeStore.exitTimeout.collectAsStateWithLifecycle()
        val refreshHours by BarcodeStore.refreshHours.collectAsStateWithLifecycle()
        val backgroundRefresh by BarcodeStore.backgroundRefresh.collectAsStateWithLifecycle()
        val refreshMode by BarcodeStore.refreshMode.collectAsStateWithLifecycle()
        val dailyMinutes by BarcodeStore.dailyMinutes.collectAsStateWithLifecycle()
        var showTimePicker by remember { mutableStateOf(false) }
        val language by AppSettings.language.collectAsStateWithLifecycle()
        var confirmLogout by remember { mutableStateOf(false) }
        var showAbout by rememberSaveable { mutableStateOf(false) }
        val loggedIn by BarcodeStore.loggedIn.collectAsStateWithLifecycle()

        TopBarScreen(s.settings, onBack) {
            AppearanceSection()

            SectionCard(s.languageLabel, R.drawable.ic_language) {
                Segmented(
                    listOf(Language.SYSTEM to s.langSystem, Language.RU to "Русский", Language.EN to "English"),
                    language, AppSettings::setLanguage, SectionInner,
                )
            }

            SectionCard(s.sectionBarcode, R.drawable.ic_schedule) {
                fun setMode(m: RefreshMode) {
                    BarcodeStore.setRefreshMode(m)
                    RefreshJobService.schedule(this@SettingsActivity)
                }
                Caption(s.refreshLabel, SectionInner)
                // Two mutually exclusive modes: every N hours, or daily at a chosen time.
                RadioRow(s.modeInterval, refreshMode == RefreshMode.INTERVAL, { setMode(RefreshMode.INTERVAL) })
                Choices(
                    RefreshSchedule.INTERVAL_OPTIONS.map { it to s.hours(it) },
                    if (refreshMode == RefreshMode.INTERVAL) refreshHours else null,
                    enabled = refreshMode == RefreshMode.INTERVAL,
                    modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                ) {
                    BarcodeStore.setRefreshHours(it)
                    RefreshJobService.schedule(this@SettingsActivity)
                }
                RadioRow(s.modeDaily, refreshMode == RefreshMode.DAILY, { setMode(RefreshMode.DAILY) }) {
                    OutlinedButton(onClick = {
                        setMode(RefreshMode.DAILY)
                        showTimePicker = true
                    }, shape = MaterialTheme.shapes.small) {
                        Text(RefreshSchedule.formatTime(dailyMinutes), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Caption(s.refreshHint, SectionInner)
                SwitchRow(s.backgroundLabel, null, backgroundRefresh) {
                    BarcodeStore.setBackgroundRefresh(it)
                    RefreshJobService.schedule(this@SettingsActivity)
                }
            }

            SectionCard(s.sectionPebble, R.drawable.ic_timer) {
                Caption(s.exitTimeoutLabel, SectionInner)
                Choices(
                    listOf(0 to s.never, 30 to s.seconds(30), 60 to s.minutes(1), 120 to s.minutes(2), 300 to s.minutes(5)),
                    exitTimeout, modifier = SectionInner,
                ) {
                    BarcodeStore.setExitTimeout(it)
                    BarcodeSync.sendToWatchAsync(this@SettingsActivity)
                }
            }

            // Shown only while background work is restricted; disappears once it's allowed.
            AnimatedVisibility(!rememberBatteryIgnored()) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ic(R.drawable.ic_bolt, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(s.batteryTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Text(s.batteryText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        PillButton(s.batteryRequest, null, ::requestIgnoreBatteryOptimizations, Modifier.fillMaxWidth())
                    }
                }
            }

            SectionCard(s.sectionAccount, R.drawable.ic_lock) {
                NavRow(R.drawable.ic_logout, s.logout, null, dangerColor(), enabled = loggedIn) { confirmLogout = true }
                NavRow(R.drawable.ic_bug, s.openDebug, s.debugHint, onClick = onDebug)
            }

            SectionCard(s.sectionAbout, R.drawable.ic_info) {
                NavRow(R.drawable.ic_info, APP_NAME, s.version(appVersion())) { showAbout = true }
                val update by Updater.state.collectAsStateWithLifecycle()
                NavRow(
                    R.drawable.ic_refresh, s.checkUpdates,
                    when (update) {
                        Updater.State.CHECKING -> s.updateChecking
                        Updater.State.UP_TO_DATE -> s.updateUpToDate
                        Updater.State.ERROR -> s.updateCheckError
                        Updater.State.IDLE -> null
                    },
                ) { Updater.check() }
            }
            UpdateCard()
        }

        if (showAbout) AboutSheet { showAbout = false }

        if (showTimePicker) {
            val state = rememberTimePickerState(dailyMinutes / 60, dailyMinutes % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = { Text(s.pickTime) },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        BarcodeStore.setDailyMinutes(state.hour * 60 + state.minute)
                        RefreshJobService.schedule(this@SettingsActivity)
                        showTimePicker = false
                    }) { Text(s.ok) }
                },
                dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(s.cancel) } },
            )
        }

        if (confirmLogout) {
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                icon = { Ic(R.drawable.ic_logout) },
                title = { Text(s.logoutTitle) },
                text = { Text(s.logoutText) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout = false
                        OzonClient.logout()
                        BarcodeStore.clear()
                        BarcodeStore.loginNeeded.value = true
                        BarcodeStore.log(s.logLoggedOut)
                        // If the watchapp is open it forgets the code now, else on its next request.
                        BarcodeSync.sendToWatchAsync(this@SettingsActivity)
                        onBack()
                    }) { Text(s.logoutConfirm, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(s.cancel) } },
            )
        }
    }

    /** "About" as a modal bottom sheet: opens fully, closes with a swipe down, a scrim tap or back. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AboutSheet(onClose: () -> Unit) {
        val s = LocalStrings.current
        ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(R.drawable.ic_info)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(APP_NAME, style = MaterialTheme.typography.headlineSmall)
                        Text(s.version(appVersion()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(s.madeWithLove, style = MaterialTheme.typography.titleMedium)
                Text(
                    s.sourceCode(SOURCE_URL.ifEmpty { null }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (SOURCE_URL.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    // GitHub goes to the user's browser; LoginActivity is for Ozon pages.
                    modifier = if (SOURCE_URL.isEmpty()) Modifier
                    else Modifier.clickable { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(s.libraries, style = MaterialTheme.typography.titleSmall)
                s.libList.forEach { Caption("• $it") }
                Spacer(Modifier.height(8.dp))
                Caption(s.license)
            }
        }
    }

    @Composable
    private fun AppearanceSection() {
        val s = LocalStrings.current
        val theme by AppSettings.theme.collectAsStateWithLifecycle()
        val amoled by AppSettings.amoled.collectAsStateWithLifecycle()
        val seed by AppSettings.seed.collectAsStateWithLifecycle()
        val dark = isAppDark(theme)
        var pickCustom by remember { mutableStateOf(false) }
        SectionCard(s.sectionAppearance, R.drawable.ic_palette) {
            Caption(s.themeLabel, SectionInner)
            Segmented(
                listOf(ThemeMode.SYSTEM to s.themeSystem, ThemeMode.LIGHT to s.themeLight, ThemeMode.DARK to s.themeDark),
                theme, AppSettings::setTheme, SectionInner,
                icons = mapOf(ThemeMode.SYSTEM to R.drawable.ic_brightness_auto, ThemeMode.LIGHT to R.drawable.ic_light_mode, ThemeMode.DARK to R.drawable.ic_dark_mode),
            )
            AnimatedVisibility(dark) { SwitchRow(s.amoledLabel, s.amoledHint, amoled, AppSettings::setAmoled) }

            Text(s.colorLabel, style = MaterialTheme.typography.titleSmall, modifier = SectionInner.padding(top = 8.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 4,  // 8 swatches = two even rows
            ) {
                SeedColor.offered.forEach { c ->
                    SeedSwatch(c, c == seed, dark) { if (c == SeedColor.CUSTOM) pickCustom = true else AppSettings.setSeed(c) }
                }
            }
            Caption(s.colorHint, SectionInner)

            Spacer(Modifier.height(8.dp))
        }
        if (pickCustom) CustomColorDialog(dark) { pickCustom = false }
    }

    /** Hue + saturation pickers for the user's own seed, with a live preview of the scheme. */
    @Composable
    private fun CustomColorDialog(dark: Boolean, onClose: () -> Unit) {
        val s = LocalStrings.current
        val start = remember {
            FloatArray(3).also { android.graphics.Color.colorToHSV(AppSettings.customColor.value.toInt(), it) }
        }
        var hue by remember { mutableFloatStateOf(start[0]) }
        var sat by remember { mutableFloatStateOf(start[1].coerceAtLeast(0.15f)) }
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 0.9f)).toLong() and 0xFFFFFFFFL
        val scheme = schemeFor(argb, dark, amoled = false)
        AlertDialog(
            onDismissRequest = onClose,
            icon = { Ic(R.drawable.ic_palette) },
            title = { Text(s.customColorTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) {
                        listOf(
                            scheme.primary to scheme.onPrimary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                            scheme.tertiaryContainer to scheme.onTertiaryContainer,
                        ).zip(listOf(s.rolePrimary, s.roleSecondary, s.roleTertiary)).forEach { (c, name) ->
                            Box(Modifier.weight(1f).height(64.dp).background(c.first).padding(8.dp), contentAlignment = Alignment.BottomStart) {
                                Text(name, color = c.second, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Text(s.hue, style = MaterialTheme.typography.labelLarge)
                    GradientSlider(hue / 360f, { hue = it * 360f }, (0..6).map { Color.hsv(it * 60f % 360f, 1f, 0.9f) }, Color(argb))
                    Text(s.saturation, style = MaterialTheme.typography.labelLarge)
                    GradientSlider((sat - 0.15f) / 0.85f, { sat = 0.15f + it * 0.85f }, listOf(Color.hsv(hue, 0.15f, 0.9f), Color.hsv(hue, 1f, 0.9f)), Color(argb))
                    Text(
                        "#%06X".format(argb and 0xFFFFFF), style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setCustomColor(argb)
                    AppSettings.setSeed(SeedColor.CUSTOM)
                    onClose()
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(s.cancel) } },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GradientSlider(value: Float, onChange: (Float) -> Unit, gradient: List<Color>, thumb: Color) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(16.dp).clip(CircleShape)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(gradient)))
            Slider(
                value = value, onValueChange = onChange,
                colors = SliderDefaults.colors(
                    thumbColor = thumb, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
                thumb = {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(thumb))
                },
                track = { Spacer(Modifier.fillMaxWidth().height(16.dp)) },
            )
        }
    }

    /** Wallpaper-picker style swatch: primary on top, secondary and tertiary below. */
    @Composable
    private fun SeedSwatch(seed: SeedColor, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
        val s = LocalStrings.current
        val scheme = targetColorScheme(seed, dark, amoled = false)
        val source = remember { MutableInteractionSource() }
        val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
        val inner by animateDpAsState(if (selected) 8.dp else 0.dp, label = "inner")
        val cs = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .pressScale(source, 0.9f)
                    .focusRing(source, CircleShape)
                    .clip(CircleShape)
                    .border(ring, cs.primary, CircleShape)
                    .clickable(source, LocalIndication.current, onClick = onClick)
                    .padding(inner)
                    .clip(CircleShape)
                    .drawBehind {
                        val half = size.height / 2
                        drawRect(scheme.primary, size = androidx.compose.ui.geometry.Size(size.width, half))
                        drawRect(scheme.secondaryContainer, androidx.compose.ui.geometry.Offset(0f, half), androidx.compose.ui.geometry.Size(size.width / 2, half))
                        drawRect(scheme.tertiaryContainer, androidx.compose.ui.geometry.Offset(size.width / 2, half), androidx.compose.ui.geometry.Size(size.width / 2, half))
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (seed == SeedColor.WALLPAPER && !selected) Ic(R.drawable.ic_wallpaper, Modifier.size(18.dp), scheme.onPrimary)
                if (seed == SeedColor.CUSTOM && !selected) Ic(R.drawable.ic_palette, Modifier.size(18.dp), scheme.onPrimary)
                androidx.compose.animation.AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Ic(R.drawable.ic_check, Modifier.size(16.dp), scheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                s.seedName(seed), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                color = if (selected) cs.primary else cs.onSurfaceVariant,
            )
        }
    }

    /** Live colour roles of the current scheme (they animate when the seed changes). */
}
