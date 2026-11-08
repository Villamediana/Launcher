package com.aura.launcher

import com.aura.launcher.todo.Todo
import com.aura.launcher.todo.TodoData
import com.aura.launcher.todo.TodoDone
import com.aura.launcher.todo.TodoMath
import com.aura.launcher.todo.TodoRepeat
import com.aura.launcher.todo.TodoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class TodoMathTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun todo(
        repeat: TodoRepeat = TodoRepeat.NONE,
        every: Int = 1,
        minute: Int = 9 * 60,
        start: String = "2026-10-05",
        days: List<Int> = emptyList(),
        end: String? = null,
    ) = Todo("t1", "Regar", startDay = start, minuteOfDay = minute, repeat = repeat, every = every, days = days, endDay = end)

    @Test
    fun `once means one slot`() {
        val t = todo()
        assertEquals(listOf(at(2026, 10, 5, 9)), TodoMath.slots(t, at(2026, 10, 1), at(2026, 10, 30), zone))
    }

    @Test
    fun `every eight hours walks from the first dose`() {
        val t = todo(TodoRepeat.HOURS, every = 8, minute = 6 * 60)
        val s = TodoMath.slots(t, at(2026, 10, 5), at(2026, 10, 6), zone)
        assertEquals(listOf(at(2026, 10, 5, 6), at(2026, 10, 5, 14), at(2026, 10, 5, 22)), s)
    }

    @Test
    fun `every three days and an end`() {
        val t = todo(TodoRepeat.DAILY, every = 3, end = "2026-10-12")
        val s = TodoMath.slots(t, at(2026, 10, 1), at(2026, 10, 30), zone)
        assertEquals(listOf(at(2026, 10, 5, 9), at(2026, 10, 8, 9), at(2026, 10, 11, 9)), s)
    }

    @Test
    fun `weekly on chosen days`() {
        // 2026-10-05 is a Monday.
        val t = todo(TodoRepeat.WEEKLY, days = listOf(1, 3))
        val s = TodoMath.slots(t, at(2026, 10, 5), at(2026, 10, 19), zone)
        assertEquals(
            listOf(at(2026, 10, 5, 9), at(2026, 10, 7, 9), at(2026, 10, 12, 9), at(2026, 10, 14, 9)),
            s,
        )
    }

    @Test
    fun `monthly keeps its date, and the 31st lands on the last day of a short month`() {
        val t = todo(TodoRepeat.MONTHLY, start = "2026-01-31")
        val s = TodoMath.slots(t, at(2026, 1, 1), at(2026, 4, 1), zone)
        assertEquals(listOf(at(2026, 1, 31, 9), at(2026, 2, 28, 9), at(2026, 3, 31, 9)), s)
    }

    @Test
    fun `the card shows what is owed, late first, and a recurring one only once`() {
        val now = at(2026, 10, 5, 15)
        val pills = todo(TodoRepeat.HOURS, every = 4, minute = 6 * 60).copy(id = "p", title = "Remédio")
        val bill = todo(start = "2026-10-03", minute = Todo.NO_TIME).copy(id = "b", title = "Conta")
        val later = todo(minute = 20 * 60).copy(id = "l", title = "Ligar")
        val rows = TodoMath.rows(TodoData(listOf(pills, bill, later)), now, zone)
        assertEquals(listOf("p", "b", "l"), rows.map { it.todo.id })
        assertTrue(rows[0].late && rows[1].late)
        assertEquals(at(2026, 10, 5, 14), rows[0].at) // the latest owed, not 06:00 and 10:00 too
        assertFalse(rows[2].late)
    }

    @Test
    fun `a ticked one-off leaves the card once the moment has passed`() {
        val now = at(2026, 10, 5, 15)
        val t = todo()
        val done = TodoData(listOf(t), listOf(TodoDone(t.id, at(2026, 10, 5, 9), now - 10 * TodoMath.MINUTE)))
        assertTrue(TodoMath.rows(done, now, zone).isEmpty())
        val justNow = TodoData(listOf(t), listOf(TodoDone(t.id, at(2026, 10, 5, 9), now - 30_000)))
        assertTrue(TodoMath.rows(justNow, now, zone).single().done)
    }

    @Test
    fun `with nothing owed today the next thing coming is shown`() {
        val now = at(2026, 10, 5, 15)
        val t = todo(start = "2026-10-07")
        assertEquals(at(2026, 10, 7, 9), TodoMath.rows(TodoData(listOf(t)), now, zone).single().at)
    }

    @Test
    fun `old medicines become recurring to-dos with their history`() {
        val dir = createTempDir()
        File(dir, "care.json").writeText(
            """{"meds":[{"id":"m1","name":"Dipirona","everyHours":6.0,"firstMinuteOfDay":480,"startDay":"2026-10-01","doseAmount":20,"doseUnit":"gotas"},
                        {"id":"m2","name":"Vitamina","everyHours":24.0,"firstMinuteOfDay":540,"startDay":"2026-10-01"}],
                "doses":[{"medId":"m1","slotAt":123,"takenAt":456}],"feeds":[]}""",
        )
        val data = TodoStore(androidx.test.core.app.ApplicationProvider.getApplicationContext()).migrateCare(File(dir, "care.json"))
        val dip = data.todos.first { it.id == "m1" }
        assertEquals("Dipirona · 20 gotas", dip.title)
        assertEquals(TodoRepeat.HOURS, dip.repeat)
        assertEquals(6, dip.every)
        val vit = data.todos.first { it.id == "m2" }
        assertEquals(TodoRepeat.DAILY, vit.repeat)
        assertEquals(1, vit.every)
        assertEquals(1, data.done.size)
        dir.deleteRecursively()
    }
}
