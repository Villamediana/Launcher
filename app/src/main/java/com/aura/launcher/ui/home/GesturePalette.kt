package com.aura.launcher.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.Gesture
import com.aura.launcher.gestures.GestureAction
import com.aura.launcher.gestures.GestureHelp
import com.aura.launcher.gestures.GestureRunner
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Every gesture you have, as a stack of plates dealt from the fingertip.
 *
 * The shape here borrows from Sword Art Online's menu: a column of separate
 * pale plates beside the point the hand stopped, the one under the thumb going
 * solid amber. It is not decoration for its own sake. A single panel of rows
 * is a dialog and reads
 * like settings; plates that are each their own object, hinged off the point
 * the hand stopped, read as something the gesture opened.
 *
 * The list solves the problem every gesture feature has: a shape lives in the
 * hand, and hands forget. Without it the launcher ends up used for the two
 * gestures somebody happens to remember. So the plates carry the *drawing*
 * first and the name second, and any of them can be run without drawing
 * anything.
 */
@Composable
fun GesturePalette(app: AuraApp) {
    val anchor by GestureHelp.showing.collectAsState()
    val from = anchor ?: return

    val gestures by app.gestures.gestures.collectAsState()

    // Not itself. The gesture that opens this list is the one gesture nobody
    // needs reminding of while looking at it, and a row that reopens the thing
    // it is already inside is a loop wearing the clothes of an option.
    val live = remember(gestures) {
        gestures.filter { g ->
            g.enabled &&
                g.actions.isNotEmpty() &&
                g.actions.none { it is GestureAction.ShowGestures }
        }
    }

    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }

    // Whether the shape that opened this had already darkened the room. Read
    // once, on the way in: by the time the plates have dealt themselves out the
    // drawing is gone and its answer would have changed.
    val handed = remember { GestureVeil.up }
    // Unfolding, not popping, and quickly. Scaling from six tenths with a
    // loose spring was a puff of smoke; a soft spring from nine tenths was the
    // right shape and took too long to get there. The motion is carried by the
    // plates dealing themselves out, so the panel under them should be settled
    // by the time the second one lands.
    val presence by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
        label = "palettePresence",
    )

    val density = LocalDensity.current
    val marginPx = with(density) { 18.dp.toPx() }
    val offsetPx = with(density) { HINGE_INSET.toPx() }
    val stackWidthPx = with(density) { STACK_WIDTH.toPx() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Enough to quiet the room, not enough to replace it. The plates
            // are pale and solid, so they carry themselves — the veil only has
            // to stop the wallpaper competing, and a near-black one turned the
            // whole phone off to show five rows.
            //
            // The same dark the drawing surface uses, because most of the time
            // it is the one the drawing surface was already holding.
            .background(GestureVeil.COLOUR.copy(alpha = veilAlpha(handed, presence))),
    ) {
        // A sibling behind the menu, so tapping the dimmed room closes it while
        // taps and scrolls on the plates remain entirely their own gestures.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = GestureHelp::hide,
                ),
        )

        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val originX = if (from.known) from.x else widthPx * 0.28f
        val originY = if (from.known) from.y else heightPx / 2f

        // Which side the plates fan out to. Toward the middle of the screen,
        // so a gesture drawn near an edge does not open into it.
        val toRight = originX < widthPx / 2f
        val stackLeft = if (toRight) {
            (originX + offsetPx).coerceAtMost(widthPx - stackWidthPx - marginPx)
        } else {
            (originX - offsetPx - stackWidthPx).coerceAtLeast(marginPx)
        }.coerceAtLeast(marginPx)

        var stackHeight by remember { mutableStateOf(0) }
        val stackTop = (originY - stackHeight / 2f)
            .coerceIn(marginPx, (heightPx - stackHeight - marginPx).coerceAtLeast(marginPx))

        Column(
            Modifier
                .width(STACK_WIDTH)
                .offset { IntOffset(stackLeft.roundToInt(), stackTop.roundToInt()) }
                .onSizeChanged { stackHeight = it.height }
                .graphicsLayer {
                    // Out of the fingertip, not out of the middle of itself.
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (toRight) -0.1f else 1.1f,
                        pivotFractionY = if (size.height > 0f) {
                            ((originY - stackTop) / size.height).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        },
                    )
                    val s = 0.92f + 0.08f * presence
                    scaleX = s
                    scaleY = s
                    alpha = presence
                },
        ) {
            if (live.isEmpty()) {
                Plate(lit = false) {
                    Text(
                        stringResource(R.string.palette_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aura.colors.inkFaint,
                    )
                }
            }
            GestureList(
                gestures = live,
                // On the application's scope, not this composition's. Hiding
                // the sheet takes this composable away, and a job started here
                // would be cancelled a frame later — the finger would come up,
                // the menu would close, and nothing else would happen.
                onRun = { gesture ->
                    GestureHelp.hide()
                    app.scope.launch { GestureRunner.run(app, gesture) }
                },
            )
        }
    }
}

