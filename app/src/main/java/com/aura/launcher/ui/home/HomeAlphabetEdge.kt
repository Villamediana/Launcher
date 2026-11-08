package com.aura.launcher.ui.home

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.aura.launcher.ui.drawer.ALPHABET

/**
 * A hidden alphabet down the right edge of the home screen.
 *
 * Draws nothing at all. It used to fade in its own faded strip and a large
 * letter under the thumb, which meant the gesture had two different looks — a
 * flat one on the home screen and the springy one in the drawer — and the flat
 * one arrived first.
 *
 * Now the edge is purely a way in: the first touch opens the drawer at the
 * letter it landed on, and from that moment the drawer's own strip is what the
 * thumb is moving over, with its curve and its swell. One mechanism, seen once.
 *
 * The letter keeps updating for the rest of the gesture, so the list scrolls
 * under the thumb rather than waiting for it to lift.
 */
@Composable
fun HomeAlphabetEdge(
    activeLetters: Set<String>,
    onOpenAt: (String) -> Unit,
    /**
     * Where the thumb is, as a fraction of the strip's height, or null once it
     * lifts. The drawer's own alphabet reads this so the gesture that opened it
     * keeps curving the letters, instead of handing over to a flat strip.
     */
    onScrub: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    var height by remember { mutableIntStateOf(0) }
    var currentLetter by remember { mutableStateOf<String?>(null) }

    // The same letters the drawer's strip draws, in the same order. If this
    // mapped over the whole alphabet while the drawer showed only the occupied
    // ones, a thumb three quarters of the way down would mean different letters
    // on either side of the handover — and the handover is meant to be
    // invisible.
    val letters = remember(activeLetters) { ALPHABET.filter { it in activeLetters } }

    fun fractionAt(y: Float): Float? {
        if (height <= 0) return null
        return (y / height).coerceIn(0f, 1f)
    }

    fun letterAt(y: Float): String? {
        if (height <= 0 || letters.isEmpty()) return null
        val slot = height.toFloat() / letters.size
        return letters[(y / slot).toInt().coerceIn(0, letters.lastIndex)]
    }

    Box(
        modifier = modifier
            // The same band the drawer's strip occupies, bottom aligned. If
            // this covered the whole edge the two would disagree: a thumb at
            // the very top would mean "#", and the drawer would then draw its
            // bow a third of the way down the screen, nowhere near the finger
            // that drew it.
            .fillMaxHeight(BAND_FRACTION)
            .width(TOUCH_WIDTH)
            .onSizeChanged { height = it.height }
            .pointerInput(activeLetters) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        currentLetter = letterAt(offset.y)
                        onScrub(fractionAt(offset.y))
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // Open immediately, at the letter the thumb landed on.
                        currentLetter?.takeIf { it in activeLetters }?.let(onOpenAt)
                    },
                    onDragEnd = { currentLetter = null; onScrub(null) },
                    onDragCancel = { currentLetter = null; onScrub(null) },
                    onVerticalDrag = { change, _ ->
                        onScrub(fractionAt(change.position.y))
                        val letter = letterAt(change.position.y)
                        if (letter != currentLetter) {
                            currentLetter = letter
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            letter?.takeIf { it in activeLetters }?.let(onOpenAt)
                        }
                    },
                )
            },
    )
}

/** Wide enough for a thumb to find without aiming. */
private val TOUCH_WIDTH = 64.dp

/**
 * Kept in step with the drawer strip's own band by hand.
 *
 * Both are constants rather than one shared value because they are measured
 * against different things — this against the home screen, that against the
 * drawer, which starts below a search field — and a single number would have to
 * pretend those are the same height.
 */
private const val BAND_FRACTION = 0.66f
