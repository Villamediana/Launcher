package com.aura.launcher.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.data.Weather
import com.aura.launcher.todo.TodoMath
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Home 3's clock: one line, the time and the day. */
@Composable
internal fun CompactClock(timeText: String, dayText: String, onTime: () -> Unit, onDay: () -> Unit) {
    val colors = Aura.colors
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            timeText,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light, fontSize = 48.sp, letterSpacing = (-1).sp),
            color = colors.ink,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onTime),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            dayText,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkMuted,
            modifier = Modifier
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDay),
        )
    }
}

/** One stop on the line. */
private data class Stop(
    val at: Long,
    val time: String,
    val title: String,
    val detail: String,
    val kind: Kind,
    val event: UpcomingEvent? = null,
    val todoId: String? = null,
)

private enum class Kind { NOW, EVENT, TODO, TOMORROW }

/**
 * Home 3, "Dia em linha": the day as a line.
 *
 * Now (how it is outside, and what to know about it), then what is coming —
 * events from the calendar, the to-dos and medicines owed today — and the
 * first thing tomorrow, in the order they happen. It answers "what comes
 * now?" without opening anything; each stop opens what it came from.
 */
@Composable
internal fun DayLine(app: AuraApp, onOpenDay: () -> Unit) {
    val colors = Aura.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val now = rememberCareNow()

    val weather by produceState<Weather?>(null, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = runCatching { app.weather.current() }.getOrNull() ?: value
                delay(15 * 60_000L)
            }
        }
    }
    val rainAt by produceState<Long?>(null, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val hours = runCatching { app.weather.forecast()?.hourly }.getOrNull().orEmpty()
                val t = System.currentTimeMillis()
                value = hours.firstOrNull { it.atMillis in t..(t + 10 * 3_600_000L) && isRain(it.code) }?.atMillis
                delay(30 * 60_000L)
            }
        }
    }
    val events by produceState(emptyList<UpcomingEvent>(), owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = runCatching { app.calendar.upcoming(limit = 8, withinDays = 2) }.getOrDefault(emptyList())
                delay(5 * 60_000L)
            }
        }
    }
    val todoData by app.todos.data.collectAsState()

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val nowLabel = stringResource(R.string.day_now)
    val tomorrowLabel = stringResource(R.string.day_tomorrow)
    val reminderLabel = stringResource(R.string.day_reminder)
    val lateLabel = stringResource(R.string.day_late)
    val rainText = rainAt?.let { stringResource(R.string.day_rain_at, timeFormat.format(Date(it))) }
    val skyText = weather?.let { stringResource(skyRes(it.code)) }

    val stops = remember(now, weather, rainAt, events, todoData, skyText, rainText) {
        val endOfToday = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }.timeInMillis
        buildList {
            weather?.let { w ->
                add(Stop(now, nowLabel, "${w.celsius}°, ${skyText.orEmpty()}", rainText.orEmpty(), Kind.NOW))
            }
            events.filter { it.endMillis > now && it.startMillis <= endOfToday }.forEach { e ->
                val time = if (e.allDay) "—" else timeFormat.format(Date(e.startMillis))
                val inMin = (e.startMillis - now) / 60_000
                val detail = listOfNotNull(
                    e.location?.takeIf { it.isNotBlank() },
                    if (!e.allDay && inMin in 1..180) context.getString(R.string.day_in_minutes, inMin.toInt()) else null,
                ).joinToString(" · ")
                add(Stop(e.startMillis, time, e.title, detail, Kind.EVENT, event = e))
            }
            TodoMath.rows(todoData, now).filter { !it.done && it.at <= endOfToday }.forEach { r ->
                add(
                    Stop(
                        r.at,
                        if (r.todo.timed) timeFormat.format(Date(r.at)) else "—",
                        r.todo.title,
                        if (r.late) lateLabel else reminderLabel,
                        Kind.TODO,
                        todoId = r.todo.id,
                    )
                )
            }
            events.firstOrNull { it.startMillis > endOfToday }?.let { e ->
                add(Stop(e.startMillis, tomorrowLabel, e.title, if (e.allDay) "" else timeFormat.format(Date(e.startMillis)), Kind.TOMORROW, event = e))
            }
        }.sortedWith(compareBy<Stop> { it.kind != Kind.NOW }.thenBy { it.kind == Kind.TOMORROW }.thenBy { it.at }).take(MAX_STOPS)
    }

    var openTodo by remember { mutableStateOf<String?>(null) }
    if (stops.isEmpty()) return

    Box(Modifier.fillMaxWidth().padding(start = 2.dp)) {
        Box {
            // The line the stops hang on.
            val lineColor = colors.ink.copy(alpha = 0.22f)
            Canvas(Modifier.matchParentSize()) {
                drawLine(lineColor, Offset(6.dp.toPx(), 10.dp.toPx()), Offset(6.dp.toPx(), size.height - 10.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                stops.forEachIndexed { i, stop ->
                    val next = i == stops.indexOfFirst { it.kind != Kind.NOW }
                    val dot = when (stop.kind) {
                        Kind.NOW -> colors.accent
                        Kind.EVENT -> if (next) colors.accent else colors.ink
                        Kind.TODO -> colors.sage
                        Kind.TOMORROW -> colors.inkFaint
                    }
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                when {
                                    stop.event != null -> app.calendar.open(stop.event)
                                    stop.todoId != null -> openTodo = stop.todoId
                                    else -> onOpenDay()
                                }
                            },
                    ) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(dot)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(stop.time, style = MaterialTheme.typography.labelMedium, color = colors.inkMuted)
                            Text(
                                stop.title,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = if (stop.kind == Kind.NOW) 19.sp else 17.sp,
                                    fontWeight = if (stop.kind == Kind.NOW || next) FontWeight.Medium else FontWeight.Normal,
                                ),
                                color = if (stop.kind == Kind.TOMORROW) colors.inkMuted else colors.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (stop.detail.isNotBlank()) {
                                Text(stop.detail, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (stop.todoId != null) {
                            // Done, in one tap, without opening anything.
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { scope.launch { app.todos.setDone(stop.todoId, stop.at, true) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, colors.sage, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }

    openTodo?.let { id -> TodoSheet(app = app, onDismiss = { openTodo = null }, openId = id) }
}

private fun isRain(code: Int) = code in 51..67 || code in 80..82 || code in 95..99

private fun skyRes(code: Int): Int = when (code) {
    0 -> R.string.sky_clear
    1, 2 -> R.string.sky_partly
    3 -> R.string.sky_cloudy
    in 45..48 -> R.string.sky_fog
    in 51..57, in 61..65, in 80..82 -> R.string.sky_rain
    in 66..67, in 71..77, in 85..86 -> R.string.sky_snow
    in 95..99 -> R.string.sky_storm
    else -> R.string.sky_clear
}

private const val MAX_STOPS = 6