/**
 * The list behaves like an ordinary list: drag to scroll, tap to run.
 *
 * The earlier hold-and-release interaction made a scroll double as a
 * selection gesture. It looked distinctive, but it also meant somebody could
 * not explore the list with the normal movement every other Android list uses.
 * Keeping selection on a click makes movement reversible and unsurprising.
 */
@Composable
private fun GestureList(gestures: List<Gesture>, onRun: (Gesture) -> Unit) {
    if (gestures.isEmpty()) return
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(0) }

    Box(
        Modifier
            .heightIn(max = 322.dp)
            .onSizeChanged { viewport = it.height },
    ) {
        Column(Modifier.verticalScroll(scroll)) {
            gestures.forEachIndexed { index, gesture ->
                GesturePlate(gesture = gesture, index = index) { onRun(gesture) }
            }
        }

        // Only when there is something below the fold.
        //
        // Without it a list that continues looks like a list that ends, and
        // the gestures past the sixth might as well not exist.
        val overflow = scroll.maxValue
        if (overflow > 0 && viewport > 0) {
            val total = viewport + overflow
            val thumb = (viewport.toFloat() / total).coerceIn(0.12f, 1f)
            val travel = 1f - thumb
            val at = scroll.value.toFloat() / overflow
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp)
                    .width(3.dp)
                    .height(with(LocalDensity.current) { viewport.toDp() })
                    .clip(RoundedCornerShape(50))
                    .background(Aura.colors.ink.copy(alpha = 0.14f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(thumb)
                        .offset { IntOffset(0, (travel * at * viewport).roundToInt()) }
                        .clip(RoundedCornerShape(50))
                        .background(Aura.colors.ink.copy(alpha = 0.45f)),
                )
            }
        }
    }
}

/** One plate: a badge holding the drawing, and the name. */
@Composable
private fun GesturePlate(gesture: Gesture, index: Int, onClick: () -> Unit) {
    // Staggered, so the stack deals itself out instead of appearing. Small
    // enough that a long list is not a queue: by the sixth plate it is over.
    var shown by remember(gesture.id) { mutableStateOf(false) }
    LaunchedEffect(gesture.id) {
        delay(index * 20L)
        shown = true
    }
    val arrive by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(150),
        label = "plateArrive",
    )
    val interaction = remember(gesture.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "plateGlow",
    )

    Box(
        Modifier
            .height(ROW_PITCH)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                alpha = arrive
                // Dealt from the hinge side.
                translationX = (1f - arrive) * -30f
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Plate(lit = pressed, glow = glow) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(BADGE)
                    .clip(RoundedCornerShape(50))
                    // On a lit plate the badge is the surface showing through
                    // the amber; on a resting one it is a wash of the accent.
                    .background(
                        if (pressed) Aura.colors.surface
                        else Aura.colors.accent.copy(alpha = 0.14f)
                    ),
            ) {
                PlateShape(
                    shape = gesture.shapes().firstOrNull().orEmpty(),
                    tint = Aura.colors.accent,
                    modifier = Modifier.size(BADGE * 0.66f),
                )
            }
            Spacer(Modifier.width(11.dp))
            Text(
                gesture.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (pressed) Aura.colors.surface else Aura.colors.ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The plate itself: flat, solid, and its own object.
 *
 * The launcher's own colours rather than the ones the quotation came in. A
 * raised surface for a plate, the accent for the one under the thumb, and the
 * theme's ink on top of both — so the menu belongs to this app in either theme
 * instead of being a borrowed skin that happens to sit over it.
 */
@Composable
private fun Plate(lit: Boolean, glow: Float = 0f, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(PLATE_HEIGHT)
            .graphicsLayer {
                // Forward, under the thumb. In the original the selected plate
                // is nearer the camera, and that is the whole gesture of it.
                val s = 1f + 0.045f * glow
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(5.dp))
            .background(if (lit) Aura.colors.accent else Aura.colors.surfaceRaised)
            .padding(horizontal = 8.dp),
        content = { content() },
    )
}

/** The drawing on a plate, solid rather than suggested. */
@Composable
private fun PlateShape(shape: List<Dot>, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (shape.size < 2) return@Canvas
        val factor = size.minDimension / GestureShape.BOX * 0.88f
        val path = Path()
        shape.forEachIndexed { index, dot ->
            val x = size.width / 2f + dot.x * factor
            val y = size.height / 2f + dot.y * factor
            // A shape drawn in two goes is drawn back in two goes.
            if (index == 0 || dot.start) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Width as a fraction of the badge, not in raw pixels: a literal was a
        // hairline on a high-density screen, which reads as a faded drawing
        // rather than a drawn one.
        drawPath(
            path,
            SolidColor(tint),
            style = Stroke(
                width = size.minDimension * 0.10f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** How tall one plate is, and the pitch the finger's position maps through. */
private val PLATE_HEIGHT = 42.dp
private val ROW_PITCH = 46.dp
private val STACK_WIDTH = 208.dp
private val BADGE = 27.dp
/**
 * How far from the fingertip the plates sit.
 *
 * Clear of the thumb that drew the shape, so the first plate is not already
 * under it when the stack lands.
 */
private val HINGE_INSET = 40.dp
