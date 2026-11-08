package com.aura.launcher

import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.Gesture
import com.aura.launcher.gestures.GestureAction
import com.aura.launcher.gestures.GestureMatch
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.gestures.matchStrokes
import com.aura.launcher.gestures.saved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shapes that need the hand to leave the glass and come back.
 *
 * A cross, a letter with a bar, anything with a separate mark: none of them can
 * be drawn in one go, and until now none of them could be a gesture. The strokes
 * are resampled separately and laid end to end rather than joined into one line,
 * because the gap between two strokes is where the finger was in the air — and a
 * recogniser that spends points travelling it is comparing the pauses as well as
 * the shape.
 */
class MultiStrokeGestureTest {

    private fun line(from: Dot, to: Dot, steps: Int = 24) = (0..steps).map { i ->
        val t = i.toFloat() / steps
        Dot(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    /** A cross: down the middle, then across. */
    private fun cross(scale: Float = 1f, at: Dot = Dot(0f, 0f)) = listOf(
        line(Dot(at.x + 150f * scale, at.y), Dot(at.x + 150f * scale, at.y + 300f * scale)),
        line(Dot(at.x, at.y + 150f * scale), Dot(at.x + 300f * scale, at.y + 150f * scale)),
    )

    /** A T: across the top, then down. */
    private fun tee() = listOf(
        line(Dot(0f, 0f), Dot(300f, 0f)),
        line(Dot(150f, 0f), Dot(150f, 300f)),
    )

    private fun oneStroke() = listOf(line(Dot(0f, 0f), Dot(0f, 300f)))

    private fun gesture(name: String, vararg drawings: List<List<Dot>>) = Gesture(
        id = name,
        name = name,
        templates = drawings.map { GestureShape.normaliseStrokes(it).saved() },
        actions = listOf(GestureAction.ShowGestures),
    )

    @Test
    fun `a two-stroke shape normalises to the usual number of points`() {
        // Everything downstream compares point for point, so a multi-stroke
        // shape that came out a different length would score zero against
        // every template rather than failing visibly.
        assertEquals(GestureShape.POINTS, GestureShape.normaliseStrokes(cross()).size)
        assertEquals(GestureShape.POINTS, GestureShape.normaliseStrokes(tee()).size)
    }

    @Test
    fun `a cross drawn again is recognised`() {
        val match = matchStrokes(cross(), listOf(gesture("cross", cross())))
        assertTrue("$match", match is GestureMatch.Hit)
    }

    @Test
    fun `where and how big it was drawn still does not matter`() {
        val elsewhere = cross(scale = 2.1f, at = Dot(700f, 1200f))
        assertTrue(matchStrokes(elsewhere, listOf(gesture("cross", cross()))) is GestureMatch.Hit)
    }

    @Test
    fun `a cross is not a T`() {
        // Same two lines, different places and different order. If the gap
        // between strokes were being resampled as part of the path these would
        // pull together, because most of the difference is in the join.
        val score = GestureShape.similarity(
            GestureShape.normaliseStrokes(cross()),
            GestureShape.normaliseStrokes(tee()),
        )
        assertTrue("cross scored $score against T", score < GestureShape.MATCH)
    }

    @Test
    fun `stroke order is part of the shape`() {
        val acrossFirst = cross().reversed()
        val score = GestureShape.similarity(
            GestureShape.normaliseStrokes(cross()),
            GestureShape.normaliseStrokes(acrossFirst),
        )
        assertTrue("the same cross drawn in the other order scored $score", score < GestureShape.MATCH)
    }

    @Test
    fun `one stroke still behaves exactly as it did`() {
        // The single-stroke path is the common one and must not have moved.
        val single = oneStroke()
        assertEquals(
            GestureShape.normalise(single.first()),
            GestureShape.normaliseStrokes(single),
        )
    }

    @Test
    fun `a stray dot alongside a real stroke does not derail it`() {
        // A finger brushing the glass between strokes contributes two points
        // and no length. It must not take a share of the sixty-four away from
        // the stroke that carries the shape.
        val withSpeck = oneStroke() + listOf(listOf(Dot(400f, 400f), Dot(401f, 400f)))
        val score = GestureShape.similarity(
            GestureShape.normaliseStrokes(oneStroke()),
            GestureShape.normaliseStrokes(withSpeck),
        )
        assertTrue("a speck cost $score", score >= GestureShape.MATCH)
    }

    @Test
    fun `the boundary between strokes survives being saved`() {
        // Not needed to match — comparison reads x and y alone — but needed to
        // draw the shape back. Without it every preview of a plus sign joined
        // the two strokes into a zigzag, which reads as the recogniser having
        // joined them too.
        val shape = GestureShape.normaliseStrokes(cross())
        assertEquals(2, shape.count { it.start })
        assertTrue("the first point of the shape starts a stroke", shape.first().start)

        val roundTripped = gesture("cross", cross()).shapes().first()
        assertEquals(
            shape.map { it.start },
            roundTripped.map { it.start },
        )
    }

    @Test
    fun `one stroke has exactly one beginning`() {
        assertEquals(1, GestureShape.normaliseStrokes(oneStroke()).count { it.start })
    }

    @Test
    fun `nothing drawable is nothing`() {
        assertTrue(GestureShape.normaliseStrokes(emptyList()).isEmpty())
        assertTrue(GestureShape.normaliseStrokes(listOf(listOf(Dot(1f, 1f)))).isEmpty())
    }

    @Test
    fun `a tap and a brush together are still too short to act on`() {
        val specks = listOf(
            listOf(Dot(10f, 10f), Dot(12f, 11f)),
            listOf(Dot(80f, 80f), Dot(82f, 81f)),
        )
        assertEquals(
            GestureMatch.TooShort,
            matchStrokes(specks, listOf(gesture("cross", cross()))),
        )
    }
}
