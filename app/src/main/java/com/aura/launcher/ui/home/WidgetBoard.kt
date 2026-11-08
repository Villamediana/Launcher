package com.aura.launcher.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aura.launcher.data.HomeWidget
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The home screen's widgets, in the order the user put them.
 *
 * Hold one and it lifts; drag and the others move out of its way; let go and
 * it takes the place it was dropped in. That is the whole of it — widgets
 * could be stacked here for a version, and a home screen that hides one card
 * behind another turned out to be a home screen you have to remember rather
 * than read.
 *
 * The drag never reorders the real list while the finger is down. The lifted
 * widget floats on an offset and everything else slides by exactly its height,
 * animated; the list is rewritten once, on release. Reordering live looks the
 * same for one frame and then fights itself, because the thing under the
 * finger keeps changing index.
 */
@Composable
fun WidgetBoard(
    order: List<HomeWidget>,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onMove: (widget: HomeWidget, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    widget: @Composable (HomeWidget) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // Heights by widget, for working out where a dragged one would land.
    //
    // By widget rather than by position: after a reorder, position 1 is a
    // different widget of a different height, and a map keyed by position
    // hands the drag the old one's number.
    //
    // Deliberately *not* snapshot state. Every measurement used to write into
    // an observable map that the board then read while composing, so each one
    // invalidated all seven rows — and during a drag, with the wallpaper
    // animating underneath, that is the frame budget gone on rebuilding
    // widgets that did not change. A plain map is read by the gesture code,
    // which is not composition at all.
    val heights = remember { HashMap<HomeWidget, Int>() }

    // What the layout actually needs to observe: which widgets drew anything.
    // It changes when media starts or an agenda empties — a few times a day,
    // not a few times a frame.
    var drawn by remember { mutableStateOf(emptySet<HomeWidget>()) }

    var dragging by remember { mutableIntStateOf(-1) }
    // Read only from inside graphicsLayer, which defers it to the draw phase:
    // the lifted widget follows the finger without a recomposition per frame.
    val dragOffset = remember { mutableFloatStateOf(0f) }
    var moveTarget by remember { mutableIntStateOf(-1) }

    val gapPx = with(density) { SLOT_GAP.toPx() }
    val settlePx = with(density) { SETTLE_SLOP.toPx() }

    fun heightOf(index: Int): Int = order.getOrNull(index)?.let { heights[it] } ?: 0

    fun topOf(index: Int): Float {
        var top = 0f
        for (i in 0 until index) {
            val h = heightOf(i)
            if (h > 0) top += h + gapPx
        }
        return top
    }

    fun recomputeTargets() {
        val from = dragging
        if (from < 0) {
            moveTarget = -1
            return
        }
        val size = order.size
        val centre = topOf(from) + heightOf(from) / 2f + dragOffset.floatValue
        var index = 0
        for (j in 0 until size) {
            if (j == from) continue
            if (centre > topOf(j) + heightOf(j) / 2f) index++
        }
        moveTarget = index.coerceIn(0, size - 1)
    }

    Column(modifier) {
        order.forEachIndexed { index, item ->
          // Keyed by the widget, not by its position, so a reorder moves the
          // composition with it instead of handing widget B the state — and
          // the measured height — of whatever used to sit in that slot.
          key(item) {
            val lifted = index == dragging
            val liftedHeight = (order.getOrNull(dragging)?.let { heights[it] } ?: 0).toFloat() + gapPx

            val shift = when {
                dragging < 0 || lifted || moveTarget < 0 -> 0f
                index in (dragging + 1)..moveTarget -> -liftedHeight
                index in moveTarget until dragging -> liftedHeight
                else -> 0f
            }
            val slide by animateFloatAsState(
                targetValue = shift,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
                label = "slide",
            )
            val lift by animateFloatAsState(
                targetValue = if (lifted) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
                label = "lift",
            )
            // The gap belongs to the widget below, and only when there is
            // something above it that actually drew. A hidden media card or an
            // empty agenda is a zero-height row, and spacing them like real
            // ones is what made one pair of widgets look glued and the next
            // pair look abandoned.
            val above = order.take(index).any { it in drawn }
            val gap = if (above && item in drawn) SLOT_GAP else 0.dp

            Box(
                modifier = Modifier
                    .padding(top = gap)
                    .graphicsLayer {
                        translationY = if (lifted) dragOffset.floatValue else slide
                        val scale = 1f + 0.04f * lift
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = 14f * lift
                        alpha = if (lifted) 0.97f else 1f
                    }
                    .zIndex(if (lifted) 1f else 0f)
                    .pointerInput(index, order.size) {
                        holdThenDrag(
                            onArmed = {
                                // Loose, but nothing picked up yet: the press
                                // is still the button's until the finger goes
                                // somewhere.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEditingChange(true)
                            },
                            onStart = {
                                dragging = index
                                dragOffset.floatValue = 0f
                            },
                            onDrag = { delta ->
                                dragOffset.floatValue += delta
                                recomputeTargets()
                            },
                            onEnd = {
                                val from = dragging
                                val to = moveTarget
                                val travelled = abs(dragOffset.floatValue)
                                val carried = order.getOrNull(from)
                                dragging = -1
                                dragOffset.floatValue = 0f
                                moveTarget = -1
                                if (carried == null) return@holdThenDrag
                                // A hold that goes nowhere changes nothing.
                                if (travelled < settlePx) return@holdThenDrag
                                if (to >= 0 && to != from) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onMove(carried, to)
                                }
                            },
                            onCancel = {
                                dragging = -1
                                dragOffset.floatValue = 0f
                                moveTarget = -1
                            },
                        )
                    },
            ) {
                // Measured inside the padding, so a widget that draws nothing
                // measures zero and stops claiming a gap of its own.
                Box(
                    Modifier.onSizeChanged { size ->
                        heights[item] = size.height
                        val has = size.height > 0
                        if (has != (item in drawn)) {
                            drawn = if (has) drawn + item else drawn - item
                        }
                    },
                ) {
                    WidgetSlot(editing = editing, index = index) { widget(item) }
                }
            }
          }
        }
    }
}

