package com.aura.launcher.ui.home

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.Forecast
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.ui.theme.Aura
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The day, opened from the line under the clock.
 *
 * The line answers "what time, how warm, how much battery" in six words. This
 * answers the question that follows it — what the rest of the day looks like —
 * and it is a sheet rather than a screen because that is the relationship: you
 * glance, you want a little more, you dismiss. Somewhere to navigate to would
 * make it a destination, which a forecast is not.
 *
 * Two things, in the order they are asked about. The next twelve hours as a
 * strip you push sideways, then the days ahead with whatever the calendar has
 * on them. Tapping an event opens it in the calendar app — this shows what is
 * there, it does not try to become a calendar.
 *
 * The dated rows carry no forecast. They did, and a high, a low and a word for
 * the sky under every heading turned an agenda into a weather report with an
 * agenda attached — and the daily code disagreed with the hourly strip a few
 * inches above it often enough to make both look like guesses. The hours
 * answer the weather question; the days answer the calendar one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(app: AuraApp, onDismiss: () -> Unit) {
    val colors = Aura.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings by app.currentSettings.collectAsState()

    var weatherAttempt by remember { mutableIntStateOf(0) }
    val initialWeatherLoad = if (settings.showWeather) WeatherLoad.Loading else WeatherLoad.Hidden
    val weatherLoad by produceState<WeatherLoad>(
        initialWeatherLoad,
        weatherAttempt,
        settings.showWeather,
    ) {
        if (!settings.showWeather) {
            value = WeatherLoad.Hidden
            return@produceState
        }
        val result = app.weather.forecast()
        value = if (result == null) WeatherLoad.Unavailable else WeatherLoad.Ready(result)
    }
    // The same reading the line under the clock shows. Without it the sheet
    // said 17° directly below a line saying 18°: the strip's first column came
    // from the hourly forecast for the current hour, which is a model's guess
    // for the whole hour, while the line comes from the observation endpoint.
    // Two numbers for "now" on one screen is the fastest way to make a forecast
    // look untrustworthy, whichever of them is closer to the truth.
    val currentNow by produceState<com.aura.launcher.data.Weather?>(
        null,
        weatherAttempt,
        settings.showWeather,
    ) {
        if (!settings.showWeather) return@produceState
        value = app.weather.current()
    }
    // A week, and enough of them that a busy Monday does not crowd out Tuesday.
    val events by produceState(initialValue = emptyList<UpcomingEvent>()) {
        value = app.calendar.upcoming(limit = 120, withinDays = DAYS_SHOWN)
    }

    val hideStatusBar = settings.hideStatusBar

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(34.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.inkFaint.copy(alpha = 0.4f))
                )
            }
        },
    ) {
        // The sheet is a Dialog with its own window; the activity's status-bar
        // hiding does not apply here. LocalView inside the content lambda is
        // owned by that dialog, so walking up its parent reaches the sheet's
        // window instead of the activity's.
        val sheetView = LocalView.current
        DisposableEffect(sheetView, hideStatusBar) {
            val window = (sheetView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, sheetView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
                else controller.show(WindowInsetsCompat.Type.statusBars())
            }
            onDispose {}
        }

        Column(
            Modifier
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.day_sheet_title),
                fontSize = 24.sp,
                color = colors.ink,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            )

            when (val weather = weatherLoad) {
                WeatherLoad.Hidden -> Unit
                WeatherLoad.Loading -> {
                    WeatherNotice(stringResource(R.string.day_sheet_weather_loading))
                    Spacer(Modifier.height(16.dp))
                }
                WeatherLoad.Unavailable -> {
                    val hasPermission = app.weather.hasPermission
                    WeatherNotice(
                        text = stringResource(
                            if (hasPermission) R.string.day_sheet_weather_unavailable
                            else R.string.day_sheet_no_weather
                        ),
                        onRetry = if (hasPermission) ({ weatherAttempt++ }) else null,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                is WeatherLoad.Ready -> {
                    HourStrip(weather.forecast.hourly, currentNow, weather.forecast.timeZoneId)
                    Spacer(Modifier.height(22.dp))
                }
            }

            val days = remember(events) { buildDays(events) }
            for (day in days) {
                DayBlock(day = day, onEvent = { app.calendar.open(it) }, onNewEvent = {
                    newEvent(context, day.startMillis)
                    onDismiss()
                })
            }

            if (days.all { it.events.isEmpty() } && weatherLoad !is WeatherLoad.Ready) {
                Text(
                    text = stringResource(R.string.day_sheet_no_events),
                    fontSize = 13.sp,
                    color = colors.inkFaint,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The next twelve hours, pushed sideways.
 *
 * A row rather than a chart. A temperature curve reads well at the size of a
 * weather app and turns into decoration in a sheet this tall — the numbers are
 * the content, and four of them fit without scrolling, which is about as far
 * ahead as anyone plans while looking at a home screen.
 */
@Composable
private fun HourStrip(
    hours: List<com.aura.launcher.data.HourPoint>,
    /** The observed conditions, which win the first column outright. */
    now: com.aura.launcher.data.Weather?,
    /** Forecast-location zone; it may differ from a manually selected device zone. */
    timeZoneId: String?,
) {
    val colors = Aura.colors
    val hourFormat = remember(timeZoneId) {
        SimpleDateFormat("HH", Locale.getDefault()).apply {
            timeZone = timeZoneId?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        }
    }

    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.paperDeep)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 14.dp, horizontal = 6.dp),
    ) {
        hours.forEachIndexed { index, hour ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    // The first column is "now" rather than the hour it happens
                    // to be, because that is what it means — the reading you
                    // already saw on the line you tapped.
                    text = if (index == 0) stringResource(R.string.day_sheet_now)
                    else hourFormat.format(Date(hour.atMillis)),
                    fontSize = 12.sp,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(9.dp))
                SkyIcon(code = if (index == 0) now?.code ?: hour.code else hour.code, size = 22.dp)
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "${if (index == 0) now?.celsius ?: hour.celsius else hour.celsius}°",
                    fontSize = 13.sp,
                    color = if (index == 0) colors.ink else colors.inkMuted,
                )
            }
        }
        // Keeps the last column clear of the rounded edge when scrolled fully.
        Spacer(Modifier.width(6.dp))
    }
}

