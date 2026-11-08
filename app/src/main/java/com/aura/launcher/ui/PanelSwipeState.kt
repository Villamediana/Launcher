package com.aura.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How open a full-screen panel is, and how a thumb moves it.
 *
 * One number from 0 to 1 that the panel, the screen behind it and the finger
 * dragging it all read, so they cannot disagree.
 *
 * Split in two on purpose. While a finger is down the value is plain state,
 * written straight from the gesture with no coroutine in the way — that is what
 * makes the panel track the thumb instead of chasing it. The [Animatable] only
 * takes over once the finger lifts, to carry the remaining distance.
 *
 * Shared by the app drawer and the settings sheet. They used to be a
 * drag-driven panel and an `AnimatedVisibility`, which is why only one of them
 * could be swiped away.
 */
class PanelSwipeState internal constructor(
    private val settle: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
) {

    /** Set by whoever measures the window; the drag maps onto this. */
    var heightPx by mutableIntStateOf(0)

    private var dragFraction by mutableFloatStateOf(0f)
    private var dragStart by mutableFloatStateOf(0f)
    private var dragging by mutableStateOf(false)

    val progress: Float get() = if (dragging) dragFraction else settle.value

    /** True from the first millimetre of the gesture until it is fully closed. */
    val isOpen: Boolean get() = progress > 0f

    /** True once it has arrived and stopped, which is when focus is safe to take. */
    val isSettledOpen: Boolean get() = !dragging && progress > 0.99f

    fun open() {
        dragging = false
        // Already open and still: the alphabet edge calls this on every letter
        // the thumb crosses, and restarting the spring each time would keep it
        // from ever settling.
        if (settle.value >= 1f && !settle.isRunning) return
        scope.launch { settle.animateTo(1f, SPRING) }
    }

    fun close() {
        dragging = false
        pendingClose = false
        scope.launch { settle.animateTo(0f, SPRING) }
    }

    /**
     * Close once something else covers the screen, not now.
     *
     * For launching an app from the panel: animating it down at the same
     * moment played the home screen underneath before the app arrived — the
     * drawer seemed to give up and go home, and the app came afterwards. Now
     * the panel stays exactly where it is while the app grows over it, and is
     * put away unseen when the launcher stops ([onHidden]). If nothing ever
     * covers it — the launch failed, or opened in a split — it closes normally
     * after a moment instead of staying stuck open.
     */
    fun closeWhenHidden() {
        pendingClose = true
        scope.launch {
            kotlinx.coroutines.delay(HIDDEN_FALLBACK_MS)
            if (pendingClose) close()
        }
    }

    /** Gone, with no animation — for when something else already covers it. */
    fun snapClosed() {
        dragging = false
        pendingClose = false
        scope.launch { settle.snapTo(0f) }
    }

    /** The launcher has gone out of sight: finish any close that was waiting for it. */
    fun onHidden() {
        if (!pendingClose) return
        pendingClose = false
        dragging = false
        scope.launch { settle.snapTo(0f) }
    }

    private var pendingClose = false

    /**
     * A finger has gone down. Returns true when this drag is opening the panel
     * rather than pushing it back down.
     *
     * The distinction matters to the caller: the same entry point handles both,
     * and treating a dismissing drag as a fresh swipe would, for instance, raise
     * a keyboard in front of the panel being dismissed.
     */
    fun beginDrag(): Boolean {
        val opening = settle.value < 0.5f
        scope.launch { settle.stop() }
        dragStart = settle.value
        dragFraction = settle.value
        dragging = true
        return opening
    }

    /** Up is negative and panels rise from the bottom, so this maps one to one. */
    fun dragBy(deltaPx: Float) {
        val height = heightPx.takeIf { it > 0 } ?: return
        dragFraction = (dragFraction - deltaPx / height).coerceIn(0f, 1f)
    }

    fun endDrag(velocityPx: Float) {
        val height = heightPx.takeIf { it > 0 } ?: 1
        val fractionPerSecond = -velocityPx / height
        val travelled = dragFraction - dragStart
        // A flick decides on its own; otherwise it is how far the gesture got
        // from where it started, which reads the same whether you are pulling
        // the panel up or pushing it back down. Either way it never stops half
        // open.
        val target = when {
            fractionPerSecond > FLING_FRACTION_PER_SECOND -> 1f
            fractionPerSecond < -FLING_FRACTION_PER_SECOND -> 0f
            travelled > TRAVEL_THRESHOLD -> 1f
            travelled < -TRAVEL_THRESHOLD -> 0f
            else -> if (dragStart > 0.5f) 1f else 0f
        }
        scope.launch {
            settle.snapTo(dragFraction)
            dragging = false
            settle.animateTo(target, SPRING, initialVelocity = fractionPerSecond)
        }
    }

    private companion object {
        /**
         * Stiff and barely bouncy: it covers the rest of the distance in about a
         * fifth of a second.
         *
         * The first spec here was soft enough that a tap took the better part of
         * a second to settle, and a launcher that slow to open makes the whole
         * phone feel slow.
         */
        val SPRING: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1600f)

        /** How far a slow drag has to travel before letting go commits to it. */
        const val TRAVEL_THRESHOLD = 0.18f

        /** A flick faster than this decides the outcome regardless of distance. */
        const val FLING_FRACTION_PER_SECOND = 1.1f

        /** How long a launch gets to cover the panel before it closes the ordinary way. */
        const val HIDDEN_FALLBACK_MS = 1200L
    }
}

@Composable
fun rememberPanelSwipeState(): PanelSwipeState {
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }
    return remember(scope) { PanelSwipeState(settle, scope) }
}