/**
 * Waits out a hold, then waits for the finger to actually go somewhere.
 *
 * Three things have to share these pixels: a tap on a button inside the
 * widget, a hold that loosens the board, and a drag that moves the widget.
 * The split is by movement rather than by time, which is what lets all three
 * be true at once —
 *
 *  - move before the hold completes and the gesture is handed back untouched,
 *    so anything underneath still works;
 *  - complete the hold and the board arms: haptic, everything goes loose, and
 *    still nothing is consumed;
 *  - move after that and the widget lifts and follows the finger;
 *  - let go without moving and the board does nothing at all — which leaves
 *    the press to the button that was under the thumb the whole time.
 *
 * That last line is the one that matters. Holding a button a beat too long
 * used to pick the widget up and swallow the press, so play, pause and every
 * dose pill would silently do nothing several times a day.
 */
private suspend fun PointerInputScope.holdThenDrag(
    onArmed: () -> Unit,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        // Taken whether or not anything else has claimed it.
        //
        // This used to bail on a press somebody else had consumed, which
        // sounded careful and was a trapdoor: `Modifier.clickable` consumes
        // its down, so any widget with one anywhere in it quietly lost the
        // ability to be dragged from there — and one with a clickable root
        // lost it entirely. Nothing here needs that rule any more. The board
        // takes nothing until the finger travels, so a press that ends where
        // it started still belongs to whatever was under it, consumed or not.
        val down = awaitFirstDown(requireUnconsumed = false)
        val from = down.position

        // Null means the hold ran its course; false means the finger lifted or
        // travelled first, and the gesture belongs to whatever is underneath.
        val gaveUp = withTimeoutOrNull(HOLD_MS) {
            while (true) {
                val event = awaitPointerEvent()
                val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull true
                if (!change.pressed) return@withTimeoutOrNull true
                // How far the finger is from where it landed — not how far it
                // has travelled getting there.
                //
                // The sum of every step was the bug: a finger resting on glass
                // is never still, and half a second of one-pixel corrections
                // adds up past the slop long before the hold is over. So the
                // hold gave up on itself, every time, and the board could not
                // be armed at all. Displacement does not accumulate: a thumb
                // that wobbles in place stays where it started.
                if ((change.position - from).getDistance() > slop) {
                    return@withTimeoutOrNull true
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (gaveUp == true) return@awaitEachGesture

        // The hold is done: the board goes loose, but the press still belongs
        // to whatever is under it until the finger travels.
        onArmed()

        var lifted = false
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                cancelled = true
                break
            }
            if (!change.pressed) break

            val delta = change.positionChange().y
            // Displacement again, for the same reason: a thumb resting on an
            // armed board would otherwise wobble the widget into the air on
            // its own, without anybody having asked it to move.
            if (!lifted && abs(change.position.y - from.y) > slop) {
                lifted = true
                onStart()
            }
            if (lifted && delta != 0f) {
                change.consume()
                onDrag(delta)
            }
        }
        when {
            !lifted -> Unit
            cancelled -> onCancel()
            else -> onEnd()
        }
    }
}

/**
 * One position on the home screen.
 *
 * Nothing but the widget and, while the board is loose, a slow tilt that says
 * so. Deliberately small: half a degree reads as "loose", three degrees reads
 * as broken.
 */
@Composable
private fun WidgetSlot(
    editing: Boolean,
    index: Int,
    content: @Composable () -> Unit,
) {
    val editWeight by animateFloatAsState(
        targetValue = if (editing) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f),
        label = "editWeight",
    )

    // The tilt only exists while the board is loose. Left running at zero
    // weight it was a frame of animation work every 16ms, all day, to rotate
    // six widgets by nothing at all.
    val tilt = if (editWeight > 0.01f) {
        val wobble = rememberInfiniteTransition(label = "wobble")
        val swing by wobble.animateFloat(
            initialValue = -0.45f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400 + (index % 3) * 90),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tilt",
        )
        swing
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { rotationZ = tilt * editWeight },
    ) {
        content()
    }
}

/**
 * Air between one widget and the next — all of it, everywhere.
 *
 * This is the only vertical margin on the home screen. The widgets used to
 * carry their own as well, in whatever amount each of them had been written
 * with, so the space above a card and the space below it were different
 * numbers and two neighbours could read as glued together while the next pair
 * sat far apart. Twelve, once, from here.
 *
 * It is counted between every pair, including rows that are drawing nothing —
 * a stopped media card or an empty agenda is a zero-height row with a gap on
 * each side — so anything spent here is spent several times over.
 */
private val SLOT_GAP: Dp = 12.dp

/**
 * How long the thumb has to rest before the board takes the gesture.
 *
 * The platform's own long press, near enough. Shorter than this and an
 * unhurried tap on a button inside a widget — the play mark on the feed line,
 * say — lifts the whole widget instead of pressing it.
 */
private const val HOLD_MS = 480L

/** Under this much travel, a drag is a hold that changed its mind. */
private val SETTLE_SLOP: Dp = 12.dp
