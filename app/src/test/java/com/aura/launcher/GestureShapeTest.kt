package com.aura.launcher

import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.GestureShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Telling one finger-drawn shape from another.
 *
 * The whole feature rests on this and none of it is visible: a recogniser that
 * is slightly wrong does not throw, it just fires the wrong thing occasionally,
 * and "occasionally" is the hardest kind of bug to report. Worse, it fires
 * *something*, so it never looks broken — only unreliable, which is how the
 * gesture ends up unused.
 *
 * Every stroke here is built by hand out of arithmetic, so this runs anywhere
 * and says exactly which property broke.
 */
class GestureShapeTest {

    // ---------------------------------------------------------- some shapes --

    private fun line(from: Dot, to: Dot, steps: Int = 40) = (0..steps).map { i ->
        val t = i.toFloat() / steps
        Dot(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    private fun swipeUp(scale: Float = 1f, at: Dot = Dot(0f, 0f)) =
        line(Dot(at.x, at.y + 300f * scale), Dot(at.x, at.y))

    private fun swipeDown(scale: Float = 1f, at: Dot = Dot(0f, 0f)) =
        line(Dot(at.x, at.y), Dot(at.x, at.y + 300f * scale))

    private fun swipeRight() = line(Dot(0f, 0f), Dot(300f, 0f))

    /** An L: down, then right. */
    private fun ell() = line(Dot(0f, 0f), Dot(0f, 300f), 20) + line(Dot(0f, 300f), Dot(300f, 300f), 20)

    /** The same L drawn by a hand in a hurry: the upright leans, the foot is short. */
    private fun shakyEll(lean: Float, foot: Float) =
        line(Dot(0f, 0f), Dot(lean, 300f), 20) + line(Dot(lean, 300f), Dot(foot, 300f), 20)

    /** An L rotated a quarter turn: right, then up. */
    private fun ellTurned() =
        line(Dot(0f, 300f), Dot(300f, 300f), 20) + line(Dot(300f, 300f), Dot(300f, 0f), 20)

    private fun circle(steps: Int = 60, radius: Float = 150f) = (0..steps).map { i ->
        val a = 2 * PI * i / steps
        Dot((radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
    }

    /** A caret: up then down. */
    private fun caret() = line(Dot(0f, 300f), Dot(150f, 0f), 20) + line(Dot(150f, 0f), Dot(300f, 300f), 20)

    /** A vee: down then up. */
    private fun vee() = line(Dot(0f, 0f), Dot(150f, 300f), 20) + line(Dot(150f, 300f), Dot(300f, 0f), 20)

    private fun norm(stroke: List<Dot>) = GestureShape.normalise(stroke)

    private fun scoreOf(a: List<Dot>, b: List<Dot>) =
        GestureShape.similarity(norm(a), norm(b))

    // ------------------------------------------------------ what must match --

    @Test
    fun `the same shape drawn twice matches itself`() {
        assertTrue(scoreOf(ell(), ell()) > 0.99f)
    }

    @Test
    fun `where on the screen it was drawn does not matter`() {
        // Drawn in the corner and drawn in the middle is the same gesture. The
        // overlay covers the whole screen and nobody aims.
        val score = scoreOf(swipeUp(at = Dot(0f, 0f)), swipeUp(at = Dot(880f, 1400f)))
        assertTrue("moved stroke scored $score", score > 0.99f)
    }

    @Test
    fun `how big it was drawn does not matter`() {
        val score = scoreOf(swipeUp(scale = 1f), swipeUp(scale = 3f))
        assertTrue("rescaled stroke scored $score", score > 0.99f)
    }

    @Test
    fun `how fast it was drawn does not matter`() {
        // A flick reports a handful of points and a slow drag reports hundreds.
        // Resampling is what makes those the same stroke.
        val slow = line(Dot(0f, 0f), Dot(0f, 300f), steps = 200)
        val quick = line(Dot(0f, 0f), Dot(0f, 300f), steps = 4)
        assertTrue(scoreOf(slow, quick) > 0.98f)
    }

    @Test
    fun `a shaky hand still draws the same shape`() {
        // What a real finger produces. If this fails the gesture works for
        // whoever tuned it and for nobody else.
        val shaky = swipeUp().mapIndexed { i, p ->
            Dot(p.x + if (i % 2 == 0) 6f else -6f, p.y + if (i % 3 == 0) 4f else -4f)
        }
        val score = scoreOf(swipeUp(), shaky)
        assertTrue("shaky stroke scored $score", score >= GestureShape.MATCH)
    }

    // --------------------------------------------------- what must not match --

    @Test
    fun `up and down are different gestures`() {
        // The single most important pair, and the one the textbook version of
        // this algorithm gets wrong: it rotates every stroke to its own angle,
        // which makes a shape identical to itself upside down.
        val score = scoreOf(swipeUp(), swipeDown())
        assertTrue("up scored $score against down", score < GestureShape.MATCH)
    }

    @Test
    fun `a caret is not a vee`() {
        val score = scoreOf(caret(), vee())
        assertTrue("caret scored $score against vee", score < GestureShape.MATCH)
    }

    @Test
    fun `an L is not the same L turned a quarter`() {
        val score = scoreOf(ell(), ellTurned())
        assertTrue("L scored $score against turned L", score < GestureShape.MATCH)
    }

    @Test
    fun `a line is not a circle`() {
        assertTrue(scoreOf(swipeRight(), circle()) < GestureShape.MATCH)
    }

    @Test
    fun `a vertical line is not a horizontal one`() {
        // Only true because the scaling is uniform. Stretching each stroke to
        // fill a square — which is what the paper does — turns both of these
        // into the same degenerate shape and every line matches every line.
        val score = scoreOf(swipeDown(), swipeRight())
        assertTrue("vertical scored $score against horizontal", score < GestureShape.MATCH)
    }

    // ----------------------------------------------------------- the helpers --

    @Test
    fun `resampling gives exactly the number of points asked for`() {
        listOf(2, 3, 16, 64, 200).forEach { n ->
            assertEquals(n, GestureShape.resample(ell(), n).size)
        }
    }

    @Test
    fun `resampled points are evenly spaced`() {
        val points = GestureShape.resample(line(Dot(0f, 0f), Dot(640f, 0f)), 33)
        val gaps = (1 until points.size).map { hypot(points[it].x - points[it - 1].x, points[it].y - points[it - 1].y) }
        val first = gaps.first()
        gaps.forEach { assertEquals(first, it, 0.5f) }
    }

    @Test
    fun `a tap is not a stroke`() {
        // Every accidental touch on the canvas arrives here. Without this the
        // nearest gesture to a dot fires whenever somebody brushes the screen.
        assertFalse(GestureShape.isLongEnough(listOf(Dot(10f, 10f), Dot(12f, 11f))))
        assertTrue(GestureShape.isLongEnough(swipeUp()))
    }

    @Test
    fun `a stroke with no length normalises to nothing rather than crashing`() {
        assertTrue(GestureShape.normalise(emptyList()).isEmpty())
        assertTrue(GestureShape.normalise(listOf(Dot(5f, 5f))).isEmpty())
        // A held finger: many samples, all the same place.
        val held = List(30) { Dot(5f, 5f) }
        assertEquals(GestureShape.POINTS, GestureShape.normalise(held).size)
    }

    @Test
    fun `comparing strokes of different lengths is a zero, not a crash`() {
        assertEquals(0f, GestureShape.similarity(norm(ell()), emptyList()), 0f)
    }

    // ------------------------------------------------------------- teaching --

    @Test
    fun `the best of several examples is what counts`() {
        // Taught with a wide one and a narrow one, drawn narrow: the wide
        // example must not drag the score down. Averaging would punish a
        // gesture for having been taught tolerance.
        val templates = listOf(norm(caret()), norm(ell()))
        assertTrue(GestureShape.bestScore(norm(ell()), templates) > 0.99f)
    }

    @Test
    fun `examples that are not the same shape are caught while teaching`() {
        // Said while the person can still redraw it. Two drawings that share a
        // name and not a shape make a gesture that fires on neither.
        assertFalse(GestureShape.agreeWithEachOther(listOf(norm(ell()), norm(circle()))))
        assertTrue(GestureShape.agreeWithEachOther(listOf(norm(ell()), norm(ell()))))
        assertTrue(GestureShape.agreeWithEachOther(listOf(norm(ell()))))
    }

    @Test
    fun `a hand that wavers is still teaching one shape`() {
        // The same L, leaning and with a short foot: 0.75, which the recogniser
        // would refuse as a match and which teaching has no business refusing.
        // Held to a band rather than a floor — a rule this loose stops being a
        // rule, and the point is that the second example be *different*, not
        // that anything goes.
        val wavered = norm(shakyEll(lean = 60f, foot = 190f))
        val score = GestureShape.similarity(norm(ell()), wavered)
        assertTrue("a wavering L scored $score", score < GestureShape.MATCH)
        assertTrue("a wavering L scored $score", score >= GestureShape.AGREE)
        assertTrue(GestureShape.agreeWithEachOther(listOf(norm(ell()), wavered)))
    }

    @Test
    fun `a drawing that lost the shape is still refused`() {
        // Loosening the rule is not removing it. This one has leant so far and
        // lost so much foot that it is on its way to being a slash.
        val gone = norm(shakyEll(lean = 100f, foot = 160f))
        assertTrue(GestureShape.similarity(norm(ell()), gone) < GestureShape.AGREE)
        assertFalse(GestureShape.agreeWithEachOther(listOf(norm(ell()), gone)))
    }

    @Test
    fun `teaching is looser than recognising`() {
        // Two questions, two numbers. Firing the wrong gesture is a mistake the
        // user did not ask for; accepting a slightly wide example is one they
        // can see and redraw — and one the best-of-several scoring absorbs.
        assertTrue(GestureShape.AGREE < GestureShape.MATCH)
    }

    @Test
    fun `a new gesture too close to an old one is caught`() {
        // Two gestures that score alike are not two gestures: which one wins is
        // then decided by drawing noise.
        val saved = listOf(norm(swipeUp()))
        assertTrue(GestureShape.collidesWith(listOf(norm(swipeUp())), saved))
        assertFalse(GestureShape.collidesWith(listOf(norm(circle())), saved))
    }

    @Test
    fun `a near tie is not decisive`() {
        assertTrue(GestureShape.isDecisive(best = 0.95f, runnerUp = 0.60f))
        assertFalse(GestureShape.isDecisive(best = 0.95f, runnerUp = 0.945f))
        assertFalse(GestureShape.isDecisive(best = 0.50f, runnerUp = 0.10f))
    }
}
