package com.aura.launcher.ui.drawer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.ui.theme.Aura
import kotlin.math.abs
import kotlin.math.exp

/**
 * Every letter the scrubber could show, in order.
 *
 * The strip itself is filtered down to the ones with apps behind them — see
 * [AlphabetIndex]. This is the ordering those are drawn from.
 */
val ALPHABET: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

/**
 * The A–Z strip down the edge of the drawer.
 *
 * Dragging along it scrubs the list, and the whole column bows inward to follow
 * the thumb. The bow is one continuous curve rather than a few letters reacting
 * on their own: the displacement is a bell over about eight letters, so the
 * strip bends like a line under a finger instead of stepping as each glyph
 * takes its turn. That is the difference between a control that reads as
 * elastic and one that reads as a list of buttons that happen to move.
 *
 * It does not reach the top of the screen. Twenty-seven targets spread over a
 * full phone height puts the first third of the alphabet where a thumb has to
 * stretch, and stretching is where a scrub goes wrong — so the strip occupies
 * the lower two thirds and the letters get more room each as a result.
 *
 * Only letters with apps behind them are drawn. Faint placeholders for the
 * empty ones held the alphabet's shape at the cost of a column of glyphs that
 * do nothing when you land on them — and on a phone with no Q, X or Z apps
 * that is three targets a thumb can hit and be ignored by. Dropping them also
 * bought the room to set the rest larger.
 *
 * The touch area is much wider than the letters look. A thumb is nearer 10 mm
 * across, so the column reaches well inward of the glyphs, and only the text
 * sits at the edge.
 */
@Composable
fun AlphabetIndex(
    letters: List<String>,
    activeLetters: Set<String>,
    onLetter: (String) -> Unit,
    /**
     * The letter under the thumb right now, or null the moment it lifts.
     *
     * Separate from [onLetter], which only fires for letters that have apps
     * behind them and only to scroll the list. This reports every letter
     * crossed, including the empty ones, because the drawer narrows to what is
     * being pointed at and "nothing here" is an answer worth showing rather
     * than a frame where the list silently keeps its old contents.
     */
    onScrubLetter: (String?) -> Unit = {},
    /**
     * Where a thumb is on the strip, as a fraction of its height, when the
     * gesture belongs to something else.
     *
     * The home screen's edge starts the drag, so this strip never sees the
     * pointer — and without being told, it sat flat while the thumb slid down
     * it. Passing the position through is what makes one continuous gesture
     * look like one gesture: the drawer flies up and the letters are already
     * curving out to meet the thumb that opened it.
     */
    externalFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Travel is expressed in dp and converted once, so the movement reads the
    // same on a dense screen as on a coarse one.
    val maxShiftPx = remember(density) { with(density) { LETTER_TRAVEL.toPx() } }

    var height by remember { mutableIntStateOf(0) }
    var touchY by remember { mutableFloatStateOf(-1f) }
    var active by remember { mutableStateOf(false) }
    var lastLetter by remember { mutableStateOf<String?>(null) }

    fun letterAt(y: Float): String? {
        if (letters.isEmpty() || height <= 0) return null
        val slot = height.toFloat() / letters.size
        val index = (y / slot).toInt().coerceIn(0, letters.lastIndex)
        return letters[index]
    }

    fun scrub(y: Float) {
        touchY = y
        val letter = letterAt(y) ?: return
        if (letter != lastLetter) {
            lastLetter = letter
            // One tick per letter crossed: the strip is small enough that touch
            // alone does not tell you when you have moved.
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onScrubLetter(letter)
            if (letter in activeLetters) onLetter(letter)
        }
    }

    fun release() {
        active = false
        touchY = -1f
        lastLetter = null
        onScrubLetter(null)
    }

    Box(modifier.fillMaxHeight().width(TOUCH_WIDTH)) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                // The band, rather than the whole side of the screen. Bottom
                // aligned so it grows down from where the thumb already rests.
                .fillMaxHeight(BAND_FRACTION)
                .padding(bottom = BOTTOM_INSET)
                .onSizeChanged { height = it.height }
                .pointerInput(letters, activeLetters) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            active = true
                            lastLetter = null
                            scrub(offset.y)
                        },
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                        onVerticalDrag = { change, _ -> scrub(change.position.y) },
                    )
                }
                .pointerInput(letters, activeLetters) {
                    detectTapGestures { offset ->
                        lastLetter = null
                        scrub(offset.y)
                    }
                },
        ) {
            val slot = if (letters.isEmpty()) 0f else height.toFloat() / letters.size

            // A direct touch always wins; the handed-over position is what keeps
            // the strip alive for a gesture that began somewhere else.
            val pointerY = when {
                active && touchY >= 0f -> touchY
                externalFraction != null && height > 0 -> externalFraction * height
                else -> -1f
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxHeight(),
            ) {
                letters.forEachIndexed { index, letter ->
                    val distance = if (pointerY < 0f || slot <= 0f) {
                        Float.MAX_VALUE
                    } else {
                        abs((index + 0.5f) * slot - pointerY) / slot
                    }

                    // A bell rather than a cone. The old falloff was a cubed
                    // linear ramp over four letters, which put a visible kink
                    // where it hit zero — the strip bent, then cornered. A
                    // Gaussian has no such edge, so the column reads as one
                    // curve from end to end.
                    val eased = if (distance == Float.MAX_VALUE) {
                        0f
                    } else {
                        exp(-(distance * distance) / (2f * CURVE_WIDTH * CURVE_WIDTH))
                    }

                    val shift by animateFloatAsState(
                        targetValue = -eased * maxShiftPx,
                        // Tighter than it was. The curve has to sit under the
                        // thumb while it moves, and a loose spring left the
                        // bow trailing behind the finger that was drawing it.
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 900f),
                        label = "letterShift",
                    )
                    // Barely any. The curve is what marks the position now, and
                    // letters that also tripled in size made the strip lurch.
                    val scale by animateFloatAsState(
                        targetValue = 1f + eased * 0.32f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 800f),
                        label = "letterScale",
                    )

                    val enabled = letter in activeLetters
                    val color = when {
                        eased > 0.55f -> colors.accent
                        enabled -> colors.inkMuted
                        else -> colors.inkFaint.copy(alpha = 0.3f)
                    }

                    androidx.compose.material3.Text(
                        text = letter,
                        fontSize = LETTER_SIZE,
                        color = color,
                        modifier = Modifier
                            .padding(end = EDGE_INSET)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = shift
                                // Grows leftward from the right edge rather
                                // than outward in both directions, so it never
                                // clips.
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            },
                    )
                }
            }

            // The letter being pointed at, set large beside the bow.
            //
            // This is what the circle in the reference actually was — the app
            // was on O, and a capital O at this size is a ring. It was read
            // here as a thumb marker and built as one, which is why removing
            // it felt right and still left something missing: a marker beside
            // your own finger says nothing, but the letter does, because the
            // thumb covers the strip exactly where you are trying to read it.
            val pointed = if (pointerY >= 0f && slot > 0f) {
                letters.getOrNull((pointerY / slot).toInt().coerceIn(0, letters.lastIndex))
            } else {
                null
            }
            if (pointed != null) {
                val bubblePx = remember(density) { with(density) { BUBBLE_SIZE.toPx() } }
                val bubbleInset = remember(density) { with(density) { BUBBLE_INSET.toPx() } }
                androidx.compose.material3.Text(
                    text = pointed,
                    fontSize = BUBBLE_SIZE,
                    color = colors.ink.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .graphicsLayer {
                            translationY = pointerY - bubblePx * 0.62f
                            translationX = -bubbleInset
                        },
                )
            }
        }
    }
}

