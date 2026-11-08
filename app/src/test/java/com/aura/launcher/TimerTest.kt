package com.aura.launcher

import com.aura.launcher.timer.TimerMode
import com.aura.launcher.timer.TimerState
import com.aura.launcher.timer.Timers
import com.aura.launcher.timer.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stopwatch and a countdown, as arithmetic.
 *
 * Nothing in the widget counts: the face is worked out from what was stored and
 * the clock on the wall, which is what lets a run survive the launcher being
 * killed. That makes every interesting question here answerable without a
 * device — and it makes the pause-and-resume arithmetic, which is where a timer
 * is usually wrong, something a test can pin down exactly.
 */
class TimerTest {

    private val t0 = 1_700_000_000_000L
    private fun sec(n: Long) = n * 1_000L
    private fun min(n: Long) = n * 60_000L

    // ------------------------------------------------------- the stopwatch --

    @Test
    fun `a fresh timer shows nothing and is idle`() {
        val state = TimerState()
        assertTrue(state.isIdle)
        assertFalse(state.running)
        assertEquals(0L, state.display(t0))
    }

    @Test
    fun `a running stopwatch counts from when it started`() {
        val state = Timers.start(TimerState(), t0)
        assertTrue(state.running)
        assertEquals(sec(30), state.elapsed(t0 + sec(30)))
    }

    @Test
    fun `pausing banks what it counted and stops`() {
        var state = Timers.start(TimerState(), t0)
        state = Timers.pause(state, t0 + sec(30))

        assertFalse(state.running)
        assertEquals(sec(30), state.banked)
        // And time passing while paused changes nothing.
        assertEquals(sec(30), state.elapsed(t0 + min(10)))
    }

    @Test
    fun `resuming adds to what was banked rather than starting over`() {
        // The arithmetic a stopwatch is usually wrong about.
        var state = Timers.start(TimerState(), t0)
        state = Timers.pause(state, t0 + sec(30))
        state = Timers.start(state, t0 + min(5))

        assertEquals(sec(30), state.elapsed(t0 + min(5)))
        assertEquals(sec(40), state.elapsed(t0 + min(5) + sec(10)))
    }

    @Test
    fun `a run survives being rebuilt from what was stored`() {
        // The whole reason nothing counts in memory: the process can be shot
        // between any two frames, and the number has to come back right.
        val state = Timers.start(TimerState(), t0)
        val fromDisk = TimerState(
            mode = state.mode,
            startedAt = state.startedAt,
            banked = state.banked,
        )
        assertEquals(min(3), fromDisk.elapsed(t0 + min(3)))
    }

    @Test
    fun `starting twice does not restart the clock`() {
        val state = Timers.start(TimerState(), t0)
        val again = Timers.start(state, t0 + sec(10))
        assertEquals(state, again)
        assertEquals(sec(10), again.elapsed(t0 + sec(10)))
    }

    @Test
    fun `pausing something already paused changes nothing`() {
        val state = Timers.pause(TimerState(), t0)
        assertEquals(TimerState(), state)
    }

    @Test
    fun `reset clears the clock but keeps the length that was chosen`() {
        var state = Timers.setDuration(TimerState(), min(10))
        state = Timers.start(state, t0)
        state = Timers.reset(state)

        assertTrue(state.isIdle)
        assertEquals(min(10), state.duration)
        assertEquals(TimerMode.COUNTDOWN, state.mode)
    }

    // -------------------------------------------------------- the countdown --

    @Test
    fun `a countdown counts towards zero`() {
        var state = Timers.setDuration(TimerState(), min(5))
        state = Timers.start(state, t0)

        assertEquals(min(5), state.display(t0))
        assertEquals(min(4), state.display(t0 + min(1)))
        assertEquals(0L, state.display(t0 + min(5)))
    }

    @Test
    fun `a countdown never goes negative`() {
        // "-00:07" is not a number anybody reads as "it is done".
        var state = Timers.setDuration(TimerState(), min(1))
        state = Timers.start(state, t0)
        assertEquals(0L, state.remaining(t0 + min(30)))
    }

    @Test
    fun `it is done the moment it reaches its own length`() {
        var state = Timers.setDuration(TimerState(), min(1))
        state = Timers.start(state, t0)

        assertFalse(state.isDone(t0 + sec(59)))
        assertTrue(state.isDone(t0 + sec(60)))
    }

