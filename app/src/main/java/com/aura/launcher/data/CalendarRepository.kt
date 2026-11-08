package com.aura.launcher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UpcomingEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    /** Where it is, as typed into the calendar — an address, a place name, a room. */
    val location: String? = null,
) {
    /**
     * "in 20 min", "14:30", "Tomorrow 09:00" — whichever is shortest while
     * still saying when. A date is only useful once "today" stops being
     * obvious.
     */
    fun whenLabel(now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val day = dayLabel(now, locale)

        // An all-day event has no time to give, but it still has a day — and
        // "All day" on its own was the one label that never said when, which is
        // the only thing the line exists to answer.
        if (allDay) return if (day == null) "All day" else "$day · all day"

        val minutesAway = TimeUnit.MILLISECONDS.toMinutes(startMillis - now)
        if (minutesAway in 0..59) return if (minutesAway <= 1) "now" else "in $minutesAway min"
        if (startMillis <= now && endMillis > now) return "now"

        val time = SimpleDateFormat("HH:mm", locale).format(Date(startMillis))
        // Today needs no date; anything else does, or "09:00" could be any
        // morning this fortnight.
        return if (day == null) time else "$day $time"
    }

    /**
     * The day part of the label, or null when the event is today.
     *
     * "Tomorrow" up to a day out, then the weekday and the date, because past
     * tomorrow a weekday alone starts being ambiguous in the other direction.
     */
    private fun dayLabel(now: Long, locale: Locale): String? {
        val start = Calendar.getInstance().apply { timeInMillis = startMillis }
        val reference = Calendar.getInstance().apply { timeInMillis = now }

        fun sameDay() = start.get(Calendar.YEAR) == reference.get(Calendar.YEAR) &&
            start.get(Calendar.DAY_OF_YEAR) == reference.get(Calendar.DAY_OF_YEAR)

        if (sameDay()) return null

        reference.add(Calendar.DAY_OF_YEAR, 1)
        if (sameDay()) return "Tomorrow"

        return SimpleDateFormat("EEE d MMM", locale).format(Date(startMillis))
    }
}

/**
 * Reads the next few events from whatever calendars the phone already syncs.
 *
 * Queries `Instances` rather than `Events`: a weekly meeting is one event but
 * many instances, and only the instance table knows when the next one actually
 * falls.
 */
class CalendarRepository(private val context: Context) {

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    suspend fun upcoming(limit: Int = 3, withinDays: Int = 14): List<UpcomingEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission) return@withContext emptyList()

            val now = System.currentTimeMillis()
            val until = now + TimeUnit.DAYS.toMillis(withinDays.toLong())

            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .let { ContentUris.appendId(it, now); ContentUris.appendId(it, until); it.build() }

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            )

            runCatching {
                context.contentResolver.query(
                    uri,
                    projection,
                    // Declined invitations are not upcoming events.
                    "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ?",
                    arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString()),
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < limit) {
                            val title = cursor.getString(1)?.trim().orEmpty()
                            val begin = cursor.getLong(2)
                            val end = cursor.getLong(3)
                            // An event already finished is not upcoming, even if
                            // the instance window still includes it.
                            if (end <= now) continue
                            add(
                                UpcomingEvent(
                                    id = cursor.getLong(0),
                                    title = title.ifBlank { "Busy" },
                                    startMillis = begin,
                                    endMillis = end,
                                    allDay = cursor.getInt(4) == 1,
                                    location = cursor.getString(5)?.trim()?.takeIf { it.isNotEmpty() },
                                )
                            )
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
        }

    /** Opens the event in whatever calendar app handles it. */
    fun open(event: UpcomingEvent): Boolean = runCatching {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        // Some calendar apps do not handle a direct event URI; fall back to
        // opening the day it falls on.
        runCatching {
            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, event.startMillis)
            context.startActivity(
                Intent(Intent.ACTION_VIEW, builder.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
