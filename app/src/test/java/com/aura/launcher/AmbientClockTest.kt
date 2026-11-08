package com.aura.launcher

import com.aura.launcher.ui.theme.Ambient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen's moving things wake on the same ticks, so they share a frame. */
class AmbientClockTest {
    @Test
    fun `loops that start at different moments wake at the same one`() {
        // The orb, the sky and the wallpaper, each finishing its frame at a different time.
        val start = 1000L / Ambient.FPS * 24_400
        val wakes = listOf(start + 1, start + 15, start + 30).map { it + Ambient.untilNextTick(it, Ambient.FPS) }
        assertEquals(1, wakes.toSet().size)
        assertEquals(0L, wakes.first() % (1000L / Ambient.FPS))
    }

    @Test
    fun `a tick is never zero, and the saving rate is half`() {
        val period = 1000L / Ambient.FPS
        assertEquals(period, Ambient.untilNextTick(period * 7, Ambient.FPS))
        for (now in 0L until 200L) assertTrue(Ambient.untilNextTick(now, Ambient.FPS) in 1..period)
        assertEquals(Ambient.FPS / 2, Ambient.SAVING_FPS)
    }

    @Test
    fun `covered by a game, the home screen waits until it is uncovered`() = kotlinx.coroutines.runBlocking {
        val uncover = Ambient.cover()
        val ticked = kotlinx.coroutines.withTimeoutOrNull(300) { Ambient.nextTick(Ambient.FPS); true }
        assertEquals("no tick while covered", null, ticked)
        uncover()
        uncover() // twice is harmless
        val after = kotlinx.coroutines.withTimeoutOrNull(300) { Ambient.nextTick(Ambient.FPS); true }
        assertEquals(true, after)
    }

    @Test
    fun `left untouched the home screen calms down, and a touch brings it back`() {
        assertEquals(Ambient.FPS, Ambient.rate(Ambient.FPS, 2_000))
        assertEquals(Ambient.IDLE_FPS, Ambient.rate(Ambient.FPS, Ambient.IDLE_MS))
        // A loop already slower than the idle rate keeps its own.
        assertEquals(8, Ambient.rate(8, Ambient.IDLE_MS * 4))
    }
}
