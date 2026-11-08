package com.aura.launcher

import com.aura.launcher.ui.home.GestureVeil
import com.aura.launcher.ui.home.veilAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One dark, handed from the drawing to the list.
 *
 * A gesture that opens the list of gestures crosses from one veil to another
 * mid-motion. Whatever the second one does on the way in is seen against a
 * screen the first one has already darkened, so "fade in from nothing" is not
 * a neutral default here — it is the wallpaper coming back for a moment behind
 * a menu that has already arrived.
 */
class GestureVeilTest {

    @Test
    fun `a veil handed over starts where the last one left off`() {
        // Not 0f at presence 0f. That single frame is the whole complaint: the
        // room brightening at the exact moment the menu appears.
        assertEquals(GestureVeil.ALPHA, veilAlpha(handed = true, presence = 0f), 0.0001f)
        assertEquals(GestureVeil.ALPHA, veilAlpha(handed = true, presence = 0.5f), 0.0001f)
        assertEquals(GestureVeil.ALPHA, veilAlpha(handed = true, presence = 1f), 0.0001f)
    }

    @Test
    fun `a veil opened cold arrives with everything else`() {
        // From a shortcut or a scheduled task there is no drawing behind it and
        // nothing to match, so it fades like any other panel.
        assertEquals(0f, veilAlpha(handed = false, presence = 0f), 0.0001f)
        assertEquals(GestureVeil.ALPHA / 2f, veilAlpha(handed = false, presence = 0.5f), 0.0001f)
        assertEquals(GestureVeil.ALPHA, veilAlpha(handed = false, presence = 1f), 0.0001f)
    }

    @Test
    fun `both ways end at the same dark`() {
        // The bug was two veils at two strengths. However the list was opened,
        // the room it settles into has to be the same room.
        assertEquals(
            veilAlpha(handed = true, presence = 1f),
            veilAlpha(handed = false, presence = 1f),
            0.0001f,
        )
    }

    @Test
    fun `the room is dark while somebody is holding it`() {
        val home = Any()
        val drawer = Any()

        assertFalse(GestureVeil.up)
        GestureVeil.hold(home)
        assertTrue(GestureVeil.up)

        // Two layers exist at once — the home screen's and the drawer's. One
        // letting go while the other is still drawing must not turn the room
        // back on.
        GestureVeil.hold(drawer)
        GestureVeil.release(home)
        assertTrue(GestureVeil.up)

        GestureVeil.release(drawer)
        assertFalse(GestureVeil.up)
    }

    @Test
    fun `letting go twice is letting go once`() {
        // A layer releases on dispose whether or not it was holding, so the
        // count has to be a set rather than a tally that can go negative and
        // leave the screen dark for good.
        val one = Any()
        GestureVeil.hold(one)
        GestureVeil.release(one)
        GestureVeil.release(one)
        assertFalse(GestureVeil.up)
    }
}
