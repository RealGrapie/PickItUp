package com.grapie.pickitup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val SplashBlue = Color(0xFF005BFF)  // = ic_launcher_background in PickItUp and the system splash

/**
 * Picks up where the system splash (blue + launcher glyph) leaves off: a scanner beam runs over
 * the parcel's barcode label, the lid hops, the name fades in, then a circle opens onto the app.
 * Easter egg: tap the parcel while the splash is up and its lid flips open, a tiny pixel
 * Pebble 2 Duo peeks out, looks around, blinks and smiles, then hides and the splash carries on.
 */
@Composable
fun SplashOverlay(tagline: String, onFinished: () -> Unit) {
    val scan = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val egg = remember { EggState() }
    var eggStarted by remember { mutableStateOf(false) }
    var eggDone by remember { mutableStateOf(false) }
    var revealing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { delay(120); scan.animateTo(1f, tween(620, easing = LinearEasing)) }
        launch { delay(180); text.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        launch {
            delay(640)
            if (eggStarted) return@launch
            hop.animateTo(1f, tween(110))
            hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        delay(1050)
        // The egg (if started) plays to the end before the app shows up.
        while (eggStarted && !eggDone) delay(16)
        revealing = true
        reveal.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        onFinished()
    }
    LaunchedEffect(eggStarted) {
        if (!eggStarted) return@LaunchedEffect
        hop.snapTo(0f)
        egg.play()
        eggDone = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(SplashBlue)
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center, size.minDimension * 0.6f))
            val r = reveal.value * hypot(size.width, size.height) / 2
            if (r > 0) drawCircle(Color.Black, r, center, blendMode = BlendMode.Clear)
        }
        val fade = 1f - (reveal.value * 2.5f).coerceAtMost(1f)
        Canvas(
            Modifier
                .size(288.dp)
                .graphicsLayer {
                    val s = 1f + reveal.value * 0.35f
                    scaleX = s; scaleY = s; alpha = fade
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        // Only the parcel itself (32..76 x 31..76 on the 108-unit canvas) hides the egg.
                        val u = size.width / 108f
                        val inBox = p.x / u in 30f..78f && p.y / u in 28f..78f
                        if (inBox && !eggStarted && !revealing) eggStarted = true
                    }
                },
        ) { drawParcel(scan.value, hop.value, egg) }
        Column(
            Modifier.align(Alignment.Center).padding(top = 230.dp).graphicsLayer {
                alpha = text.value * fade
                translationY = (1f - text.value) * 24.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(APP_NAME, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                tagline, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}

/** Animation state of the easter egg. */
private class EggState {
    val lid = Animatable(0f)      // 0 closed .. 1 open (flipped over its right hinge)
    val rise = Animatable(0f)     // 0 hidden in the box .. 1 peeking out
    val tilt = Animatable(0f)     // degrees, looking left / right
    var blink by mutableStateOf(false)
    var happy by mutableStateOf(false)

    suspend fun play() = coroutineScope {
        launch { lid.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
        delay(160)
        rise.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f))
        delay(150)
        tilt.animateTo(-9f, tween(260, easing = FastOutSlowInEasing))
        tilt.animateTo(9f, tween(420, easing = FastOutSlowInEasing))
        blink = true; delay(110); blink = false
        tilt.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 300f))
        happy = true
        delay(650)
        blink = true; delay(110); blink = false
        delay(200)
        happy = false
        rise.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        lid.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }
}

// Pebble 2 Duo (black), 15x19 pixels like the real one: a strap narrower than the body, a
// black bezel round an e-paper screen, a chin under it, BACK on the left and UP / SELECT / DOWN
// on the right. S strap, B body, K bezel, U buttons, W screen, F face.
private val PEBBLE = listOf(
    "...SSSSSSSSS...",
    "...SSSSSSSSS...",
    "...SSSSSSSSS...",
    ".BBBBBBBBBBBBB.",
    ".BKKKKKKKKKKKB.",
    ".BKWWWWWWWWWKB.",
    "UBKWWWWWWWWWKBU",
    "UBKWWWWWWWWWKBU",
    ".BKWWFWWWFWWKB.",
    ".BKWWFWWWFWWKBU",
    ".BKWWWWWWWWWKBU",
    ".BKWWFWWWFWWKB.",
    ".BKWWWFFFWWWKBU",
    ".BKWWWWWWWWWKBU",
    ".BKKKKKKKKKKKB.",
    ".BBBBBBBBBBBBB.",
    ".BBBBBBBBBBBBB.",
    "...SSSSSSSSS...",
    "...SSSSSSSSS...",
)
private val EYES_BLINK = listOf(".BKWWWWWWWWWKB.", ".BKWFFWWWFFWKBU")
private val EYES_HAPPY = listOf(".BKWWFWWWFWWKB.", ".BKWFWFWFWFWKBU")
private const val PX = 1.35f  // one watch pixel in parcel units (the parcel is 44 wide)
private const val COLS = 15

