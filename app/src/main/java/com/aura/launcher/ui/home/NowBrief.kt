package com.aura.launcher.ui.home

import com.aura.launcher.data.HourPoint
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.timer.TimerMode
import com.aura.launcher.timer.TimerState

/**
 * What the "Now" widget says: at most four short lines, most pressing first,
 * chosen from what the phone already knows — the calendar and the route to
 * the next place, the medicine schedule, the alarm clock, a running timer,
 * what Aura was asked to do later, and the forecast.
 *
 * Pure, so the choosing can be tested without a phone; the widget only
 * fetches, formats and draws what this returns.
 */
object NowBrief {

    sealed interface Item {
        /** Lower goes first. */
        val rank: Int
    }

    /** A to-do occurrence, as the brief needs it: what, when, and whether it is ticked. */
    data class Due(val name: String, val at: Long, val done: Boolean = false)

    /** A timed to-do due within the last hour and not yet ticked. */
    data class LateDose(val name: String, val minutesLate: Int) : Item {
        override val rank = 0
    }

    /** When to leave for the next event that has somewhere to be. */
    data class Leave(val title: String, val leaveAt: Long, val travelMinutes: Int, val now: Boolean) : Item {
        override val rank = if (now) 1 else 3
    }

    /** A countdown running, or a stopwatch. */
    data class Timer(val countdown: Boolean, val millis: Long) : Item {
        override val rank = 2
    }

    /** Something in the calendar happening right now. */
    data class Ongoing(val title: String, val endsAt: Long) : Item {
        override val rank = 4
    }

    /** The next event, when there is no route to time the leaving by. */
    data class Event(val title: String, val startsAt: Long, val inMinutes: Int) : Item {
        override val rank = 5
    }

    data class Dose(val name: String, val at: Long) : Item {
        override val rank = 6
    }

    /** The next alarm on the phone's clock. */
    data class Alarm(val at: Long) : Item {
        override val rank = 7
    }

    /** Rain starting within the next few hours, while it is not raining now. */
    data class Rain(val at: Long) : Item {
        override val rank = 9
    }

    const val MAX_LINES = 4
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    /** A dose later than this is not "now" any more — it was missed, not late. */
    const val LATE_DOSE_WINDOW_MS = HOUR
    const val ALARM_WINDOW_MS = 12 * HOUR
    const val EVENT_WINDOW_MS = 12 * HOUR
    const val LEAVE_WINDOW_MS = 4 * HOUR
    const val DOSE_WINDOW_MS = 90 * MINUTE
    const val RAIN_WINDOW_MS = 4 * HOUR
    /** Arriving with a few minutes to spare, not on the stroke of the hour. */
    const val LEAVE_MARGIN_MS = 5 * MINUTE

    fun isRain(code: Int): Boolean = code in 51..67 || code in 80..82 || code in 95..99

    fun build(
        now: Long,
        events: List<UpcomingEvent>,
        /** Seconds of driving to each event's location, by event id, where known. */
        travelSeconds: Map<Long, Double>,
        hourly: List<HourPoint>,
        lateDoses: List<Due>,
        nextDose: Due?,
        nextAlarm: Long? = null,
        timer: TimerState? = null,
    ): List<Item> {
        val items = mutableListOf<Item>()

        lateDoses
            .filter { !it.done && now - it.at in 0..LATE_DOSE_WINDOW_MS }
            .maxByOrNull { it.at }
            ?.let { items += LateDose(it.name, ((now - it.at) / MINUTE).toInt().coerceAtLeast(1)) }

        val timed = events.filter { !it.allDay }
        timed.firstOrNull { it.startMillis <= now && now < it.endMillis }
            ?.let { items += Ongoing(it.title, it.endMillis) }

        val next = timed.firstOrNull { it.startMillis > now }
        if (next != null) {
            val travel = travelSeconds[next.id]
            if (travel != null && next.startMillis - now <= LEAVE_WINDOW_MS) {
                val leaveAt = next.startMillis - (travel * 1000).toLong() - LEAVE_MARGIN_MS
                items += Leave(
                    title = next.title,
                    leaveAt = leaveAt,
                    travelMinutes = (travel / 60).toInt().coerceAtLeast(1),
                    now = leaveAt <= now,
                )
            } else if (next.startMillis - now <= EVENT_WINDOW_MS) {
                items += Event(next.title, next.startMillis, ((next.startMillis - now) / MINUTE).toInt())
            }
        }

        if (timer != null && timer.running) {
            val countdown = timer.mode == TimerMode.COUNTDOWN
            items += Timer(countdown, if (countdown) timer.remaining(now) else timer.elapsed(now))
        }

        if (nextDose != null && nextDose.at - now in 0..DOSE_WINDOW_MS) {
            items += Dose(nextDose.name, nextDose.at)
        }

        if (nextAlarm != null && nextAlarm - now in 0..ALARM_WINDOW_MS) {
            items += Alarm(nextAlarm)
        }

        val currentHour = hourly.lastOrNull { it.atMillis <= now }
        val rainingNow = currentHour?.let { isRain(it.code) } == true
        if (!rainingNow) {
            hourly.firstOrNull { it.atMillis > now && it.atMillis - now <= RAIN_WINDOW_MS && isRain(it.code) }
                ?.let { items += Rain(it.atMillis) }
        }

        return items.sortedBy { it.rank }.take(MAX_LINES)
    }
}
