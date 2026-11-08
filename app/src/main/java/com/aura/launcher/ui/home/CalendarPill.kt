package com.aura.launcher.ui.home

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.AuraApp
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * The next few calendar events, as pills under the clock.
 *
 * Deliberately just the time and the title. A home screen that lists an agenda
 * has become an agenda; one line per event answers "what is next" and leaves
 * the rest to the calendar app, which is one tap away.
 *
 * A swipe in either direction on one pill hides it for the rest of the day.
 * Deliberately not saved — this is for the holiday you have already read, the
 * meeting you know is coming, the "birthday" that would be shouting at you
 * from noon to midnight otherwise. A hidden pill comes back tomorrow because
 * tomorrow it is a different day; a hidden pill kept for ever is a calendar
 * setting hiding as a gesture, and that belongs to the calendar app.
 */
@Composable
fun CalendarPills(
    app: AuraApp,
    eventCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bumped on resume or when Android's calendar provider actually changes.
    // Relative labels have their own cheap clock below, so they no longer make
    // us query the calendar database every minute.
    var refreshTick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner, app.calendar.hasPermission) {
        var calendarObserverRegistered = false
        lateinit var calendarObserver: ContentObserver

        fun registerCalendarObserver() {
            if (calendarObserverRegistered || !app.calendar.hasPermission) return
            calendarObserverRegistered = runCatching {
                app.contentResolver.registerContentObserver(
                    CalendarContract.CONTENT_URI,
                    true,
                    calendarObserver,
                )
                true
            }.getOrDefault(false)
        }

        fun unregisterCalendarObserver() {
            if (!calendarObserverRegistered) return
            runCatching { app.contentResolver.unregisterContentObserver(calendarObserver) }
            calendarObserverRegistered = false
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    registerCalendarObserver()
                    refreshTick++
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> unregisterCalendarObserver()
                else -> Unit
            }
        }
        calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registerCalendarObserver()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            unregisterCalendarObserver()
        }
    }

    val events by produceState(initialValue = emptyList<UpcomingEvent>(), refreshTick, eventCount, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Only today. The day sheet exists for anything past that;
                // showing a bit of tomorrow here would make the pill duplicate
                // information the sheet already gives, and the home screen is
                // supposed to be the "right now" surface.
                val endOfToday = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                    set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59)
                    set(java.util.Calendar.MILLISECOND, 999)
                }.timeInMillis
                val loaded = app.calendar.upcoming(
                    limit = eventCount.coerceIn(1, 5),
                    withinDays = 1,
                ).filter { it.startMillis <= endOfToday }
                value = loaded

                // Query again only when an event expires (to pull the next one
                // into a full pill list) or the date rolls over. Changes made
                // elsewhere arrive through the content observer above.
                val now = System.currentTimeMillis()
                val nextEnd = loaded.asSequence()
                    .map { it.endMillis }
                    .filter { it > now }
                    .minOrNull()
                val nextMeaningfulChange = minOf(nextEnd ?: endOfToday + 1, endOfToday + 1)
                delay((nextMeaningfulChange - now).coerceAtLeast(1_000L))
            }
        }
    }

    val now by produceState(initialValue = System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000L - value % 60_000L + 50L)
            }
        }
    }

    // Which pills the user has swept aside this session.
    //
    // Not persisted, and this is the point of the feature. Somebody who swipes
    // a holiday away has read it and does not want to read it every time they
    // wake the phone today; they still want to see it come back tomorrow when
    // it is fresh news. Kept as `remember` rather than `rememberSaveable` so a
    // process death also resets it — a launcher restart is close enough to a
    // fresh look that shouting the same thing again is fine.
    var hidden by remember { mutableStateOf(setOf<Long>()) }

    // No pruning of the set. The tempting move is to intersect with the ids in
    // `events` on every tick — "if the calendar dropped it, drop it here too"
    // — and it is exactly what made hidden events reappear a few minutes
    // later. The refresh runs every sixty seconds and on every resume, and
    // "the query came back empty" is indistinguishable from "the calendar was
    // slow this second": either produces an empty list, either would clear
    // the set, and the pills you swept away would be back on the next tick.
    //
    // Calendar event ids are monotonically assigned per database and are not
    // reused within a session, so a stale id in the set costs nothing: the
    // matching event is gone from `events` too, so there is nothing to hide.
    // The set is cleared by process death, which is the "fresh day" reset.

    val visible = remember(events, hidden) { events.filter { it.id !in hidden } }

    AnimatedVisibility(
        visible = visible.isNotEmpty(),
        enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 1600f)) + fadeIn(),
        exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 1600f)) + fadeOut(),
        modifier = modifier,
    ) {
        Column {
            for (event in visible) {
                // Keyed on the event's own id — Compose reuses composition
                // slots by position by default, so removing the first pill
                // would otherwise animate the *second* one out.
                key(event.id) {
                    SwipeAwayPill(
                        event = event,
                        now = now,
                        onClick = { app.calendar.open(event) },
                        onHide = { hidden = hidden + event.id },
                    )
                }
            }
        }
    }
}