private fun DrawScope.drawPebble(egg: EggState, left: Float, top: Float) {
    val rows = PEBBLE.toMutableList()
    when {
        egg.blink -> { rows[8] = EYES_BLINK[0]; rows[9] = EYES_BLINK[1] }
        egg.happy -> { rows[8] = EYES_HAPPY[0]; rows[9] = EYES_HAPPY[1] }
    }
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, c ->
            val color = when (c) {
                'S' -> Color(0xFF34343A)
                'B' -> Color(0xFF1E1E22)
                'K' -> Color(0xFF0A0A0C)
                'U' -> Color(0xFF141417)
                'W' -> Color(0xFFD9DDE3)
                'F' -> Color(0xFF111114)
                else -> null
            } ?: return@forEachIndexed
            // A hair of overlap so the pixels don't show seams when scaled.
            drawRect(color, Offset(left + x * PX, top + y * PX), Size(PX + 0.05f, PX + 0.05f))
        }
    }
}

/** The launcher glyph (ic_launcher_foreground, 108-unit canvas) with the scanner beam and the egg. */
private fun DrawScope.drawParcel(scan: Float, lidHop: Float, egg: EggState) {
    val u = size.width / 108f
    withTransform({ scale(u, u, Offset.Zero) }) {
        // The watch lives inside the box: only what is above the box's top edge shows.
        val rise = egg.rise.value
        if (rise > 0.01f) clipRect(0f, -200f, 108f, 41.8f) {
            val w = COLS * PX
            val left = 54f - w / 2
            // Peeks out up to the chin: the strap, the bezel and the face show above the box.
            val top = 44f - rise * 22.5f
            rotate(egg.tilt.value, Offset(54f, top + 16 * PX)) { drawPebble(egg, left, top) }
        }
        val box = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(32f, 41.8f); lineTo(76f, 41.8f); lineTo(76f, 73f)
            quadraticTo(76f, 76f, 73f, 76f); lineTo(35f, 76f); quadraticTo(32f, 76f, 32f, 73f); close()
            addRoundRect(RoundRect(39f, 50f, 69f, 69f, CornerRadius(2f)))
        }
        drawPath(box, Color.White)
        val bars = listOf(43f to 46f, 47f to 49f, 50f to 54f, 55f to 57f, 59f to 62f, 63f to 65f)
        val beamX = 37f + scan * 34f
        val scanning = scan in 0.001f..0.999f
        bars.forEach { (l, r) ->
            val d = kotlin.math.abs((l + r) / 2 - beamX)
            val a = if (scanning) (0.45f + d / 8f).coerceIn(0.45f, 1f) else 1f
            drawRect(Color.White.copy(alpha = a), Offset(l, 53f), Size(r - l, 13f))
        }
        if (scanning) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFFF4D6D).copy(alpha = 0.9f), Color.Transparent), beamX - 3f, beamX + 3f),
                Offset(beamX - 3f, 50.5f), Size(6f, 18f),
            )
            drawLine(Color(0xFFFF4D6D), Offset(beamX, 50f), Offset(beamX, 69f), strokeWidth = 0.7f)
        }
        // Lid: hops on the normal splash, flips open over its right hinge for the egg. The closing
        // spring overshoots below 0: clamp it so the lid doesn't sink into the box.
        translate(top = -3.5f * lidHop) {
            rotate(egg.lid.value.coerceAtLeast(0f) * 118f, Offset(76f, 40.2f)) {
                drawPath(Path().apply { moveTo(32f, 40.2f); lineTo(40f, 31f); lineTo(68f, 31f); lineTo(76f, 40.2f); close() }, Color.White)
            }
        }
    }
}
