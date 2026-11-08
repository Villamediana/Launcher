package com.aura.launcher.ui.home

import androidx.compose.ui.graphics.Color

/**
 * The dark the launcher goes behind a gesture: asked for from two places, only
 * ever drawn by one.
 *
 * The drawing surface dims the room while a shape is being made, and the
 * palette dims it while the list is open. A shape that opens the list needs
 * both, one straight after the other, with nothing visible happening in
 * between — and they used to carry their own, a different black at a different
 * strength each. So while the two overlapped the room went darker than either,
 * and the moment the drawing let go it went *lighter* than it had been. Both
 * happen exactly as the menu arrives, which reads as the veil being swapped for
 * another rather than simply kept.
 *
 * One colour, one strength, and a note of who currently has the room dark.
 */
object GestureVeil {

    /** Never opaque: the wallpaper holding its breath, not a different screen. */
    val COLOUR = Color(0xFF080706)

    const val ALPHA = 0.62f

    private val holders = mutableSetOf<Any>()

    /** Whether the room is dark already. */
    val up: Boolean get() = holders.isNotEmpty()

    fun hold(who: Any) {
        holders += who
    }

    fun release(who: Any) {
        holders -= who
    }
}

/**
 * How dark the palette's own veil is.
 *
 * [handed] is the whole of it. Opening over a screen the drawing surface has
 * already darkened, the veil has to start where that one left off: fading in
 * from nothing would show the wallpaper coming back through a menu that is
 * already there. Opening cold — from a shortcut, a macro, a scheduled task —
 * it arrives with everything else.
 */
fun veilAlpha(handed: Boolean, presence: Float): Float =
    if (handed) GestureVeil.ALPHA else GestureVeil.ALPHA * presence