private sealed interface WeatherLoad {
    data object Hidden : WeatherLoad
    data object Loading : WeatherLoad
    data object Unavailable : WeatherLoad
    data class Ready(val forecast: Forecast) : WeatherLoad
}

/** A compact weather status with an icon-only retry action when retrying helps. */
@Composable
private fun WeatherNotice(text: String, onRetry: (() -> Unit)? = null) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = colors.inkFaint,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.day_sheet_weather_retry),
                tint = colors.accent,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .clickable(onClick = onRetry)
                    .padding(9.dp),
            )
        }
    }
}

/** One dated block: the day, its forecast, and what is on it. */
@Composable
private fun DayBlock(day: DayRow, onEvent: (UpcomingEvent) -> Unit, onNewEvent: () -> Unit) {
    val colors = Aura.colors
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = dateFormat.format(Date(day.startMillis)),
                fontSize = 17.sp,
                color = colors.ink,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = day.relativeLabel(),
                fontSize = 12.sp,
                color = colors.inkFaint,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }

        // Only today offers this. "New event" under every day would be four
        // buttons doing the same thing, and the calendar's own screen is where
        // you go to add something to next Thursday.
        if (day.isToday) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onNewEvent)
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.day_sheet_new_event),
                    fontSize = 14.sp,
                    color = colors.accent,
                )
            }
        }

        for (event in day.events) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onEvent(event) }
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(38.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent.copy(alpha = 0.8f))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = event.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (event.allDay) {
                            day.relativeLabel()
                        } else {
                            "${timeFormat.format(Date(event.startMillis))}–" +
                                timeFormat.format(Date(event.endMillis))
                        },
                        fontSize = 12.sp,
                        color = colors.inkFaint,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** A day, with whatever is known about it from either source. */
internal data class DayRow(
    val startMillis: Long,
    val daysFromToday: Int,
    val events: List<UpcomingEvent>,
) {
    val isToday: Boolean get() = daysFromToday == 0

    @Composable
    fun relativeLabel(): String = when (daysFromToday) {
        0 -> stringResource(R.string.day_sheet_today)
        1 -> stringResource(R.string.day_sheet_tomorrow)
        else -> stringResource(R.string.day_sheet_in_days, daysFromToday)
    }
}

/**
 * Merges the two sources into one list of days.
 *
 * Driven by the calendar day rather than by either feed, because the two do not
 * line up: the forecast has seven days whether or not anything is happening on
 * them, and the calendar has events on days the forecast may not reach. Keying
 * on midnight-local means an event at 23:50 and one at 00:10 land on the days a
 * person would put them on, which is not what either raw feed would give.
 */
internal fun buildDays(events: List<UpcomingEvent>): List<DayRow> {
    val today = midnight(System.currentTimeMillis())
    val byDay = events.groupBy { midnight(it.startMillis) }

    return (0 until DAYS_SHOWN).map { offset ->
        val start = addDays(today, offset)
        DayRow(
            startMillis = start,
            daysFromToday = offset,
            events = byDay[start].orEmpty().sortedBy { it.startMillis },
        )
    }.filter {
        // Today always shows, because it carries the "New event" row. The rest
        // earn their place by having something on them — a dated heading with
        // nothing under it is a row that says only what the calendar already
        // implies, which is that the day exists.
        it.isToday || it.events.isNotEmpty()
    }
}

internal fun midnight(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun addDays(millis: Long, days: Int): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    add(Calendar.DAY_OF_YEAR, days)
}.timeInMillis

/**
 * Hands a new event on the given day to a calendar app.
 *
 * The intent is fired raw — no `Intent.createChooser` wrapper. That
 * matters: `createChooser` always asks and never lets Android remember
 * the choice. Firing the bare intent hits the system's resolver, which
 * offers "Just once / Always" the first time and skips the prompt from
 * then on. Where only one calendar app is installed, it goes straight
 * there with no prompt at all.
 */
private fun newEvent(context: android.content.Context, dayStart: Long) {
    val insert = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dayStart)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(insert) }
}

/**
 * How far ahead the sheet's day rows go. One week is enough to answer
 * "what does the rest of my week look like" without turning into an
 * agenda view — a longer view lives in the calendar app.
 */
private const val DAYS_SHOWN = 7
