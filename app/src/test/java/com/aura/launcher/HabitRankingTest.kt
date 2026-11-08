package com.aura.launcher

import com.aura.launcher.data.SettingsRepository.Habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind "most used", which is the whole of the feature.
 *
 * A count would have been three lines and wrong: it answers "what has been
 * opened most since the launcher was installed", and a row on a home screen is
 * asking "what does this person reach for now". These are the cases where the
 * two answers differ.
 */
class HabitRankingTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `a launch is worth one at the moment it happens`() {
        val habit = Habit("app", 0.0, now).used(now)
        assertEquals(1.0, habit.scoreAt(now), 0.0001)
    }

    @Test
    fun `a fortnight halves what a launch was worth`() {
        val habit = Habit("app", 1.0, now)
        assertEquals(0.5, habit.scoreAt(now + 14 * day), 0.001)
        assertEquals(0.25, habit.scoreAt(now + 28 * day), 0.001)
    }

    @Test
    fun `a habit picked up this month overtakes one dropped last year`() {
        // The case a plain count gets wrong, and the reason for all of this:
        // 400 launches of something abandoned a year ago against three weeks
        // of daily use of something new.
        var abandoned = Habit("abandoned", 0.0, now - 400 * day)
        repeat(400) { abandoned = abandoned.used(now - 400 * day) }

        var current = Habit("current", 0.0, now - 21 * day)
        for (d in 21 downTo 0) current = current.used(now - d * day)

        assertTrue(
            "abandoned=${abandoned.scoreAt(now)} current=${current.scoreAt(now)}",
            current.scoreAt(now) > abandoned.scoreAt(now),
        )
    }

    @Test
    fun `using something every day keeps it near the top rather than climbing forever`() {
        // Daily use converges instead of running away, so a long-standing habit
        // never becomes impossible to displace.
        var habit = Habit("daily", 0.0, now - 365 * day)
        for (d in 365 downTo 0) habit = habit.used(now - d * day)
        assertTrue("score ran away: ${habit.scoreAt(now)}", habit.scoreAt(now) < 30.0)
        assertTrue("score too low: ${habit.scoreAt(now)}", habit.scoreAt(now) > 10.0)
    }

    @Test
    fun `stopping retires an app without deleting it`() {
        var habit = Habit("holiday app", 0.0, now - 200 * day)
        repeat(50) { habit = habit.used(now - 200 * day) }
        assertTrue(habit.scoreAt(now) < 0.01)
        // Still in the table, so one launch brings it straight back.
        assertTrue(habit.used(now).scoreAt(now) >= 1.0)
    }

    @Test
    fun `the table survives a round trip through its stored form`() {
        val habits = listOf(
            Habit("com.example/.Main", 3.5, now),
            // A key with an '=' in it, which the parser has to split from the
            // right to get back intact.
            Habit("com.example/.Odd=Name", 1.25, now - day),
        )
        val parsed = Habit.parse(Habit.write(habits))
        assertEquals(habits, parsed)
    }

    @Test
    fun `a corrupt line is dropped rather than taking the table with it`() {
        val parsed = Habit.parse("good=1.0:${now}\nrubbish\nalso bad=x:y\n")
        assertEquals(1, parsed.size)
        assertEquals("good", parsed.first().key)
    }
}