/**
 * One pill, with a horizontal drag that hides it either way.
 *
 * Either direction, on purpose: this is not "archive" versus "snooze", it is
 * one gesture with one meaning — get this out of my way. Making left and right
 * do different things would be a second thing to learn for something the pill
 * itself gives no hint of.
 *
 * The pill also comes back to centre if the thumb changes its mind, which is
 * why the threshold is generous. A swipe that never reached the commit line
 * springs back rather than committing — a home screen where a pill vanishes
 * because you brushed it is a home screen you cannot trust.
 */
@Composable
private fun SwipeAwayPill(
    event: UpcomingEvent,
    now: Long,
    onClick: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()

    val offset = remember { Animatable(0f) }
    // Owned by the gesture rather than fed through Compose state on every
    // frame, because the value is written from a coroutine on the pointer
    // dispatcher and read on the drawing thread — Animatable already handles
    // that safely.

    // The pill spans as much as its content, and there is no width until it
    // has been laid out. We just need "enough to leave the screen", so a
    // reasonable dp-based ceiling is fine; the alpha is what makes the exit
    // read as motion, and any generous value looks the same.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val leavingDistancePx = with(density) { LEAVE_DISTANCE_DP.dp.toPx() }
    val commitThresholdPx = with(density) { COMMIT_THRESHOLD_DP.dp.toPx() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .graphicsLayer {
                translationX = offset.value
                // Fades out as it travels, so the pill visibly detaches rather
                // than just sliding sideways. Clamped so the middle of a drag
                // still reads as "I am touching this".
                alpha = (1f - (offset.value.absoluteValue / leavingDistancePx))
                    .coerceIn(0.25f, 1f)
            }
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            // Drag first, so the horizontal gesture wins over the tap when
            // both would consume the pointer.
            .pointerInput(event.id) {
                detectHorizontalDragGestures(
                    onDragStart = { scope.launch { offset.stop() } },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + dragAmount) }
                    },
                    onDragEnd = {
                        val current = offset.value
                        if (current.absoluteValue >= commitThresholdPx) {
                            // Off the side it was heading. The order matters:
                            // finish the slide before letting the parent's
                            // shrinkVertically take the space away, or the
                            // pill jumps rather than glides.
                            val target = leavingDistancePx * current.sign
                            scope.launch {
                                offset.animateTo(target, tween(180))
                                onHide()
                            }
                        } else {
                            scope.launch {
                                offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 700f))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 700f))
                        }
                    },
                )
            }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = event.whenLabel(now),
            style = MaterialTheme.typography.bodySmall,
            color = colors.accent,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

/** Past this, letting go hides the pill; short of it, it springs back. */
private const val COMMIT_THRESHOLD_DP = 96

/**
 * How far the pill slides before the shrink takes the space.
 *
 * Generous enough that the pill is well off the visible line before the row
 * collapses, so what the eye sees is one movement — slide, then heal — rather
 * than a sudden gap next to a pill that is still on screen.
 */
private const val LEAVE_DISTANCE_DP = 320
