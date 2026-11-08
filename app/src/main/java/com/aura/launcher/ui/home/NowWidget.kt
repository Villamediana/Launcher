package com.aura.launcher.ui.home

import android.content.Context
import android.location.Geocoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.todo.TodoMath
import com.aura.launcher.data.Locator
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.web.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Now": the two or three things worth knowing this minute, and nothing else.
 *
 * Drawn straight onto the wallpaper, like the clock — no card, no box. Each
 * line leads with a small mark: a quiet dot for things coming, and an accent
 * dot that breathes for the one that wants you now (a late dose, time to
 * leave). When nothing is asking for anything it draws nothing at all.
 *
 * What goes in it is decided by [NowBrief]; this only fetches, formats and
 * draws. Refreshed when the home screen comes back and every minute while it
 * is in front of you; the route to the next place is asked for once per event.
 */
@Composable
fun NowCard(app: AuraApp) {
    val context = LocalContext.current
    val todos by app.todos.data.collectAsState()
    val timer by app.timers.state.collectAsState()
    var alarm by remember { mutableStateOf<Long?>(null) }
    var events by remember { mutableStateOf<List<UpcomingEvent>>(emptyList()) }
    var hourly by remember { mutableStateOf<List<com.aura.launcher.data.HourPoint>>(emptyList()) }
    var travel by remember { mutableStateOf<Map<Long, Double>>(emptyMap()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var arrivals by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) arrivals++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(arrivals) {
        events = runCatching { app.calendar.upcoming(limit = 5, withinDays = 1) }.getOrDefault(emptyList())
        alarm = runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).nextAlarmClock?.triggerTime
        }.getOrNull()
        hourly = runCatching { app.weather.forecast()?.hourly }.getOrNull().orEmpty()
        // Route only the next event that has an address, and only once.
        val next = events.firstOrNull { it.startMillis > System.currentTimeMillis() && !it.allDay }
        if (next?.location != null && next.id !in travel &&
            next.startMillis - System.currentTimeMillis() <= NowBrief.LEAVE_WINDOW_MS
        ) {
            val seconds = routeSeconds(context, app, next.location)
            if (seconds != null) travel = travel + (next.id to seconds)
        }
    }

    // The minute hand: lines like "in 12 min" and "leave now" go stale fast.
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                // Every second while a timer is counting on screen; on the
                // minute otherwise.
                delay(if (timer.running) 1000L - now % 1000L + 20 else 60_000L - now % 60_000L + 50)
            }
        }
    }

    val items = remember(now, events, travel, hourly, todos, alarm, timer) {
        NowBrief.build(
            now = now,
            events = events,
            travelSeconds = travel,
            hourly = hourly,
            lateDoses = TodoMath.rows(todos, now)
                .filter { it.late && it.todo.timed }
                .map { NowBrief.Due(it.todo.title, it.at, it.done) },
            nextDose = todos.todos.filter { it.timed && !it.archived }
                .mapNotNull { t -> TodoMath.next(todos, t, now)?.takeIf { it > now }?.let { NowBrief.Due(t.title, it) } }
                .minByOrNull { it.at },
            nextAlarm = alarm,
            timer = timer,
        )
    }

    // Nothing pending, nothing drawn — not even the label. The Miguel asked
    // for the widget to simply not be there rather than say it is empty.
    if (items.isEmpty()) return

    val colors = Aura.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.now_widget_label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { index, item ->
            // Each line arrives a beat after the one above it — once per
            // return to the home screen, not every time "in 12 min" ticks to
            // "in 11 min".
            var shown by remember(arrivals, index) { mutableStateOf(false) }
            LaunchedEffect(arrivals, index) {
                delay(70L * index)
                shown = true
            }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 3 },
            ) {
                NowLine(
                    text = describe(item),
                    urgent = item is NowBrief.LateDose || (item is NowBrief.Leave && item.now),
                    lead = index == 0,
                )
            }
        }
    }
}

@Composable
private fun NowLine(text: String, urgent: Boolean, lead: Boolean) {
    val colors = Aura.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        if (urgent) UrgentDot() else Canvas(Modifier.size(14.dp)) {
            val r = size.minDimension / 2
            drawCircle(colors.ink.copy(alpha = if (lead) 0.55f else 0.3f), radius = r * 0.26f)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = if (lead) 17.sp else 15.sp,
            fontWeight = FontWeight.Light,
            color = if (urgent) colors.accent else if (lead) colors.ink else colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Only the line that wants you now runs an animation; the rest stay still. */
@Composable
private fun UrgentDot() {
    val colors = Aura.colors
    // On the home screen's shared clock rather than an infinite transition,
    // which asks for a frame on every refresh — 120 a second on a fast panel,
    // for as long as the dose stays late.
    val owner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val breath by produceState(1f, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val fps = com.aura.launcher.ui.theme.Ambient.fps(context)
            while (true) {
                com.aura.launcher.ui.theme.Ambient.nextTick(fps)
                // 1.4 s up, 1.4 s down, eased like the tween it replaces.
                val phase = (android.os.SystemClock.uptimeMillis() % 2_800L) / 1_400f
                val k = if (phase <= 1f) phase else 2f - phase
                value = 0.35f + 0.65f * androidx.compose.animation.core.FastOutSlowInEasing.transform(k)
            }
        }
    }
    Canvas(Modifier.size(14.dp)) {
        val r = size.minDimension / 2
        // A soft halo that breathes, round a solid centre.
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.accent.copy(alpha = 0.45f * breath),
                1f to Color.Transparent,
                center = center,
                radius = r,
            ),
            radius = r,
        )
        drawCircle(colors.accent, radius = r * 0.36f)
    }
}

@Composable
private fun describe(item: NowBrief.Item): String {
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    return when (item) {
        is NowBrief.LateDose -> stringResource(R.string.now_late_dose, item.name, item.minutesLate)
        is NowBrief.Leave ->
            if (item.now) stringResource(R.string.now_leave_now, item.title, item.travelMinutes)
            else stringResource(R.string.now_leave_at, hm.format(Date(item.leaveAt)), item.title, item.travelMinutes)
        is NowBrief.Event ->
            if (item.inMinutes < 60) stringResource(R.string.now_event_in, item.title, item.inMinutes.coerceAtLeast(1))
            else stringResource(R.string.now_event_at, item.title, hm.format(Date(item.startsAt)))
        is NowBrief.Dose -> stringResource(R.string.now_dose, item.name, hm.format(Date(item.at)))
        is NowBrief.Rain -> stringResource(R.string.now_rain, hm.format(Date(item.at)))
        is NowBrief.Ongoing -> stringResource(R.string.now_ongoing, item.title, hm.format(Date(item.endsAt)))
        is NowBrief.Alarm -> stringResource(R.string.now_alarm, hm.format(Date(item.at)))
        is NowBrief.Timer ->
            if (item.countdown) stringResource(R.string.now_timer_left, com.aura.launcher.timer.formatDuration(item.millis, roundUp = true))
            else stringResource(R.string.now_stopwatch, com.aura.launcher.timer.formatDuration(item.millis))
    }
}

/** Driving time from here to [place], by the real road network. */
@Suppress("DEPRECATION")
private suspend fun routeSeconds(context: Context, app: AuraApp, place: String): Double? {
    val here = Locator.currentOrLastKnown(context) ?: return null
    val spot = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(place, 1)?.firstOrNull() }.getOrNull()
    } ?: return null
    return app.geo.route(here.latitude, here.longitude, spot.latitude, spot.longitude, TravelMode.DRIVING)
        .getOrNull()?.durationS
}
