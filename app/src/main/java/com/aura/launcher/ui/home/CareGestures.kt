package com.aura.launcher.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A tap on a control that shares its widget with a drag gesture.
 *
 * Both have to work on the same pixels: the widgets are made almost entirely
 * of buttons, and holding one is how the home screen is rearranged. The
 * arrangement that makes that possible is here and in the board's own
 * `holdThenDrag`, and it is a division of labour rather than a race —
 *
 *  - this never consumes the press, so the board can still watch it;
 *  - the board never consumes anything until the finger actually moves;
 *  - so a press that ends where it started is always the control's, however
 *    long it was held, and a press that travels is always the board's.
 *
 * The version before this one consumed the down to keep the board out, which
 * fixed the button and broke the dragging — there was almost nowhere left on a
 * widget to pick it up by.
 *
 * [onPressChange] is for the small scale animations these controls use: true
 * on the way down, false on the way back up, however the gesture ends.
 */
internal fun Modifier.careTap(
    onPressChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
): Modifier = this.pointerInput(onClick) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onPressChange(true)
        // Null when something else took the gesture — which, on this screen,
        // means the widget was being dragged out from under the finger.
        val up = waitForUpOrCancellation()
        onPressChange(false)
        if (up != null) {
            up.consume()
            onClick()
        }
    }
}
