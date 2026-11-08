package com.aura.launcher

import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.ui.home.buildDays
import com.aura.launcher.ui.home.midnight
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which day a given event lands under.
 *
 * Worth pinning because getting it wrong is silent: the event still shows, on
 * the wrong heading, and nothing about the screen looks broken.
 */
@RunWith(RobolectricTestRunner::class)
class DaySheetTest {

    private val today = midnight(System.currentTimeMillis())

    private fun day(offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = today
        add(Calendar.DAY_OF_YEAR, offset)
    }.timeInMillis

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = day(dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }.timeInMillis

    private fun event(dayOffset: Int, hour: Int, minute: Int = 0, title: String = "Thing") =
        UpcomingEvent(
            id = (dayOffset * 100 + hour).toLong(),
            title = title,
            startMillis = at(dayOffset, hour, minute),
            endMillis = at(dayOffset, hour, minute) + 3_600_000,
            allDay = false,
        )

    @Test
    fun `late and early events land on the days a person would put them on`() {
        // 23:50 tonight and 00:10 tomorrow are ten hours apart in the feed and
        // on either side of a heading to a reader. Grouping on anything other
        // than local midnight puts one of them under the wrong day.
        val days = buildDays(listOf(event(0, 23, 50, "tonight"), event(1, 0, 10, "tomorrow")))
        val todayRow = days.first { it.daysFromToday == 0 }
        val tomorrowRow = days.first { it.daysFromToday == 1 }
        assertEquals(listOf("tonight"), todayRow.events.map { it.title })
        assertEquals(listOf("tomorrow"), tomorrowRow.events.map { it.title })
    }

    @Test
    fun `today is always present, even with nothing on it`() {
        // It carries the "New event" row, so a blank day still has a reason to
        // be drawn.
        val days = buildDays(emptyList())
        assertEquals(1, days.size)
        assertTrue(days.single().isToday)
    }

    @Test
    fun `a day with nothing on it is dropped`() {
        // Otherwise the sheet is a week of empty headings.
        val days = buildDays(listOf(event(3, 9)))
        assertEquals(listOf(0, 3), days.map { it.daysFromToday })
    }


    @Test
    fun `events within a day come out in time order`() {
        // The calendar query sorts, but grouping does not have to preserve it.
        val days = buildDays(
            listOf(event(1, 17, title = "evening"), event(1, 9, title = "morning")),
        )
        val tomorrow = days.first { it.daysFromToday == 1 }
        assertEquals(listOf("morning", "evening"), tomorrow.events.map { it.title })
    }


    @Test
    fun `nothing beyond the week is shown`() {
        // A calendar query can return more than the sheet has headings for.
        val days = buildDays(listOf(event(30, 9)))
        assertTrue(days.all { it.daysFromToday < 7 })
    }

}
