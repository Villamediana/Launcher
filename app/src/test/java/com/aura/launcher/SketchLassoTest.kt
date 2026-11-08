package com.aura.launcher

import com.aura.launcher.sketch.Box
import com.aura.launcher.sketch.Element
import com.aura.launcher.sketch.Lasso
import com.aura.launcher.sketch.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A circle around something asks the model; a circle around nothing is a drawing. */
class SketchLassoTest {

    /** A loop around (cx, cy), going [turn] of the way round. */
    private fun loop(cx: Float, cy: Float, r: Float, turn: Float = 1f) = FloatArray(80) { i ->
        val a = (i / 2) / 39f * 2 * PI.toFloat() * turn
        if (i % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
    }

    private val word = Element.Ink("w", floatArrayOf(80f, 100f, 100f, 90f, 120f, 110f, 140f, 95f), 3.5f)
    private val far = Element.Shape("f", ShapeKind.RECT, Box(500f, 500f, 560f, 560f))

    @Test fun `circling a word asks about the word only`() {
        assertEquals(listOf("w"), Lasso.enclosed(loop(110f, 100f, 70f), listOf(word, far)))
    }

    @Test fun `a circle around nothing is just a circle`() {
        assertTrue(Lasso.enclosed(loop(300f, 300f, 60f), listOf(word, far)).isEmpty())
    }

    @Test fun `an open arc is not a circle`() {
        assertTrue(Lasso.enclosed(loop(110f, 100f, 70f, turn = 0.6f), listOf(word)).isEmpty())
    }

    @Test fun `a stroke cut through, not around, is not circled`() {
        val long = Element.Ink("l", floatArrayOf(0f, 100f, 400f, 100f), 3.5f)
        assertTrue(Lasso.enclosed(loop(110f, 100f, 70f), listOf(long)).isEmpty())
    }

    @Test fun `a triangle whose tips poke out of the circle is still circled`() {
        val triangle = Element.Ink("t", floatArrayOf(40f, 160f, 110f, 20f, 180f, 160f, 40f, 160f), 3.5f)
        assertEquals(listOf("t"), Lasso.enclosed(loop(110f, 100f, 75f), listOf(triangle)))
    }

    @Test fun `a small o is a letter`() {
        assertTrue(Lasso.enclosed(loop(110f, 100f, 12f), listOf(Element.Ink("d", floatArrayOf(110f, 100f), 3.5f))).isEmpty())
    }
}
