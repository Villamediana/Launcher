package com.aura.launcher

import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.Gesture
import com.aura.launcher.gestures.GestureAction
import com.aura.launcher.gestures.GestureLearning
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.gestures.saved
import com.aura.launcher.gestures.matchGesture
import com.aura.launcher.gestures.GestureMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gesture keeping the strokes that worked.
 *
 * The feature is one line of consequence — a template list gets longer — and
 * the whole of it is in what it *refuses*. An over-eager version of this does
 * not fail loudly: it pulls two gestures together over a fortnight, or teaches
 * a shape to accept the sloppiest drawing it ever got away with, and the user
 * reports that gestures "stopped working". So the refusals are the tests.
 */
class GestureLearningTest {

    private fun line(from: Dot, to: Dot, steps: Int = 40) = (0..steps).map { i ->
        val t = i.toFloat() / steps
        Dot(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    private fun up() = line(Dot(500f, 900f), Dot(500f, 300f))
    private fun right() = line(Dot(200f, 600f), Dot(800f, 600f))
    private fun ell() =
        line(Dot(200f, 200f), Dot(200f, 800f), 20) + line(Dot(200f, 800f), Dot(800f, 800f), 20)

    /** The same stroke with a lean on it, which is what a hand actually does. */
    private fun leaningUp(drift: Float) = line(Dot(500f, 900f), Dot(500f + drift, 300f))

    private fun gesture(name: String, vararg drawings: List<Dot>) = Gesture(
        id = name,
        name = name,
        templates = drawings.map { GestureShape.normalise(it).saved() },
        actions = listOf(GestureAction.ShowGestures),
        taught = drawings.size,
    )

    private fun shape(stroke: List<Dot>) = GestureShape.normalise(stroke)

    /** What the surfaces do: match, then offer the stroke back. */
    private fun offer(drawn: List<Dot>, all: List<Gesture>): Gesture? {
        val match = matchGesture(drawn, all) as? GestureMatch.Hit ?: return null
        return GestureLearning.reinforced(match.gesture, shape(drawn), match.score, all)
    }

    // ------------------------------------------------------------- it keeps --

    @Test
    fun `a confident stroke becomes another example`() {
        val before = gesture("up", up())
        val grown = GestureLearning.reinforced(before, shape(leaningUp(100f)), 0.95f, listOf(before))
        assertNotNull(grown)
        assertEquals(2, grown!!.templates.size)
        // The taught one is still first, and still counted as taught.
        assertEquals(1, grown.taught)
        assertEquals(before.templates.first(), grown.templates.first())
    }

    @Test
    fun `the drift is what gets learned`() {
        // A hand that has moved far enough that the original barely matches.
        val taught = gesture("up", up())
        val today = leaningUp(150f)
        val scoreBefore = GestureShape.bestScore(shape(today), taught.shapes())

        val grown = GestureLearning.reinforced(taught, shape(today), 0.95f, listOf(taught))!!
        val scoreAfter = GestureShape.bestScore(shape(today), grown.shapes())

        assertTrue("learning should not make today's stroke worse", scoreAfter >= scoreBefore)
        assertEquals(1f, scoreAfter, 0.001f)
    }

    @Test
    fun `a real match offers a real stroke`() {
        val up = gesture("up", up())
        val ell = gesture("ell", ell())
        val grown = offer(leaningUp(120f), listOf(up, ell))
        assertNotNull("a plainly-matched stroke should be kept", grown)
        assertEquals("up", grown!!.name)
        assertEquals(2, grown.templates.size)
    }

    // ---------------------------------------------------------- it refuses --

    @Test
    fun `a stroke matched with a shrug is not kept`() {
        val before = gesture("up", up())
        // Just over MATCH: enough to act on, not enough to enshrine.
        assertNull(GestureLearning.reinforced(before, shape(leaningUp(40f)), 0.79f, listOf(before)))
    }

    @Test
    fun `a stroke that adds nothing is not kept`() {
        val before = gesture("up", up())
        assertNull(
            "an identical drawing buys no tolerance and costs a slot",
            GestureLearning.reinforced(before, shape(up()), 1f, listOf(before)),
        )
    }

    @Test
    fun `a stroke that also matches another gesture is not kept`() {
        // A pair that only became confusable later — a gesture edited, or one
        // drawn halfway between two, which is the case DECISIVE_MARGIN exists
        // for. The stroke ran the winner; it must not become an example of it.
        val mine = gesture("up", up())
        val neighbour = gesture("leaning", leaningUp(300f))
        val candidate = shape(leaningUp(150f))
        assertTrue(
            "the premise: this stroke matches the neighbour too",
            GestureShape.bestScore(candidate, neighbour.shapes()) >= GestureShape.MATCH,
        )
        assertTrue(
            "the premise: and it is not simply a copy of what this gesture has",
            GestureShape.bestScore(candidate, mine.shapes()) < GestureLearning.REDUNDANT,
        )
        assertNull(GestureLearning.reinforced(mine, candidate, 0.96f, listOf(mine, neighbour)))
    }

    @Test
    fun `a disabled neighbour still blocks a colliding stroke`() {
        // Switched off is not gone. Learning a collision while it sleeps leaves
        // the user an ambiguous pair waiting for the day they switch it back on.
        val mine = gesture("up", up())
        val asleep = gesture("leaning", leaningUp(300f)).copy(enabled = false)
        assertNull(
            GestureLearning.reinforced(mine, shape(leaningUp(150f)), 0.96f, listOf(mine, asleep)),
        )
    }

    @Test
    fun `an empty stroke is not kept`() {
        val before = gesture("up", up())
        assertNull(GestureLearning.reinforced(before, emptyList(), 1f, listOf(before)))
    }

    // ------------------------------------------------------------ the ring --

    @Test
    fun `a full gesture replaces its oldest learned example, never a taught one`() {
        var g = gesture("up", up(), leaningUp(100f), leaningUp(200f))
        val anchors = g.templates.toList()

        // Every drawing here is a hundred pixels of lean from the nearest
        // other, which scores about 0.94 — plainly the same gesture, plainly
        // not a copy, so nothing is refused as redundant on the way in.
        val learned = listOf(-100f, -200f, 300f, 400f, -300f).map { shape(leaningUp(it)) }
        for (stroke in learned) {
            g = GestureLearning.reinforced(g, stroke, 0.95f, listOf(g))
                ?: error("expected ${stroke.size}-point stroke to be kept")
        }
        assertEquals(GestureLearning.MAX_TEMPLATES, g.templates.size)

        // One more. The oldest learned drawing goes; the three taught ones stay.
        val next = shape(leaningUp(500f))
        val rolled = GestureLearning.reinforced(g, next, 0.95f, listOf(g))!!
        assertEquals(GestureLearning.MAX_TEMPLATES, rolled.templates.size)
        assertEquals("the taught drawings are never replaced", anchors, rolled.templates.take(3))
        assertEquals(next.saved(), rolled.templates.last())
        assertTrue(
            "the oldest learned example is the one that left",
            !rolled.templates.contains(learned.first().saved()),
        )
    }

    @Test
    fun `a gesture from before any of this counts every drawing as taught`() {
        // taught = 0 is what an old gestures.json deserialises to, and the
        // honest reading is that a human drew all of them.
        val old = gesture("up", up(), leaningUp(100f)).copy(taught = 0)
        assertEquals(2, GestureLearning.anchorCount(old))

        val grown = GestureLearning.reinforced(old, shape(leaningUp(250f)), 0.95f, listOf(old))!!
        assertEquals(3, grown.templates.size)
        assertEquals(old.templates, grown.templates.take(2))
    }

    @Test
    fun `an old gesture already holding eight learns nothing`() {
        val full = Gesture(
            id = "up",
            name = "up",
            templates = (0 until GestureLearning.MAX_TEMPLATES).map {
                shape(leaningUp(it * 18f)).saved()
            },
            actions = listOf(GestureAction.ShowGestures),
            taught = 0,
        )
        assertNull(
            "nothing in it may be replaced, and there is no room to add",
            GestureLearning.reinforced(full, shape(leaningUp(-70f)), 0.95f, listOf(full)),
        )
    }

    @Test
    fun `learning never makes a gesture match its neighbour`() {
        // The slow failure this feature could cause, run forward: fifty strokes
        // leaning further and further, with a second gesture over there.
        var mine = gesture("up", up())
        val neighbour = gesture("across", right())
        repeat(50) { i ->
            val drawn = leaningUp(i * 12f)
            val match = matchGesture(drawn, listOf(mine, neighbour))
            if (match is GestureMatch.Hit && match.gesture.id == "up") {
                mine = GestureLearning.reinforced(mine, shape(drawn), match.score, listOf(mine, neighbour))
                    ?: mine
            }
        }
        // Whatever it learned, the neighbour's own drawing still belongs to the
        // neighbour.
        val verdict = matchGesture(right(), listOf(mine, neighbour))
        assertTrue(verdict is GestureMatch.Hit)
        assertEquals("across", (verdict as GestureMatch.Hit).gesture.name)
    }
}
