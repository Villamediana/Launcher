package com.aura.launcher

import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.Gesture
import com.aura.launcher.gestures.GestureAction
import com.aura.launcher.gestures.GestureMatch
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.gestures.matchGesture
import com.aura.launcher.gestures.saved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a drawn stroke into a decision.
 *
 * The seam between arithmetic and consequence. Above it a shape becomes an app
 * opening or a message being sent, so the interesting cases are all the ones
 * where the honest answer is "no": a palm on the canvas, a shape nobody taught,
 * two gestures that have drifted close enough to be a coin toss. Getting any of
 * those wrong means the launcher does something the user did not ask for, and
 * they will not be looking at the screen when it happens.
 */
class GestureMatchTest {

    private fun line(from: Dot, to: Dot, steps: Int = 40) = (0..steps).map { i ->
        val t = i.toFloat() / steps
        Dot(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    private fun up() = line(Dot(500f, 900f), Dot(500f, 300f))
    private fun down() = line(Dot(500f, 300f), Dot(500f, 900f))
    private fun right() = line(Dot(200f, 600f), Dot(800f, 600f))
    private fun ell() =
        line(Dot(200f, 200f), Dot(200f, 800f), 20) + line(Dot(200f, 800f), Dot(800f, 800f), 20)

    private fun gesture(name: String, vararg drawings: List<Dot>) = Gesture(
        id = name,
        name = name,
        templates = drawings.map { GestureShape.normalise(it).saved() },
        actions = listOf(GestureAction.ShowGestures),
    )

    // ------------------------------------------------------------- it works --

    @Test
    fun `a taught gesture drawn again is recognised`() {
        val match = matchGesture(up(), listOf(gesture("up", up()), gesture("ell", ell())))
        assertTrue(match is GestureMatch.Hit)
        assertEquals("up", (match as GestureMatch.Hit).gesture.name)
    }

    @Test
    fun `drawn somewhere else on the screen, still recognised`() {
        // Nobody aims at anything: the canvas is the whole screen and the
        // stroke starts wherever the finger already was.
        val elsewhere = line(Dot(90f, 1800f), Dot(90f, 1200f))
        val match = matchGesture(elsewhere, listOf(gesture("up", up())))
        assertTrue(match is GestureMatch.Hit)
    }

    @Test
    fun `the closest of several gestures wins`() {
        val gestures = listOf(gesture("up", up()), gesture("down", down()), gesture("right", right()))
        assertEquals("down", (matchGesture(down(), gestures) as GestureMatch.Hit).gesture.name)
        assertEquals("right", (matchGesture(right(), gestures) as GestureMatch.Hit).gesture.name)
    }

    // ---------------------------------------------------- it declines to act --

    @Test
    fun `a tap on the canvas does nothing`() {
        // The commonest input this will ever get, and the one where doing
        // something is worst: a palm resting on an overlay must not open an app.
        val match = matchGesture(listOf(Dot(400f, 400f), Dot(403f, 402f)), listOf(gesture("up", up())))
        assertEquals(GestureMatch.TooShort, match)
    }

    @Test
    fun `a shape nobody taught does nothing`() {
        val squiggle = (0..60).map { Dot(200f + it * 8f, 400f + (it % 7) * 40f) }
        assertEquals(GestureMatch.Unknown, matchGesture(squiggle, listOf(gesture("up", up()))))
    }

    @Test
    fun `with nothing taught, nothing happens`() {
        assertEquals(GestureMatch.Unknown, matchGesture(up(), emptyList()))
    }

    @Test
    fun `a gesture switched off is not matched`() {
        val off = gesture("up", up()).copy(enabled = false)
        assertEquals(GestureMatch.Unknown, matchGesture(up(), listOf(off)))
    }

    @Test
    fun `a gesture with no actions is not matched`() {
        // It can exist — half-built, or emptied while editing — and firing it
        // would be a gesture that appears to do nothing at all.
        val empty = gesture("up", up()).copy(actions = emptyList())
        assertEquals(GestureMatch.Unknown, matchGesture(up(), listOf(empty)))
    }

    @Test
    fun `two gestures that are the same shape are a tie, not a coin toss`() {
        // They should never both have been saved, and the builder refuses it —
        // but a gesture edited later can drift into another, and picking the
        // marginal winner would make the launcher unpredictable in a way nobody
        // could report usefully.
        val gestures = listOf(gesture("one", up()), gesture("two", up()))
        val match = matchGesture(up(), gestures)
        assertTrue("expected a tie, got $match", match is GestureMatch.Ambiguous)
        assertEquals(2, (match as GestureMatch.Ambiguous).between.size)
    }

    @Test
    fun `a clear winner is not reported as a tie`() {
        val gestures = listOf(gesture("up", up()), gesture("right", right()))
        assertTrue(matchGesture(up(), gestures) is GestureMatch.Hit)
    }

    // ------------------------------------------------------------- teaching --

    @Test
    fun `several drawings of one gesture all count`() {
        // Taught three times, drawn a fourth way. The nearest of the examples
        // is what decides, which is the whole reason for asking more than once.
        val wobbly = up().mapIndexed { i, p -> Dot(p.x + if (i % 2 == 0) 14f else -14f, p.y) }
        val taught = gesture("up", up(), wobbly, down().reversed())
        assertTrue(matchGesture(wobbly, listOf(taught)) is GestureMatch.Hit)
    }
}
