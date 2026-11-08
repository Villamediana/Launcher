package com.aura.launcher

import com.aura.launcher.data.HourPoint
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.ui.home.NowBrief
import com.aura.launcher.ui.home.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class SkyAndNowTest {

    private val sp = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = sp) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ sky --

    @Test
    fun `sao paulo sunrise and sunset are within a few minutes of the almanac`() {
        // Almanac for São Paulo, 2026-09-24: sunrise ~05:51, sunset ~17:59 (BRT).
        val day = SkyMath.sunDay(-23.55, -46.63, at(2026, 9, 24, 12, 0))
        assertTrue(abs(day.rise!! - at(2026, 9, 24, 5, 51)) < 6 * 60_000L)
        assertTrue(abs(day.set!! - at(2026, 9, 24, 17, 59)) < 6 * 60_000L)
    }

    @Test
    fun `polar night has no sunrise`() {
        val day = SkyMath.sunDay(78.2, 15.6, at(2026, 12, 21, 12, 0, ZoneId.of("UTC")))
        assertEquals(null, day.rise)
    }

    @Test
    fun `a known full moon reads as full`() {
        // Full moon: 2024-04-23 23:49 UTC.
        val age = SkyMath.moonAge(at(2024, 4, 23, 23, 49, ZoneId.of("UTC")))
        assertTrue("age $age", abs(age - 0.5) < 0.03)
        assertTrue(SkyMath.moonLit(age) > 0.97)
    }

    @Test
    fun `a known new moon reads as new`() {
        // New moon: 2024-04-08 18:21 UTC (the eclipse).
        val age = SkyMath.moonAge(at(2024, 4, 8, 18, 21, ZoneId.of("UTC")))
        assertTrue("age $age", age < 0.03 || age > 0.97)
    }

    // ------------------------------------------------------------------ now --

    private val now = at(2026, 9, 24, 13, 0)
    private fun event(id: Long, minutesFromNow: Int, location: String? = null) =
        UpcomingEvent(id, "Dentista", now + minutesFromNow * 60_000L, now + (minutesFromNow + 60) * 60_000L, false, location)

    @Test
    fun `with a route, it says when to leave, not when the event is`() {
        val items = NowBrief.build(now, listOf(event(1, 60, "Rua X")), mapOf(1L to 20 * 60.0), emptyList(), emptyList(), null)
        val leave = items.single() as NowBrief.Leave
        // 60 min away, 20 min drive, 5 min margin → leave at 13:35.
        assertEquals(now + 35 * 60_000L, leave.leaveAt)
        assertEquals(false, leave.now)
    }

    @Test
    fun `when the leave time has passed it says leave now`() {
        val items = NowBrief.build(now, listOf(event(1, 20, "Rua X")), mapOf(1L to 30 * 60.0), emptyList(), emptyList(), null)
        assertTrue((items.single() as NowBrief.Leave).now)
    }

    @Test
    fun `a late dose outranks everything`() {
        val late = NowBrief.Due("Dipirona", now - 25 * 60_000L)
        val items = NowBrief.build(now, listOf(event(1, 30)), emptyMap(), emptyList(), listOf(late), null)
        assertTrue(items.first() is NowBrief.LateDose)
        assertEquals(25, (items.first() as NowBrief.LateDose).minutesLate)
    }

    @Test
    fun `rain coming is said, rain already falling is not`() {
        val dry = listOf(HourPoint(now - 30 * 60_000L, 20, 0), HourPoint(now + 2 * 3_600_000L, 18, 63))
        assertTrue(NowBrief.build(now, emptyList(), emptyMap(), dry, emptyList(), null).single() is NowBrief.Rain)
        val wet = listOf(HourPoint(now - 30 * 60_000L, 20, 61), HourPoint(now + 2 * 3_600_000L, 18, 63))
        assertTrue(NowBrief.build(now, emptyList(), emptyMap(), wet, emptyList(), null).isEmpty())
    }

    @Test
    fun `events far off and all-day events stay out`() {
        assertTrue(NowBrief.build(now, listOf(event(1, 13 * 60)), emptyMap(), emptyList(), emptyList(), null).isEmpty())
        val allDay = event(2, 30).copy(allDay = true)
        assertTrue(NowBrief.build(now, listOf(allDay), emptyMap(), emptyList(), emptyList(), null).isEmpty())
    }

    @Test
    fun `never more than four lines, most pressing first`() {
        val late = NowBrief.Due("Dipirona", now - 25 * 60_000L)
        val next = NowBrief.Due("Dipirona", now + 30 * 60_000L)
        val rain = listOf(HourPoint(now + 3_600_000L, 18, 63))
        val items = NowBrief.build(
            now, listOf(event(1, 30)), emptyMap(), rain, listOf(late), next,
            nextAlarm = now + 3_600_000L,
        )
        assertEquals(4, items.size)
        assertTrue(items[0] is NowBrief.LateDose)
        assertTrue(items[1] is NowBrief.Event)
    }

    @Test
    fun `a dose missed two hours ago is not late any more, it is gone`() {
        val old = NowBrief.Due("Dipirona", now - 2 * 3_600_000L)
        assertTrue(NowBrief.build(now, emptyList(), emptyMap(), emptyList(), listOf(old), null).isEmpty())
    }

    @Test
    fun `the next event later today still shows, not only the next three hours`() {
        val items = NowBrief.build(now, listOf(event(1, 5 * 60)), emptyMap(), emptyList(), emptyList(), null)
        assertTrue(items.single() is NowBrief.Event)
    }

    @Test
    fun `an event under way is said with when it ends`() {
        val ongoing = UpcomingEvent(9, "Reunião", now - 10 * 60_000L, now + 50 * 60_000L, false)
        val item = NowBrief.build(now, listOf(ongoing), emptyMap(), emptyList(), emptyList(), null).single()
        assertEquals(NowBrief.Ongoing("Reunião", now + 50 * 60_000L), item)
    }

    @Test
    fun `alarm and running timer come in`() {
        val timer = com.aura.launcher.timer.TimerState(
            mode = com.aura.launcher.timer.TimerMode.COUNTDOWN,
            startedAt = now - 60_000L,
            duration = 5 * 60_000L,
        )
        val items = NowBrief.build(
            now, emptyList(), emptyMap(), emptyList(), emptyList(), null,
            nextAlarm = now + 8 * 3_600_000L, timer = timer,
        )
        assertTrue(items.any { it is NowBrief.Timer && it.countdown })
        assertTrue(items.any { it is NowBrief.Alarm })
    }
}
