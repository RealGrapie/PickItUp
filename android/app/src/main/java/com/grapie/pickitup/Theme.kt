package com.grapie.pickitup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Seed colours offered in settings; [WALLPAPER] is Material You, [CUSTOM] user-picked. */
enum class SeedColor(val argb: Long) {
    OZON(0xFF005BFF),
    VIOLET(0xFF6750A4),
    TEAL(0xFF00897B),
    GREEN(0xFF386A20),
    ORANGE(0xFFE8710A),
    RASPBERRY(0xFFD81B60),
    WALLPAPER(0xFF005BFF),
    /** The user's own colour, [AppSettings.customColor]. */
    CUSTOM(0xFF7C4DFF);

    companion object {
        val offered get() = entries
    }
}

/**
 * Tonal palette of one hue/chroma: tone 0 = black, 100 = white, tone = CIE L* (like M3's HCT).
 * Hue and chroma live in OKLCH, which keeps blues blue when lightened (plain Lab turns them
 * purple); chroma is reduced per tone until the colour fits sRGB.
 */
class TonalPalette(private val hue: Double, private val chroma: Double) {
    private val cache = HashMap<Int, Color>()
    fun tone(t: Int): Color = cache.getOrPut(t) { Oklch.toneColor(hue, chroma, t.toDouble()) }

    companion object {
        val TONES = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)
    }
}

/** Primary / secondary / tertiary / neutral / neutral-variant / error palettes from one seed. */
class CorePalettes(seed: Color) {
    val seedHue: Double
    val primary: TonalPalette
    val secondary: TonalPalette
    val tertiary: TonalPalette
    val neutral: TonalPalette
    val neutralVariant: TonalPalette
    val error = TonalPalette(29.0, 0.19)

    init {
        val (_, c, h) = Oklch.fromColor(seed)
        seedHue = h
        // "Tonal spot": a calm primary, low-chroma secondary, tertiary rotated by 60 degrees.
        primary = TonalPalette(h, c.coerceIn(0.09, 0.16))
        secondary = TonalPalette(h, 0.045)
        tertiary = TonalPalette((h + 60) % 360, 0.075)
        neutral = TonalPalette(h, 0.008)
        neutralVariant = TonalPalette(h, 0.02)
    }

    fun scheme(dark: Boolean, amoled: Boolean): ColorScheme {
        val p = primary; val s = secondary; val t = tertiary; val n = neutral; val nv = neutralVariant; val e = error
        return if (!dark) lightColorScheme(
            primary = p.tone(40), onPrimary = p.tone(100), primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
            inversePrimary = p.tone(80),
            secondary = s.tone(40), onSecondary = s.tone(100), secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
            tertiary = t.tone(40), onTertiary = t.tone(100), tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
            background = n.tone(98), onBackground = n.tone(10),
            surface = n.tone(98), onSurface = n.tone(10),
            surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
            surfaceTint = p.tone(40),
            inverseSurface = n.tone(20), inverseOnSurface = n.tone(95),
            error = e.tone(40), onError = e.tone(100), errorContainer = e.tone(90), onErrorContainer = e.tone(10),
            outline = nv.tone(50), outlineVariant = nv.tone(80), scrim = Color.Black,
            surfaceBright = n.tone(98), surfaceDim = n.tone(87),
            surfaceContainerLowest = n.tone(100), surfaceContainerLow = n.tone(96), surfaceContainer = n.tone(94),
            surfaceContainerHigh = n.tone(92), surfaceContainerHighest = n.tone(90),
        ) else darkColorScheme(
            primary = p.tone(80), onPrimary = p.tone(20), primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
            inversePrimary = p.tone(40),
            secondary = s.tone(80), onSecondary = s.tone(20), secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
            tertiary = t.tone(80), onTertiary = t.tone(20), tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
            background = if (amoled) Color.Black else n.tone(6), onBackground = n.tone(90),
            surface = if (amoled) Color.Black else n.tone(6), onSurface = n.tone(90),
            surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80),
            surfaceTint = p.tone(80),
            inverseSurface = n.tone(90), inverseOnSurface = n.tone(20),
            error = e.tone(80), onError = e.tone(20), errorContainer = e.tone(30), onErrorContainer = e.tone(90),
            outline = nv.tone(60), outlineVariant = nv.tone(30), scrim = Color.Black,
            surfaceBright = n.tone(24), surfaceDim = if (amoled) Color.Black else n.tone(6),
            surfaceContainerLowest = if (amoled) Color.Black else n.tone(4),
            surfaceContainerLow = n.tone(if (amoled) 4 else 10),
            surfaceContainer = n.tone(if (amoled) 6 else 12),
            surfaceContainerHigh = n.tone(if (amoled) 10 else 17),
            surfaceContainerHighest = n.tone(if (amoled) 15 else 22),
        )
    }
}

