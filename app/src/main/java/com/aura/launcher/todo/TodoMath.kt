package com.aura.launcher.todo

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * When each to-do is due, and which of those the home screen should show.
 *
 * Pure: everything takes the data and a clock, so the rules can be tested
 * without a phone.
 */
object TodoMath {

    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    /** A timed to-do is late this long after its time, not the instant it passes. */
    const val GRACE_MS = 15 * MINUTE

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun day(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().format(DAY_FORMAT)

    fun date(day: String): LocalDate = LocalDate.parse(day, DAY_FORMAT)

    fun format(date: LocalDate): String = date.format(DAY_FORMAT)

    fun startOfDay(day: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        date(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, todo: Todo, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusMinutes(todo.minuteOfDay.coerceAtLeast(0).toLong())
            .toInstant().toEpochMilli()

    /** Every occurrence of [todo] in `[from, to)`, oldest first. */
    fun slots(todo: Todo, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        if (todo.archived || to <= from) return emptyList()
        val start = date(todo.startDay)
        val first = at(start, todo, zone)
        val end = todo.endDay?.let { startOfDay(it, zone) + DAY }
        val limit = minOf(to, end ?: to)
        if (limit <= from) return emptyList()
        val every = todo.every.coerceAtLeast(1)

        if (todo.repeat == TodoRepeat.NONE) {
            return if (first in from until limit) listOf(first) else emptyList()
        }

        if (todo.repeat == TodoRepeat.HOURS) {
            val step = every * HOUR
            val out = ArrayList<Long>()
            var t = first + (if (from > first) (from - first) / step * step else 0L)
            while (t < from) t += step
            while (t < limit && out.size < MAX_SLOTS) {
                if (t >= first) out.add(t)
                t += step
            }
            return out
        }

        val out = ArrayList<Long>()
        val fromDay = Instant.ofEpochMilli(maxOf(from, first)).atZone(zone).toLocalDate()
        var d = if (fromDay.isBefore(start)) start else fromDay.minusDays(1)
        val weekdays = todo.days.filter { it in 1..7 }.ifEmpty { listOf(start.dayOfWeek.value) }
        val startWeek = start.minusDays((start.dayOfWeek.value - 1).toLong())
        while (out.size < MAX_SLOTS) {
            val t = at(d, todo, zone)
            if (t >= limit) break
            val due = !d.isBefore(start) && when (todo.repeat) {
                TodoRepeat.DAILY -> ChronoUnit.DAYS.between(start, d) % every == 0L
                TodoRepeat.WEEKLY -> d.dayOfWeek.value in weekdays &&
                    ChronoUnit.WEEKS.between(startWeek, d.minusDays((d.dayOfWeek.value - 1).toLong())) % every == 0L
                TodoRepeat.MONTHLY -> {
                    val months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), d.withDayOfMonth(1))
                    months % every == 0L &&
                        d.dayOfMonth == minOf(start.dayOfMonth, d.lengthOfMonth())
                }
                else -> false
            }
            if (due && t >= from) out.add(t)
            d = d.plusDays(1)
        }
        return out
    }

    fun isDone(data: TodoData, todoId: String, slotAt: Long): Boolean =
        data.done.any { it.todoId == todoId && it.slotAt == slotAt }

    data class Row(
        val todo: Todo,
        val at: Long,
        val done: Boolean,
        val late: Boolean,
    )

    /**
     * What the home screen shows: one line per to-do.
     *
     * The occurrence that is owed — late ones first — or, for a moment after
     * it is ticked, the one just done, so the tap can be seen and undone.
     * Anything due later today follows. When nothing at all is owed today the
     * next thing coming is shown instead, so the card is never a blank promise.
     */
    fun rows(data: TodoData, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Row> {
        val todayStart = startOfDay(day(now, zone), zone)
        val todayEnd = todayStart + DAY
        val out = ArrayList<Row>()
        val upcoming = ArrayList<Row>()
        for (todo in data.todos) {
            if (todo.archived) continue
            val lookBack = if (todo.repeat == TodoRepeat.NONE) 366 * DAY else DAY
            val slots = slots(todo, now - lookBack, todayEnd + 7 * DAY, zone)
            val justDone = slots.lastOrNull { slot ->
                data.done.any { it.todoId == todo.id && it.slotAt == slot && now - it.doneAt < JUST_DONE_MS }
            }
            if (justDone != null) {
                out += Row(todo, justDone, done = true, late = false)
                continue
            }
            val owed = slots.filter { it < todayEnd && !isDone(data, todo.id, it) }
            // A recurring one owes only its latest occurrence: yesterday's
            // missed watering is not two waterings today.
            val pick = if (todo.repeat == TodoRepeat.NONE) owed.firstOrNull() else {
                owed.lastOrNull { it <= now } ?: owed.firstOrNull()
            }
            if (pick != null) {
                val late = if (todo.timed) pick < now - GRACE_MS else pick < todayStart
                out += Row(todo, pick, done = false, late = late)
            } else {
                slots.firstOrNull { it >= todayEnd && !isDone(data, todo.id, it) }
                    ?.let { upcoming += Row(todo, it, done = false, late = false) }
            }
        }
        val sorted = out.sortedWith(
            compareByDescending<Row> { it.late }
                .thenBy { if (it.todo.timed) 0 else 1 }
                .thenBy { it.at },
        )
        return sorted.ifEmpty { upcoming.sortedBy { it.at }.take(1) }
    }

    /** The next occurrence of [todo] still to do, from [now] on. */
    fun next(data: TodoData, todo: Todo, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        slots(todo, now - (if (todo.repeat == TodoRepeat.NONE) 366 * DAY else 0L), now + 400 * DAY, zone)
            .firstOrNull { !isDone(data, todo.id, it) }

    /** How long a ticked to-do holds its line before the card moves on. */
    const val JUST_DONE_MS = 2 * MINUTE

    private const val MAX_SLOTS = 512
}
