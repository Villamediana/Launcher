package com.aura.launcher.sketch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A board to show: [boardId] null for a new one. [originX], [originY] are
 * where on the screen it was opened from, in pixels, so it can open out of
 * that point — negative for the middle.
 */
data class SketchOpening(val boardId: String?, val originX: Float = -1f, val originY: Float = -1f)

/** Whether the drawing board is up. Raised by the gesture surface or settings, shown by the launcher's root. */
object SketchHost {
    private val _open = MutableStateFlow<SketchOpening?>(null)
    val open: StateFlow<SketchOpening?> = _open.asStateFlow()

    fun open(opening: SketchOpening) {
        _open.value = opening
    }

    fun close() {
        _open.value = null
    }
}
