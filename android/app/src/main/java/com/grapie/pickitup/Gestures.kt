package com.grapie.pickitup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A column whose rows are reordered by dragging a handle: [row] gets the item and the Modifier to put on its
 * handle. The dragged row follows the finger over the others, which make way as it passes half their height;
 * [onDrop] gets the new order when it changed.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onDrop: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, handle: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val drop by rememberUpdatedState(onDrop)
    var order by remember { mutableStateOf(items) }
    var dragged by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    // Rows that made way slide into their new place from where they were.
    val shifts = remember { mutableStateMapOf<Any, Animatable<Float, *>>() }
    if (dragged == null && settling == null && order != items) order = items

    Column(modifier) {
        order.forEach { item ->
            val k = key(item)
            key(k) {
                val isDragged = dragged == k
                val handle = Modifier.pointerInput(k) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            settling?.cancel(); settling = null
                            dragged = k; offset = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            offset += dy
                            val i = order.indexOfFirst { key(it) == k }
                            val next = if (offset > 0) i + 1 else i - 1
                            val other = order.getOrNull(next) ?: return@detectVerticalDragGestures
                            val ok = key(other)
                            val h = (heights[ok] ?: return@detectVerticalDragGestures).toFloat()
                            if (kotlin.math.abs(offset) > h / 2) {
                                order = order.toMutableList().apply { this[next] = item; this[i] = other }
                                // the dragged row's slot moved by the other's height; keep it under the finger
                                val sign = if (next > i) 1f else -1f
                                offset -= sign * h
                                val mine = (heights[k] ?: 0).toFloat()
                                scope.launch {
                                    val a = Animatable(sign * mine)
                                    shifts[ok] = a
                                    a.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f))
                                    if (shifts[ok] === a) shifts.remove(ok)
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = { finish(scope, offset, { offset = it }) { dragged = null; settling = null }.also { settling = it }; if (order != items) drop(order) },
                        onDragCancel = { finish(scope, offset, { offset = it }) { dragged = null; settling = null }.also { settling = it }; if (order != items) drop(order) },
                    )
                }
                Surface(
                    color = if (isDragged) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent,
                    shadowElevation = if (isDragged) 6.dp else 0.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .onSizeChanged { heights[k] = it.height }
                        .graphicsLayer { translationY = if (isDragged) offset else shifts[k]?.value ?: 0f },
                ) { row(item, handle) }
            }
        }
    }
}

private fun finish(scope: CoroutineScope, from: Float, set: (Float) -> Unit, done: () -> Unit): Job = scope.launch {
    animate(from, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 600f)) { v, _ -> set(v) }
    done()
}

/**
 * Pull to refresh that moves the content itself (as in BooruClient): pulled down at the top, the content comes
 * after the finger with some resistance and a line in the gap says what letting go does. Past [threshold] (px)
 * letting go refreshes: the gap stays while it runs, says it is done, then closes. The content gets it through
 * [connection] and is moved down by [pull].
 */
@Stable
class PullRefresh(private val scope: CoroutineScope, private val threshold: Float, private val onRefresh: () -> Unit) {
    enum class Phase { IDLE, PULLING, REFRESHING, DONE }

    var pull by mutableFloatStateOf(0f)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    /** Off while refreshing makes no sense (logged out). */
    var enabled = true
    private var anim: Job? = null

    val armed: Boolean get() = pull >= threshold

    /** The finger moved by [dy] (down positive); returns how much of it the pull took. */
    private fun dragBy(dy: Float): Float {
        if (dy < 0f) {
            // pushing back up closes the gap before the content scrolls
            if (pull <= 0f || phase != Phase.PULLING) return 0f
            anim?.cancel()
            val used = maxOf(dy, -pull)
            pull += used
            if (pull <= 0f) phase = Phase.IDLE
            return used
        }
        if (!enabled || dy == 0f || (phase != Phase.IDLE && phase != Phase.PULLING)) return 0f
        anim?.cancel()
        phase = Phase.PULLING
        // harder the further it goes
        pull += dy * 0.5f * (1f - (pull / (threshold * 3f)).coerceIn(0f, 0.8f))
        return dy
    }

    /** The finger let go: refresh if pulled far enough, else close. Returns whether it was pulling. */
    private fun letGo(): Boolean {
        if (phase != Phase.PULLING) return false
        if (armed) {
            phase = Phase.REFRESHING
            settle(threshold)
            onRefresh()
        } else {
            phase = Phase.IDLE
            settle(0f)
        }
        return true
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity = if (letGo()) available else Velocity.Zero
    }

    /** The refresh is over: done shows for a moment ([ok]), then the gap closes. */
    fun finished(ok: Boolean) {
        if (phase != Phase.REFRESHING) return
        anim?.cancel()
        anim = scope.launch {
            if (ok) {
                phase = Phase.DONE
                delay(900)
            }
            animate(pull, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 400f)) { v, _ -> pull = v }
            phase = Phase.IDLE
        }
    }

    private fun settle(target: Float) {
        anim?.cancel()
        anim = scope.launch { animate(pull, target, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { v, _ -> pull = v } }
    }
}

/** The line in the gap over the pulled content: what letting go does, then the refresh going on and its end. */
@Composable
fun PullRefreshLine(refresh: PullRefresh, threshold: Float, modifier: Modifier = Modifier) {
    if (refresh.phase == PullRefresh.Phase.IDLE && refresh.pull <= 0f) return
    val s = LocalStrings.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .fillMaxWidth()
            // the gap's height; its content in the middle of it
            .layout { m, c ->
                val h = refresh.pull.roundToInt().coerceAtLeast(0)
                val p = m.measure(Constraints.fixed(c.maxWidth, h))
                layout(c.maxWidth, h) { p.place(0, 0) }
            }
            .graphicsLayer { alpha = (refresh.pull / threshold).coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (refresh.phase) {
                PullRefresh.Phase.REFRESHING -> CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
                PullRefresh.Phase.DONE -> Ic(R.drawable.ic_check, Modifier.size(22.dp), tint = color)
                // the arrow turns over once letting go would refresh
                else -> Ic(
                    R.drawable.ic_arrow_downward, tint = color,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (refresh.armed) 180f else 0f },
                )
            }
            Text(
                when (refresh.phase) {
                    PullRefresh.Phase.REFRESHING -> s.ptrRefreshing
                    PullRefresh.Phase.DONE -> s.ptrDone
                    else -> if (refresh.armed) s.ptrRelease else s.ptrPull
                },
                color = color, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
