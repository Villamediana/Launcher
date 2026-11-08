package com.aura.launcher.gestures

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where on the screen the shape that opened the list finished. */
data class HelpAnchor(val x: Float, val y: Float) {
    val known: Boolean get() = x >= 0f && y >= 0f

    companion object {
        /** Nowhere in particular: put it in the middle. */
        val Unknown = HelpAnchor(-1f, -1f)
    }
}

/**
 * Whether the list of gestures is on screen, and where it came from.
 *
 * A flag rather than a call, because the two ends are far apart: it is raised
 * by [GestureRunner], a plain object on a background coroutine, and read by the
 * launcher's composition. Neither can hold the other, and a flow between them
 * is the whole of what they need to agree on.
 *
 * The anchor is noted separately from the raising, and by somebody else. The
 * runner knows a gesture asked for the list; only the surface that caught the
 * stroke knows where the hand was when it did — and a menu that unfolds
 * somewhere other than where you drew is a menu that arrived rather than one
 * you opened.
 */
object GestureHelp {

    private val _showing = MutableStateFlow<HelpAnchor?>(null)
    val showing: StateFlow<HelpAnchor?> = _showing.asStateFlow()

    private var anchor = HelpAnchor.Unknown

    /** Called by the drawing surface for every stroke it recognises. */
    fun noteAnchor(x: Float, y: Float) {
        anchor = HelpAnchor(x, y)
    }

    fun show() {
        _showing.value = anchor
        // Spent. A later opening from somewhere with no stroke behind it — a
        // tap in the palette itself, a step in a longer gesture — should land
        // in the middle rather than wherever a finger happened to be minutes
        // ago.
        anchor = HelpAnchor.Unknown
    }

    fun hide() {
        _showing.value = null
    }
}
