package com.aura.launcher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.GestureHelp
import com.aura.launcher.gestures.GestureMatch
import com.aura.launcher.gestures.GestureRunner
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.gestures.matchStrokes
import com.aura.launcher.sketch.SketchHost
import com.aura.launcher.sketch.SketchOpening
import com.aura.launcher.ui.theme.Aura
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Two fingers on the wallpaper, and the screen becomes something to draw on.
 * On Home, a double-tap on empty wallpaper can also arm the same surface for
 * one-finger drawing — useful when the other hand is not free.
 *
 * Two fingers remain the always-available path. One
 * finger dragging up the home screen already means "open the drawer", and a
 * gesture feature that made that unreliable would be a bad trade at any price.
 * Two fingers mean nothing here yet, which is why they need no handle to touch
 * first, no button to arm and no long press to wait through: the shape starts
 * the moment the second finger lands, and that is what makes it feel like
 * something the screen already knew how to do.
 *
 * It sits over the home screen and the app drawer — the two surfaces that are
 * wallpaper and icons rather than a form — and takes nothing until then.
 * Touches are watched on the initial pass, before the children see them, and
 * consumed only once there are two, so every single-finger tap, drag and swipe
 * reaches whatever it was aimed at exactly as before. The drawer's own scroll
 * included.
 *
 * The one-finger path is explicit and temporary: only empty Home wallpaper
 * recognises its double-tap, the armed canvas times out, and the first finished
 * shape returns to normal input. The drawer never installs that activator.
 *
 * The stroke it records is the midpoint between the two fingers. That is the
 * point the hand thinks it is drawing with, and it is also the one that does
 * not jump when the fingers change their spacing mid-shape.
 *
 * Held still instead of drawn, the same touch opens the drawing board out of
 * that point: a ring closes around the finger, and the dark becomes a page.
 * A shape always moves, so the two never meet.
 */
