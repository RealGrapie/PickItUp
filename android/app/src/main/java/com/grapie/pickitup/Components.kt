package com.grapie.pickitup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Ic(id: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, description: String? = null) =
    Icon(painterResource(id), contentDescription = description, modifier = modifier, tint = tint)

/** Pressed state: shrink a little with a springy release (M3 "pressed" feedback on top of the ripple). */
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Focused state for keyboard / d-pad: an outline ring in the primary colour. */
fun Modifier.focusRing(source: InteractionSource, shape: Shape): Modifier = composed {
    val focused by source.collectIsFocusedAsState()
    val width by animateDpAsState(if (focused) 3.dp else 0.dp, label = "focusRing")
    if (width > 0.dp) border(width, MaterialTheme.colorScheme.primary, shape) else this
}

/** Fully rounded shape that squares off a bit while pressed (shape morph). */
@Composable
fun morphShape(source: InteractionSource, rest: Dp = 28.dp, pressed: Dp = 14.dp): Shape {
    val isPressed by source.collectIsPressedAsState()
    val r by animateDpAsState(if (isPressed) pressed else rest, spring(stiffness = Spring.StiffnessMediumLow), label = "morph")
    return RoundedCornerShape(r)
}

/** Big pill button with an icon, pressed shape morph and scale. */
@Composable
fun PillButton(
    text: String,
    icon: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val shape = morphShape(source)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Ic(icon, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val m = modifier.heightIn(min = 56.dp).pressScale(source)
    if (tonal) FilledTonalButton(onClick, m, enabled, shape = shape, interactionSource = source, content = content)
    else Button(onClick, m, enabled, shape = shape, interactionSource = source, content = content)
}

/** Settings group: a primary-coloured caption and a rounded container. */
@Composable
fun SectionCard(title: String, icon: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Ic(icon, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

/** Padding for plain content inside a [SectionCard] (list items bring their own). */
val SectionInner = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** Round tonal icon badge used as a list leading element. */
@Composable
fun IconBadge(id: Int, container: Color = MaterialTheme.colorScheme.secondaryContainer, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Ic(id, Modifier.size(22.dp), tint)
    }
}

@Composable
fun <T> Choices(options: List<Pair<T, String>>, selected: T?, enabled: Boolean = true, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            FilterChip(
                selected = isSelected,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (isSelected) ({ Ic(R.drawable.ic_check, Modifier.size(18.dp)) }) else null,
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Small status dot that cross-fades its colour. */
@Composable
fun StatusDot(color: Color) {
    val c by animateColorAsState(color, label = "dot")
    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
}

/**
 * Pickup barcode for scanning from the phone screen. Modules are a whole number of pixels
 * (fractional widths stop scanners from reading it) with a white quiet zone of 10+ modules.
 * Always black on white, whatever the theme.
 */
@Composable
fun BarcodeView(code: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(20.dp)) {
    val widths = remember(code) { Code128.encode(code) } ?: return
    val modules = widths.sum()
    Canvas(modifier.clip(shape).background(Color.White)) {
        val scale = maxOf(1, (size.width / (modules + 20)).toInt())
        var x = ((size.width - modules * scale) / 2).toInt().toFloat()
        val top = size.height * 0.12f
        val barHeight = size.height * 0.76f
        widths.forEachIndexed { i, w ->
            val px = (w * scale).toFloat()
            if (i % 2 == 0) drawRect(Color.Black, Offset(x, top), Size(px, barHeight))
            x += px
        }
    }
}

/** A release body split for the update card: [highlight] for the card, [points] for "What's new". */
data class ReleaseNotes(val highlight: String?, val points: List<String>)

/**
 * Release notes in the app's language. The GitHub release body has a Russian and an English
 * section ("## English", then "## Русский"), each a plain highlight line and "- " points, so keep
 * the lines with (RU) or without (EN) Cyrillic, minus headings. Older releases had only points,
 * RU and EN interleaved: then the card shows the first point. Empty results fall back to all lines.
 */
fun localizedNotes(notes: String, russian: Boolean): ReleaseNotes {
    val cyrillic = Regex("[\u0400-\u04FF]")
    val lines = notes.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .filterNot { it.startsWith("#") || (it.endsWith(":") && "What's new" in it) }
    val picked = lines.filter { cyrillic.containsMatchIn(it) == russian }.ifEmpty { lines }
    val bullet = Regex("^[-*•]\\s+")
    val (points, plain) = picked.partition { bullet.containsMatchIn(it) }
    fun clean(t: String) = t.replace(bullet, "").replace("**", "").replace("__", "").trim()
    return ReleaseNotes(plain.firstOrNull()?.let(::clean), (points.ifEmpty { plain.drop(1) }).map(::clean))
}

/**
 * "Version X is available": a teaser line, "What's new" (a bottom sheet) and "Update".
 * [skippable] (main screen): a close button skips this version, and the card follows the
 * "check automatically" switch. Settings always show it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateCard(modifier: Modifier = Modifier, skippable: Boolean = false) {
    val s = LocalStrings.current
    val release by Updater.available.collectAsStateWithLifecycle()
    val progress by Updater.progress.collectAsStateWithLifecycle()
    val failed by Updater.failed.collectAsStateWithLifecycle()
    val installing by Updater.installing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val skipped by Updater.skipped.collectAsStateWithLifecycle()
    val autoCheck by Updater.autoCheck.collectAsStateWithLifecycle()
    var showNotes by remember { mutableStateOf(false) }
    val r = release ?: return
    if (skippable && (!autoCheck || r.version == skipped)) return
    val notes = localizedNotes(r.notes, s === Strings.RU)
    val teaser = notes.highlight ?: notes.points.firstOrNull()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = MaterialTheme.colorScheme.onPrimaryContainer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.updateAvailable(r.version), style = MaterialTheme.typography.titleMedium, color = on, modifier = Modifier.weight(1f))
                if (skippable) IconButton({ Updater.skip(r.version) }, Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_close), s.skipUpdate, tint = on)
                }
            }
            teaser?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = on, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Buttons stay: if the system dialog was missed, "Update" brings it back (same file).
            if (installing) Text(s.updateInstalling, style = MaterialTheme.typography.labelLarge, color = on)
            if (failed) Text(s.updateFailed, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            val p = progress
            if (p != null) {
                Text(s.updateDownloading, style = MaterialTheme.typography.labelLarge, color = on)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notes.points.isNotEmpty()) PillButton(s.whatsNew, null, { showNotes = true }, Modifier.weight(1f), tonal = true)
                    PillButton(s.updateAction, R.drawable.ic_download, { Updater.install(context) }, Modifier.weight(1f))
                }
            }
        }
    }
    if (showNotes) {
        // Same style as "About": opens fully, closes with a swipe down, a scrim tap or back.
        ModalBottomSheet(onDismissRequest = { showNotes = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(s.whatsNewIn(r.version), style = MaterialTheme.typography.headlineSmall)
                notes.highlight?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                notes.points.forEach { line ->
                    Row {
                        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(20.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PillButton(s.updateAction, R.drawable.ic_download, { showNotes = false; Updater.install(context) }, Modifier.fillMaxWidth())
            }
        }
    }
}