    @Test
    fun `finishing parks it at zero instead of letting it run on`() {
        var state = Timers.setDuration(TimerState(), min(1))
        state = Timers.start(state, t0)
        state = Timers.finish(state, t0 + sec(60))

        assertFalse(state.running)
        assertEquals(0L, state.display(t0 + min(10)))
        assertTrue(state.finishedAt > 0L)
    }

    @Test
    fun `a stopwatch is never done`() {
        val state = Timers.start(TimerState(), t0)
        assertFalse(state.isDone(t0 + min(60)))
        assertNull(Timers.endsAt(state, t0))
    }

    @Test
    fun `the alarm is set for the moment it runs out, pause included`() {
        var state = Timers.setDuration(TimerState(), min(5))
        state = Timers.start(state, t0)
        assertEquals(t0 + min(5), Timers.endsAt(state, t0))

        // Paused for an hour: nothing to wake up for, and when it resumes the
        // alarm moves with it rather than going off at the original time.
        state = Timers.pause(state, t0 + min(2))
        assertNull(Timers.endsAt(state, t0 + min(2)))

        val resumedAt = t0 + min(62)
        state = Timers.start(state, resumedAt)
        assertEquals(resumedAt + min(3), Timers.endsAt(state, resumedAt))
    }

    @Test
    fun `a finished countdown has no alarm left to book`() {
        var state = Timers.setDuration(TimerState(), min(1))
        state = Timers.start(state, t0)
        state = Timers.finish(state, t0 + min(1))
        assertNull(Timers.endsAt(state, t0 + min(1)))
    }

    @Test
    fun `a length is clamped to something a clock can show`() {
        assertEquals(Timers.MIN_DURATION, Timers.setDuration(TimerState(), 0L).duration)
        assertEquals(Timers.MAX_DURATION, Timers.setDuration(TimerState(), min(60 * 48)).duration)
    }

    @Test
    fun `choosing a length remembers it for next time`() {
        val state = Timers.setDuration(TimerState(), min(3))
        val switched = Timers.setMode(state, TimerMode.STOPWATCH)
        assertEquals(min(3), switched.lastDuration)
    }

    // ------------------------------------------------------------- switching --

    @Test
    fun `switching modes always starts from zero`() {
        // Carrying twelve seconds of stopwatch into a countdown would start it
        // already part-spent, which reads as a bug every time.
        var state = Timers.start(TimerState(), t0)
        state = Timers.pause(state, t0 + sec(12))
        state = Timers.setMode(state, TimerMode.COUNTDOWN)

        assertTrue(state.isIdle)
        assertEquals(0L, state.banked)
    }

    @Test
    fun `switching to the mode it is already in leaves a run alone`() {
        val state = Timers.start(TimerState(), t0)
        assertEquals(state, Timers.setMode(state, TimerMode.STOPWATCH))
    }

    @Test
    fun `setting a new length abandons the run rather than half-keeping it`() {
        var state = Timers.setDuration(TimerState(), min(5))
        state = Timers.start(state, t0)
        state = Timers.setDuration(state, min(10))

        assertTrue(state.isIdle)
        assertEquals(min(10), state.display(t0))
    }

    // ---------------------------------------------------------------- the face --

    @Test
    fun `the face reads mm ss until an hour and h mm ss after`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(sec(9)))
        assertEquals("01:30", formatDuration(sec(90)))
        assertEquals("59:59", formatDuration(sec(3599)))
        assertEquals("1:00:00", formatDuration(sec(3600)))
        assertEquals("2:05:03", formatDuration(sec(7503)))
    }

    @Test
    fun `a countdown rounds up so the last second is not shown as zero`() {
        // With truncation, a five-minute timer shows 04:59 for the whole of its
        // first second and 00:00 while it is still running.
        assertEquals("05:00", formatDuration(min(5), roundUp = true))
        assertEquals("05:00", formatDuration(min(5) - 1, roundUp = true))
        assertEquals("00:01", formatDuration(1, roundUp = true))
        assertEquals("00:00", formatDuration(0, roundUp = true))
    }

    @Test
    fun `a negative reading is shown as zero rather than as a minus`() {
        assertEquals("00:00", formatDuration(-5_000L))
    }
}
