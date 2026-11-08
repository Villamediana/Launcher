package com.aura.launcher.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.timer.TimerAlarm
import com.aura.launcher.timer.TimerMode
import com.aura.launcher.timer.TimerState
import com.aura.launcher.timer.Timers
import com.aura.launcher.timer.formatDuration
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch

/**
 * A stopwatch and a countdown, as one face and one thumb.
 *
 * The home screen already knows what time it is; what it has never been able to
 * answer is "how long" — how long has this been going, how long is left. Both
 * are the same clock read in opposite directions, which is why this is one
 * widget with a switch rather than two cards competing for the same space.
 *
 * The face *is* the button. A timer is started with a wet hand, a full hand,
 * one hand — and a control that needs aiming at a 40dp target is a control that
 * gets missed. Everything else on the card is smaller than the number and sits
 * underneath it: the mode, the lengths to choose from, the way back to zero.
 *
 * Nothing here counts. The number is worked out from what was stored and the
 * wall clock every time it is drawn, so the run survives the launcher being
 * killed, the screen going off and the phone being rebooted — see
 * [com.aura.launcher.timer.TimerState]. The tick only wakes the screen while
 * something is actually running, and only while the home screen is in front of
 * somebody.
 */
@Composable
fun TimerCard(app: AuraApp, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val state by app.timers.state.collectAsState()

    // A second at a time while it runs, a minute otherwise — the shared home
    // clock, so a running timer does not put a second coroutine on the phone.
    val now = rememberCareNow(fast = state.running)

    val done = state.isDone(now)
    // The countdown reaching zero while somebody is looking at it. The alarm
    // says so when they are not; this is what stops the face counting past it.
    LaunchedEffect(done) {
        if (done) app.timers.edit { Timers.finish(it, System.currentTimeMillis()) }
    }

    val finished = state.mode == TimerMode.COUNTDOWN && state.finishedAt > 0L
    // A countdown that has just gone off breathes, so the card reads as
    // finished from across a room rather than as one that says 00:00.
    val pulse by animateFloatAsState(
        targetValue = if (finished) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "timerFinished",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 700f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDuration(
                    state.display(now),
                    roundUp = state.mode == TimerMode.COUNTDOWN,
                ),
                fontSize = 46.sp,
                fontWeight = FontWeight.Light,
                color = when {
                    finished -> colors.accent
                    state.running -> colors.ink
                    state.isIdle -> colors.inkFaint
                    else -> colors.inkMuted
                },
                modifier = Modifier
                    .graphicsLayer { alpha = 1f - 0.25f * pulse }
                    .careTap {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            app.timers.edit { current ->
                                // A finished countdown starts the same length
                                // again rather than refusing to move: the
                                // second egg goes in right after the first.
                                val from = if (current.finishedAt > 0L) Timers.reset(current) else current
                                Timers.toggle(from, System.currentTimeMillis())
                            }
                            TimerAlarm.sync(app, app.timers.state.value)
                        }
                    },
            )

            Spacer(Modifier.width(14.dp))

            // Only once there is something to undo. A reset on a clock that has
            // never run is a control that can only disappoint.
            if (!state.isIdle) {
                Text(
                    text = stringResource(R.string.timer_reset),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.surfaceRaised)
                        .careTap {
                            scope.launch {
                                app.timers.edit { Timers.reset(it) }
                                TimerAlarm.cancel(app)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                label = stringResource(R.string.timer_mode_stopwatch),
                on = state.mode == TimerMode.STOPWATCH,
            ) {
                scope.launch {
                    app.timers.edit { Timers.setMode(it, TimerMode.STOPWATCH) }
                    TimerAlarm.cancel(app)
                }
            }
            Chip(
                label = stringResource(R.string.timer_mode_countdown),
                on = state.mode == TimerMode.COUNTDOWN,
            ) {
                scope.launch {
                    app.timers.edit { current ->
                        Timers.setMode(current, TimerMode.COUNTDOWN)
                            // Back to whatever length they last used, so the
                            // common case is one tap and then the face.
                            .copy(duration = current.lastDuration)
                    }
                    TimerAlarm.cancel(app)
                }
            }
        }

        // The lengths, and only while there is a countdown to set. Once it is
        // running they would be a row of buttons that throw the run away.
        if (state.mode == TimerMode.COUNTDOWN && state.isIdle) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENGTHS.forEach { minutes ->
                    val millis = minutes * 60_000L
                    Chip(
                        label = stringResource(R.string.timer_minutes, minutes),
                        on = state.duration == millis,
                    ) {
                        scope.launch { app.timers.edit { Timers.setDuration(it, millis) } }
                    }
                }
            }
        }
    }
}

/** The lengths a kitchen, a nap and a phone call are actually set to. */
private val LENGTHS = listOf(1, 3, 5, 10, 30)

/** A small pill that is either on or off — the card's only other control. */
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = Aura.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) colors.accent.copy(alpha = 0.16f) else colors.surfaceRaised)
            .careTap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) colors.accent else colors.inkFaint,
        )
    }
}