@Composable
fun GestureSurface(
    app: AuraApp,
    /**
     * Whether this surface is on screen and reachable.
     *
     * Not a preference any more — drawing with two fingers is simply what the
     * launcher does, and a switch for it was one more thing to find before a
     * feature could work. This is only about the drawer parked off-screen,
     * where arming a canvas nobody can touch is a subscription paid for
     * nothing.
     */
    enabled: Boolean = true,
    /** Armed by a double-tap on empty home wallpaper. */
    oneFingerMode: Boolean = false,
    onOneFingerModeChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier, content = content)
        return
    }

    val colors = Aura.colors
    val density = LocalDensity.current

    // The list, if a shape has already opened it. It brings its own dark and
    // sits above this one, so from the moment it is up this layer stops
    // painting the veil and only the drawing is left.
    val paletteUp by GestureHelp.showing.collectAsState()

    // Every stroke of the shape. A cross, a letter with a bar, anything that
    // needs the hand to leave the glass and come back is more than one, and the
    // last of them is the one currently under the fingers.
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val points = remember { mutableStateListOf<Offset>() }
    var drawing by remember { mutableStateOf(false) }
    // Bumped whenever a stroke ends. The wait for another one restarts with it,
    // so two strokes in quick succession are one shape and a pause is the end.
    var settled by remember { mutableStateOf(0) }
    var verdict by remember { mutableStateOf<Verdict>(Verdict.None) }
    // Bumped when the first stroke of a shape starts: the wait to see whether
    // the hand is holding still rather than drawing.
    var strokeStarts by remember { mutableIntStateOf(0) }
    var hold by remember { mutableFloatStateOf(0f) }
    // Set once the board has been opened, until every finger is off the glass.
    var handedOff by remember { mutableStateOf(false) }
    val stillness = with(density) { HOLD_SLOP_DP.dp.toPx() }

    // The veil and the light arrive together and leave together, so the whole
    // thing reads as one object appearing rather than three.
    val presence by animateFloatAsState(
        targetValue = if (oneFingerMode || drawing || strokes.isNotEmpty()) 1f else 0f,
        animationSpec = tween(if (drawing) 190 else 320),
        label = "gesturePresence",
    )

    // Noted while the room is dark, so whatever this hands over to can pick the
    // dark up rather than fade it in again. Keyed on an instance of its own:
    // the home screen and the drawer each have one of these layers, and only
    // the one being drawn on should count.
    val veilToken = remember { Any() }
    val veiled = oneFingerMode || drawing || strokes.isNotEmpty()
    DisposableEffect(veiled) {
        if (veiled) GestureVeil.hold(veilToken)
        onDispose { GestureVeil.release(veilToken) }
    }

    // The decision, once the hand has stayed off the glass.
    //
    // Not on the finger lifting, which is where it used to be: lifting ends a
    // stroke, and a shape can have more than one. The pause is what separates
    // "the second half is coming" from "that was all", and it is short enough
    // that a single-stroke gesture still feels immediate.
    LaunchedEffect(settled) {
        if (settled == 0 || drawing || strokes.isEmpty()) return@LaunchedEffect
        delay(NEXT_STROKE_MS)
        if (drawing) return@LaunchedEffect
        val drawn = strokes.map { stroke -> stroke.map { Dot(it.x, it.y) } }
        when (val match = matchStrokes(drawn, app.gestures.gestures.value)) {
            is GestureMatch.Hit -> {
                if (oneFingerMode) onOneFingerModeChange(false)
                app.haptics.captured()
                // Where the hand finished, for anything that wants to appear
                // there rather than in the middle of the screen.
                strokes.lastOrNull()?.lastOrNull()?.let {
                    GestureHelp.noteAnchor(it.x, it.y)
                }
                verdict = Verdict.Hit
                // The application's scope: a gesture that opens another app
                // outlives the screen it was drawn on.
                app.scope.launch { GestureRunner.run(app, match.gesture) }
                // And, separately, the stroke itself — a gesture the hand is
                // drifting away from is one that learns the drift back. Its
                // own coroutine because the run above can take a task's worth
                // of time, and this is a few kilobytes of JSON.
                app.scope.launch {
                    app.gestures.reinforce(
                        match.gesture.id,
                        GestureShape.normaliseStrokes(drawn),
                        match.score,
                    )
                }
            }

            is GestureMatch.Ambiguous, GestureMatch.Unknown -> {
                if (oneFingerMode) onOneFingerModeChange(false)
                app.haptics.missed()
                verdict = Verdict.Miss
            }

            // A two-finger brush, or a pinch that went nowhere. Silence is the
            // right answer to an accident.
            GestureMatch.TooShort -> {
                if (oneFingerMode) onOneFingerModeChange(false)
                strokes.clear()
                points.clear()
                verdict = Verdict.None
            }
        }
    }

    LaunchedEffect(strokeStarts) {
        hold = 0f
        if (strokeStarts == 0) return@LaunchedEffect
        val began = withFrameMillis { it }
        while (true) {
            val t = withFrameMillis { it }
            val first = points.firstOrNull()
            if (!drawing || strokes.isNotEmpty() || first == null ||
                points.any { (it - first).getDistance() > stillness }
            ) {
                hold = 0f
                return@LaunchedEffect
            }
            hold = ((t - began) / HOLD_MS.toFloat()).coerceAtMost(1f)
            if (hold >= 1f) break
        }
        val at = points.first()
        handedOff = true
        drawing = false
        hold = 0f
        points.clear()
        strokes.clear()
        verdict = Verdict.None
        if (oneFingerMode) onOneFingerModeChange(false)
        app.haptics.captured()
        SketchHost.open(SketchOpening(boardId = null, originX = at.x, originY = at.y))
    }

    // Double-tap opens a temporary canvas, not a sticky mode. If the next
    // stroke never comes, return the wallpaper to normal on its own.
    LaunchedEffect(oneFingerMode, drawing, strokes.isNotEmpty()) {
        if (!oneFingerMode || drawing || strokes.isNotEmpty()) return@LaunchedEffect
        delay(ONE_FINGER_IDLE_MS)
        onOneFingerModeChange(false)
    }

    // The ending, held on screen for a beat after the decision. Without it a
    // recognised gesture and a rejected one look the same: the drawing
    // vanishes and something either happens or does not.
    LaunchedEffect(verdict) {
        if (verdict == Verdict.None) return@LaunchedEffect
        delay(if (verdict == Verdict.Hit) 420 else 340)
        strokes.clear()
        points.clear()
        verdict = Verdict.None
    }

    Box(
        modifier
            // On the parent, not on a sibling above the content. The initial
            // pass runs outermost first, which is the only placement that can
            // see a touch before the things underneath it do — and the only one
            // that can then decline to take it.
            .pointerInput(enabled, oneFingerMode) {
                awaitPointerEventScope {
                    var singlePointerId: androidx.compose.ui.input.pointer.PointerId? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.filter { it.pressed }
                        if (handedOff) {
                            // The rest of the touch that opened the board.
                            event.changes.forEach { it.consume() }
                            if (down.isEmpty()) handedOff = false
                            continue
                        }
                        val nextPoint = if (oneFingerMode) {
                            if (!drawing) {
                                down.firstOrNull()?.also { singlePointerId = it.id }?.position
                            } else {
                                down.firstOrNull { it.id == singlePointerId }?.position
                            }
                        } else if (down.size >= 2) {
                            midpoint(down[0].position, down[1].position)
                        } else {
                            null
                        }

                        when {
                            nextPoint != null && !drawing -> {
                                drawing = true
                                // A second stroke joins the shape; a first one
                                // starts a new shape. What tells them apart is
                                // whether the last one has been given up on.
                                if (verdict != Verdict.None) {
                                    strokes.clear()
                                    verdict = Verdict.None
                                }
                                points.clear()
                                points += nextPoint
                                if (strokes.isEmpty()) strokeStarts++
                                down.forEach { it.consume() }
                            }

                            nextPoint != null -> {
                                // Samples on top of each other add nothing to
                                // the shape and cost a resample step each.
                                if (points.lastOrNull()?.let { (nextPoint - it).getDistance() > 4f } != false) {
                                    points += nextPoint
                                }
                                down.forEach { it.consume() }
                            }

                            drawing -> {
                                // A finger lifted. That ends a stroke, not
                                // necessarily the shape — the decision waits a
                                // beat in case the hand is coming back.
                                drawing = false
                                singlePointerId = null
                                event.changes.forEach { it.consume() }
                                if (points.size >= 2) {
                                    strokes += points.toList()
                                } else if (oneFingerMode) {
                                    points.clear()
                                    onOneFingerModeChange(false)
                                }
                                settled++
                            }
                        }
                    }
                }
            },
    ) {
        content()

        if (presence <= 0.001f && verdict == Verdict.None) return@Box

        Canvas(Modifier.fillMaxSize()) {
            val fading = if (drawing) 1f else presence
            val tint = if (verdict == Verdict.Miss) MISS else colors.accent

            // The room going quiet. Handed straight over once the palette is
            // up: it paints the same dark at the same strength, one layer
            // higher, and two of them stacked is a third thing again.
            if (paletteUp == null) {
                drawRect(GestureVeil.COLOUR.copy(alpha = GestureVeil.ALPHA * presence))
            }

            // Every stroke of the shape, finished ones included. A cross
            // whose first line vanished the moment the finger came up would be
            // a cross nobody could see themselves drawing.
            val drawn = strokes.filter { it.size >= 2 } +
                listOf(points.toList()).filter { it.size >= 2 }
            if (drawn.isNotEmpty()) {
                val drift = when (verdict) {
                    Verdict.Hit -> -22f * (1f - presence)
                    Verdict.Miss -> 14f * (1f - presence)
                    Verdict.None -> 0f
                }
                translate(top = drift) {
                    val path = Path()
                    drawn.forEach { stroke ->
                        path.moveTo(stroke.first().x, stroke.first().y)
                        stroke.drop(1).forEach { path.lineTo(it.x, it.y) }
                    }
                    // Four passes: a wide haze, then a bright core. Cheaper
                    // than a blur and it survives hardware rendering.
                    listOf(30f to 0.07f, 18f to 0.14f, 9f to 0.34f, 3.5f to 0.95f)
                        .forEach { (width, alpha) ->
                            drawPath(
                                path,
                                brush = SolidColor(tint.copy(alpha = alpha * fading)),
                                style = Stroke(width = width),
                            )
                        }
                    // The spark at the head, which is what makes it read as
                    // being drawn rather than having appeared. Only while a
                    // finger is actually on the glass.
                    if (drawing) {
                        val head = drawn.last().last()
                        drawCircle(tint.copy(alpha = 0.55f * fading), radius = 22f, center = head)
                        drawCircle(
                            Color(0xFFFFF6EB).copy(alpha = 0.95f * fading),
                            radius = 7f,
                            center = head,
                        )
                    }
                }
            }

            // The board coming: a ring closing around the finger that holds
            // still. Silent for the first moment, so a shape that is about to
            // move never flickers one.
            val shown = ((hold - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val at = points.firstOrNull()
            if (shown > 0f && at != null) {
                val r = 30.dp.toPx()
                drawCircle(colors.accent.copy(alpha = 0.16f * shown), radius = r * (0.6f + 0.4f * shown), center = at)
                drawArc(
                    color = colors.accent.copy(alpha = 0.9f),
                    startAngle = -90f,
                    sweepAngle = 360f * shown,
                    useCenter = false,
                    topLeft = Offset(at.x - r, at.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

private enum class Verdict { None, Hit, Miss }

/**
 * How long the hand may be off the glass before the shape is considered done.
 *
 * The whole of multi-stroke support, and the only number in it. Long enough to
 * put the bar on a letter or cross a cross; short enough that a shape drawn in
 * one go does not sit there waiting to find out whether it was.
 */
private const val NEXT_STROKE_MS = 380L

/** How long an armed one-finger canvas waits for the first stroke. */
private const val ONE_FINGER_IDLE_MS = 1_600L

/** How long a finger holds still on the drawing surface to open the board. */
private const val HOLD_MS = 650L

/** How far a holding finger may wander and still count as still. */
private const val HOLD_SLOP_DP = 10

private fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

/** The same light, gone cold, for a shape that matched nothing. */
private val MISS = Color(0xFF969288)