/**
 * Big enough to read while a thumb is moving past it.
 *
 * label-small was about 11sp, which is a caption size — fine for something you
 * study and wrong for something you aim at without looking straight at it.
 * Dropping the empty letters freed the room for this.
 */
private val LETTER_SIZE = 15.sp

/** The pointed-at letter, set large enough to read past a thumb. */
private val BUBBLE_SIZE = 34.sp

/**
 * Far enough inward to clear the apex of the bow.
 *
 * Not a free choice — it follows from the other two constants. A glyph sits
 * [EDGE_INSET] from the edge and travels up to [LETTER_TRAVEL] inward, so the
 * innermost letter reaches about 92dp in, and its own width carries it past
 * 100. At 84dp the bubble was landing exactly on the letter it was supposed to
 * be reporting, which is the one place it must never be.
 */
private val BUBBLE_INSET = 128.dp

/** Wide enough for a thumb; the glyphs still sit near the edge. */
private val TOUCH_WIDTH = 64.dp

/** Breathing room from the screen edge; hard against it looks like a mistake. */
private val EDGE_INSET = 16.dp

/** How far the letter under the thumb travels inward. */
private val LETTER_TRAVEL = 76.dp

/**
 * How much of the drawer's height the alphabet occupies, measured from the
 * bottom. The top third is where a thumb has to stretch, and a scrub that
 * needs a stretch is a scrub that lands on the wrong letter.
 */
private const val BAND_FRACTION = 0.66f

/** Clear of the gesture bar at the very bottom. */
private val BOTTOM_INSET = 28.dp

/**
 * Spread of the bow, in letters either side of the thumb.
 *
 * Measured off the widget this was drawn from rather than guessed: there the
 * displacement is still a fifth of its maximum nine letters away from the
 * thumb, and only reaches zero at the ends of the alphabet. That is a sigma
 * near five. At the 3.2 it started with, the bow closed back to the edge within
 * three letters and read as a bump travelling along a straight line — the whole
 * column has to lean, or it is not a curve.
 */
private const val CURVE_WIDTH = 5.2f