/** sRGB <-> OKLCH, plus "this hue/chroma at CIE L* = tone". */
object Oklch {
    data class Lch(val l: Double, val c: Double, val h: Double)

    private fun toLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun toGamma(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    fun fromColor(color: Color): Lch {
        val r = toLinear(color.red.toDouble()); val g = toLinear(color.green.toDouble()); val b = toLinear(color.blue.toDouble())
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val h = Math.toDegrees(atan2(okB, okA)).let { if (it < 0) it + 360 else it }
        return Lch(okL, hypot(okA, okB), h)
    }

    /** Linear sRGB of an OKLCH colour (may be out of gamut). */
    private fun linear(l: Double, c: Double, hDeg: Double): DoubleArray {
        val h = Math.toRadians(hDeg)
        val a = c * cos(h); val b = c * sin(h)
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
        )
    }

    private fun luminance(rgb: DoubleArray) = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    private fun inGamut(rgb: DoubleArray) = rgb.all { it in -1e-4..1.0001 }
    private fun yFromTone(t: Double) = if (t > 8) ((t + 16) / 116).pow(3) else t / 903.2963

    /** OKLCH lightness with luminance [y] for this chroma/hue (luminance grows with lightness). */
    private fun lightnessFor(y: Double, c: Double, h: Double): DoubleArray {
        var lo = 0.0; var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2
            if (luminance(linear(mid, c, h)) < y) lo = mid else hi = mid
        }
        return linear((lo + hi) / 2, c, h)
    }

    fun toneColor(h: Double, c: Double, tone: Double): Color {
        if (tone <= 0.0) return Color.Black
        if (tone >= 100.0) return Color.White
        val y = yFromTone(tone)
        var rgb = lightnessFor(y, c, h)
        if (!inGamut(rgb)) {
            // Largest chroma that still fits sRGB at this tone.
            var lo = 0.0; var hi = c
            repeat(16) {
                val mid = (lo + hi) / 2
                if (inGamut(lightnessFor(y, mid, h))) lo = mid else hi = mid
            }
            rgb = lightnessFor(y, lo, h)
        }
        fun ch(v: Double) = toGamma(v.coerceIn(0.0, 1.0)).toFloat()
        return Color(ch(rgb[0]), ch(rgb[1]), ch(rgb[2]))
    }
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Whether the app is dark right now (theme setting, or the system for SYSTEM). */
@Composable
fun isAppDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun targetColorScheme(seed: SeedColor, dark: Boolean, amoled: Boolean): ColorScheme {
    val context = LocalContext.current
    if (seed == SeedColor.WALLPAPER) {
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return if (dark && amoled) base.copy(
            background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
        ) else base
    }
    val custom by AppSettings.customColor.collectAsStateWithLifecycle()
    val argb = if (seed == SeedColor.CUSTOM) custom else seed.argb
    return schemeFor(argb, dark, amoled)
}

@Composable
fun schemeFor(argb: Long, dark: Boolean, amoled: Boolean): ColorScheme {
    val palettes = remember(argb) { CorePalettes(Color(argb)) }
    return remember(argb, dark, amoled) { palettes.scheme(dark, amoled) }
}

/** Every role cross-fades when the seed or the light/dark mode changes. */
@Composable
private fun animate(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 450)
    @Composable fun a(c: Color): Color = animateColorAsState(c, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        inversePrimary = a(target.inversePrimary),
        secondary = a(target.secondary), onSecondary = a(target.onSecondary),
        secondaryContainer = a(target.secondaryContainer), onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary), onTertiary = a(target.onTertiary),
        tertiaryContainer = a(target.tertiaryContainer), onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background), onBackground = a(target.onBackground),
        surface = a(target.surface), onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant), onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceTint = a(target.surfaceTint),
        inverseSurface = a(target.inverseSurface), inverseOnSurface = a(target.inverseOnSurface),
        error = a(target.error), onError = a(target.onError),
        errorContainer = a(target.errorContainer), onErrorContainer = a(target.onErrorContainer),
        outline = a(target.outline), outlineVariant = a(target.outlineVariant),
        surfaceBright = a(target.surfaceBright), surfaceDim = a(target.surfaceDim),
        surfaceContainerLowest = a(target.surfaceContainerLowest), surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainer = a(target.surfaceContainer), surfaceContainerHigh = a(target.surfaceContainerHigh),
        surfaceContainerHighest = a(target.surfaceContainerHighest),
    )
}

@Composable
fun PickItUpTheme(content: @Composable () -> Unit) {
    val mode by AppSettings.theme.collectAsStateWithLifecycle()
    val seed by AppSettings.seed.collectAsStateWithLifecycle()
    val amoled by AppSettings.amoled.collectAsStateWithLifecycle()
    val dark = isAppDark(mode)
    MaterialTheme(colorScheme = animate(targetColorScheme(seed, dark, amoled)), shapes = AppShapes, content = content)
}
