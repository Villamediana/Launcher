package com.aura.launcher.sketch

import kotlin.math.hypot

/**
 * A circle around something is how the hand asks the model for help.
 *
 * A stroke that comes back near where it started is a loop; a loop with
 * things inside it is a question about those things. A loop around empty
 * board is just a circle someone drew, and stays one.
 */
object Lasso {

    /** The ids of the elements [stroke] goes around; empty when it is not a loop around anything. */
    fun enclosed(stroke: FloatArray, elements: List<Element>): List<String> {
        if (stroke.size < 16) return emptyList()
        val box = Box.of(stroke)
        val size = maxOf(box.width, box.height)
        if (size < MIN_SIZE) return emptyList()
        val gap = hypot(stroke[0] - stroke[stroke.size - 2], stroke[1] - stroke[stroke.size - 1])
        if (gap > size * CLOSE_ENOUGH) return emptyList()
        return elements.filter { e -> e.bounds.intersects(box) && inside(stroke, e) }.map { it.id }
    }

    /**
     * The middle of it, or most of a stroke. A circle drawn by hand cuts the
     * corners of what it goes around, so a stroke whose ends poke out — the
     * tip of a triangle, the tail of a letter — still counts.
     */
    private fun inside(loop: FloatArray, e: Element): Boolean {
        if (contains(loop, e.bounds.centerX, e.bounds.centerY)) return true
        if (e !is Element.Ink) return false
        val n = e.points.size / 2
        return (0 until n).count { contains(loop, e.points[it * 2], e.points[it * 2 + 1]) } >= n * MOSTLY
    }

    /** Even–odd point in polygon, the loop closed from its last point back to its first. */
    fun contains(loop: FloatArray, x: Float, y: Float): Boolean {
        val n = loop.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = loop[i * 2]; val yi = loop[i * 2 + 1]
            val xj = loop[j * 2]; val yj = loop[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Smaller than this, in board units, is a dot or a letter o, not a loop around something. */
    private const val MIN_SIZE = 40f
    /** How far apart the ends may be, as a share of the loop's size. */
    private const val CLOSE_ENOUGH = 0.3f
    /** The share of a stroke's points inside that makes it circled even with its middle outside. */
    private const val MOSTLY = 0.6f
}
