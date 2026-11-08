package com.aura.launcher.ui.sketch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.sketch.Board
import com.aura.launcher.sketch.Box as BoardBox
import com.aura.launcher.sketch.Camera
import com.aura.launcher.sketch.Element
import com.aura.launcher.sketch.SketchOpening
import com.aura.launcher.sketch.SketchPainter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The drawing board: the gesture veil's dark, made into a page with no edges.
 *
 * Everything is the hand. One finger draws. Two fingers move and zoom the
 * page. A double tap on something picks it up — then one finger carries it,
 * and carrying it onto the mark at the bottom puts it away. The only thing on
 * screen that is not the drawing is a small × to leave.
 *
 * Drawing is only drawing. Circling something asks the model chosen in
 * settings to make it into what it meant (see [SketchSession]).
 */
@Composable
fun SketchScreen(app: AuraApp, opening: SketchOpening, onClosed: () -> Unit) {
    val board by produceState<Board?>(null, opening) {
        value = opening.boardId?.let { app.boards.load(it) } ?: app.boards.fresh()
    }
    val loaded = board ?: return
    // The in-app language lives on this context, not on the application's.
    val context = androidx.compose.ui.platform.LocalContext.current
    val ink = com.aura.launcher.ui.theme.Aura.colors.accent.toArgb()
    val session = remember(loaded.id) { SketchSession(context, app.scope, app.boards, app.sketchModel, loaded, ink) }
    SketchBoard(app, session, opening, onClosed)
}

private enum class Hand { UNDECIDED, DRAWING, MOVING, VIEW }

@Composable
private fun SketchBoard(app: AuraApp, session: SketchSession, opening: SketchOpening, onClosed: () -> Unit) {
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Opening out of the point the hand held, closing back into the dark.
    val reveal = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
    val fade by animateFloatAsState(if (closing) 0f else 1f, tween(240), label = "sketchFade") {
        if (closing) onClosed()
    }
    fun close() {
        if (closing) return
        closing = true
        session.close()
        app.scope.launch { session.save(thumbnail = true) }
    }
    BackHandler { close() }
    DisposableEffect(session) { onDispose { session.close() } }


    // The stroke under the finger, in board coordinates.
    val stroke = remember { ArrayList<Float>(512) }
    var strokeVersion by remember { mutableIntStateOf(0) }
    var hand by remember { mutableStateOf(Hand.UNDECIDED) }
    var carryAt by remember { mutableStateOf(Offset.Unspecified) }

    // Frames only while something is moving on its own.
    var frame by remember { mutableLongStateOf(0L) }
    val animating by remember {
        derivedStateOf { session.working.isNotEmpty() || session.arrivals.isNotEmpty() || session.departures.isNotEmpty() }
    }
    LaunchedEffect(animating) {
        while (animating) {
            withFrameMillis { }
            frame = session.now()
            session.arrivals.entries.filter { frame - it.value > ARRIVE_GLOW_MS }.forEach { session.arrivals.remove(it.key) }
            session.departures.entries.filter { frame - it.value > LEAVE_MS }.forEach { session.departures.remove(it.key) }
        }
    }


    val trashCentre = with(density) { Offset(size.width / 2f, size.height - 104.dp.toPx()) }
    val trashReach = with(density) { 58.dp.toPx() }
    val overTrash = hand == Hand.MOVING && carryAt != Offset.Unspecified && (carryAt - trashCentre).getDistance() < trashReach

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer { alpha = fade }
            .drawWithContent {
                val origin = if (opening.originX >= 0f) Offset(opening.originX, opening.originY) else center
                val far = maxOf(
                    hypot(origin.x, origin.y), hypot(this.size.width - origin.x, origin.y),
                    hypot(origin.x, this.size.height - origin.y), hypot(this.size.width - origin.x, this.size.height - origin.y),
                )
                if (reveal.value >= 1f) drawContent()
                else clipPath(Path().apply { addOval(Rect(origin, far * reveal.value)) }) { this@drawWithContent.drawContent() }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(session) {
                    val slop = viewConfiguration.touchSlop
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero
                    var pendingDot: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val cam = session.camera
                        fun board(p: Offset) = Offset(p.x / session.camera.zoom + session.camera.x, p.y / session.camera.zoom + session.camera.y)
                        val startOnBoard = board(start)
                        val held = session.selected?.let { id -> session.elements.firstOrNull { it.id == id } }
                        val onHeld = held != null && held.bounds.inflate(24f / cam.zoom).contains(startOnBoard.x, startOnBoard.y)
                        hand = Hand.UNDECIDED
                        var last = start
                        var upAt = down.uptimeMillis
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                upAt = event.changes.firstOrNull()?.uptimeMillis ?: upAt
                                break
                            }
                            if (pressed.size >= 2) {
                                if (hand == Hand.DRAWING) {
                                    stroke.clear(); strokeVersion++
                                }
                                if (hand == Hand.MOVING) session.moved()
                                hand = Hand.VIEW
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val c = session.camera
                                val nextZoom = (c.zoom * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val anchorX = centroid.x / c.zoom + c.x
                                val anchorY = centroid.y / c.zoom + c.y
                                session.camera = Camera(
                                    x = anchorX - centroid.x / nextZoom - pan.x / nextZoom,
                                    y = anchorY - centroid.y / nextZoom - pan.y / nextZoom,
                                    zoom = nextZoom,
                                )
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val p = pressed[0]
                            p.consume()
                            if (hand == Hand.VIEW) continue
                            if (hand == Hand.UNDECIDED && (p.position - start).getDistance() > slop * 0.5f) {
                                pendingDot?.cancel()
                                if (onHeld) {
                                    hand = Hand.MOVING
                                } else {
                                    hand = Hand.DRAWING
                                    session.selected = null
                                    stroke.clear()
                                    stroke += startOnBoard.x; stroke += startOnBoard.y
                                }
                            }
                            when (hand) {
                                Hand.DRAWING -> if ((p.position - last).getDistance() > 1.5f) {
                                    val b = board(p.position)
                                    stroke += b.x; stroke += b.y
                                    strokeVersion++
                                    last = p.position
                                }
                                Hand.MOVING -> {
                                    val z = session.camera.zoom
                                    session.move(held!!.id, (p.position.x - last.x) / z, (p.position.y - last.y) / z)
                                    carryAt = p.position
                                    last = p.position
                                }
                                else -> Unit
                            }
                        }

                        when (hand) {
                            Hand.DRAWING -> {
                                if (stroke.size >= 4) {
                                    session.addStroke(
                                        Element.Ink(session.newId(), stroke.toFloatArray(), SketchPainter.PEN_WIDTH / session.camera.zoom),
                                    )
                                }
                                stroke.clear(); strokeVersion++
                            }
                            Hand.MOVING -> {
                                val bin = Offset(this.size.width / 2f, this.size.height - 104.dp.toPx())
                                if ((carryAt - bin).getDistance() < 58.dp.toPx()) {
                                    app.haptics.missed()
                                    session.delete(held!!.id)
                                } else {
                                    session.moved()
                                }
                                carryAt = Offset.Unspecified
                            }
                            Hand.UNDECIDED -> if (upAt - down.uptimeMillis < TAP_MS) {
                                if (down.uptimeMillis - lastTapAt < DOUBLE_TAP_MS && (start - lastTapPos).getDistance() < 64f) {
                                    // A double tap: pick up what is under it, or let go.
                                    pendingDot?.cancel()
                                    lastTapAt = 0L
                                    val hit = session.hit(startOnBoard.x, startOnBoard.y, 18f / cam.zoom)
                                    session.selected = if (hit == null || hit.id == session.selected) null else hit.id
                                    if (hit != null) app.haptics.captured()
                                } else {
                                    lastTapAt = down.uptimeMillis
                                    lastTapPos = start
                                    if (session.selected != null) {
                                        session.selected = null
                                    } else {
                                        // A dot — unless this is the first half of a double tap.
                                        val width = SketchPainter.PEN_WIDTH / cam.zoom
                                        pendingDot = scope.launch {
                                            delay(DOUBLE_TAP_MS)
                                            session.addStroke(Element.Ink(session.newId(), floatArrayOf(startOnBoard.x, startOnBoard.y), width))
                                        }
                                    }
                                }
                            }
                            Hand.VIEW -> session.moved()
                        }
                        hand = Hand.UNDECIDED
                    }
                },
        ) {
            frame; strokeVersion
            val c = session.camera
            drawRect(Color(SketchPainter.GROUND))
            dotGrid(c)
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val save = nc.save()
                nc.scale(c.zoom, c.zoom)
                nc.translate(-c.x, -c.y)
                val unit = 1f / c.zoom
                val now = session.now()
                val painter = session.painter

                // What was circled, breathing while the model works on it.
                session.working.forEach { (_, circled) ->
                    session.elements.filter { it.id in circled }.map { it.bounds }.reduceOrNull(BoardBox::union)?.let {
                        val breath = 0.45f + 0.35f * sin(now / 260f)
                        painter.glow(nc, it, breath, unit)
                    }
                }
                session.departures.forEach { (e, t) ->
                    painter.draw(nc, e, (1f - (now - t) / LEAVE_MS.toFloat()).coerceIn(0f, 1f))
                }
                session.elements.forEach { e ->
                    val arrived = session.arrivals[e.id]
                    if (arrived != null) {
                        val age = (now - arrived).toFloat()
                        painter.glow(nc, e.bounds, (1f - age / ARRIVE_GLOW_MS).coerceIn(0f, 1f), unit)
                        painter.draw(nc, e, (age / ARRIVE_MS).coerceIn(0f, 1f))
                    } else {
                        painter.draw(nc, e)
                    }
                    if (e.id == session.selected) painter.glow(nc, e.bounds, if (overTrash) 0.4f else 1f, unit)
                }
                if (stroke.size >= 2) {
                    painter.draw(nc, Element.Ink("", stroke.toFloatArray(), SketchPainter.PEN_WIDTH / c.zoom))
                    painter.spark(nc, stroke[stroke.size - 2], stroke[stroke.size - 1], unit)
                }
                nc.restoreToCount(save)
            }
        }

        // Where a carried thing is put away.
        AnimatedVisibility(
            visible = hand == Hand.MOVING,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.matchParentSize(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = (if (overTrash) 30.dp else 22.dp).toPx()
                drawCircle(Color(SketchPainter.INK).copy(alpha = if (overTrash) 0.16f else 0.07f), r, trashCentre)
                val arm = 7.dp.toPx()
                val ink = Color(SketchPainter.INK).copy(alpha = if (overTrash) 0.9f else 0.5f)
                drawLine(ink, trashCentre + Offset(-arm, -arm), trashCentre + Offset(arm, arm), 1.6.dp.toPx(), StrokeCap.Round)
                drawLine(ink, trashCentre + Offset(arm, -arm), trashCentre + Offset(-arm, arm), 1.6.dp.toPx(), StrokeCap.Round)
            }
        }

        // The way out.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
                .size(44.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val arm = 6.5.dp.toPx()
                val ink = Color(SketchPainter.INK).copy(alpha = 0.42f)
                drawLine(ink, center + Offset(-arm, -arm), center + Offset(arm, arm), 1.4.dp.toPx(), StrokeCap.Round)
                drawLine(ink, center + Offset(arm, -arm), center + Offset(-arm, arm), 1.4.dp.toPx(), StrokeCap.Round)
            }
        }

        AnimatedVisibility(
            visible = session.note != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(420)),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp, start = 64.dp, end = 64.dp),
        ) {
            var shown by remember { mutableStateOf("") }
            session.note?.let { shown = it }
            Text(shown, color = Color(SketchPainter.INK).copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/**
 * Faint dots every few board units, so the page shows that it moves and how
 * far in it is; spaced out as it zooms away so they never become a texture.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.dotGrid(c: Camera) {
    var step = GRID * c.zoom
    while (step < 22f) step *= 2f
    val offX = -((c.x * c.zoom) % step + step) % step
    val offY = -((c.y * c.zoom) % step + step) % step
    val points = ArrayList<Offset>()
    var y = offY
    while (y < size.height) {
        var x = offX
        while (x < size.width) {
            points += Offset(x, y)
            x += step
        }
        y += step
    }
    drawPoints(
        points,
        androidx.compose.ui.graphics.PointMode.Points,
        Color(SketchPainter.INK).copy(alpha = 0.075f),
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
    )
}

private const val GRID = 32f
private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 8f
private const val TAP_MS = 280L
private const val DOUBLE_TAP_MS = 300L
private const val ARRIVE_MS = 420L
private const val ARRIVE_GLOW_MS = 1_100L
private const val LEAVE_MS = 340L
