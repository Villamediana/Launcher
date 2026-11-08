package com.aura.launcher.timer

import kotlinx.serialization.Serializable

/** Counting up from nothing, or down to it. */
@Serializable
enum class TimerMode { STOPWATCH, COUNTDOWN }

/**
 * A clock the user started, as the two numbers it takes to rebuild it.
 *
 * Not a tick count. The widget is a composable that stops existing the moment
 * the home screen leaves the foreground, and the process behind it can be shot
 * at any time — anything counting in memory would lose the run. What is stored
 * instead is when the current stretch began and how much time was banked before
 * it, which is enough to work out the elapsed time from the clock on the wall at
 * any later moment, including in a fresh process.
 *
 * That is also what makes the whole of this arithmetic rather than behaviour:
 * every question the widget asks — how long has it been, how long is left, has
 * it finished — is a pure function of this and the current time.
 */
@Serializable
data class TimerState(
    val mode: TimerMode = TimerMode.STOPWATCH,
    /** When the running stretch began, or 0 when it is not running. */
    val startedAt: Long = 0L,
    /** Milliseconds banked by the stretches before this one. */
    val banked: Long = 0L,
    /** What a countdown was set to. Ignored by the stopwatch. */
    val duration: Long = DEFAULT_DURATION,
    /**
     * The last length somebody chose, kept so the next countdown opens on it.
     *
     * Separate from [duration] because a running countdown's length is a fact
     * about that run — resetting it must not forget what the user tends to use.
     */
    val lastDuration: Long = DEFAULT_DURATION,
    /**
     * When a countdown reached zero, or 0.
     *
     * Kept so the widget can say "finished" after the fact rather than quietly
     * sitting at 00:00, which reads identically to one that never ran.
     */
    val finishedAt: Long = 0L,
) {
    val running: Boolean get() = startedAt > 0L

    /** How long it has run, counting the stretch in progress. */
    fun elapsed(now: Long): Long =
        if (running) banked + (now - startedAt).coerceAtLeast(0L) else banked

    /**
     * What a countdown has left, floored at zero.
     *
     * Zero is the end of it: a countdown that went negative would be answering
     * a question nobody asked, and "-00:07" is not a number anyone reads as
     * "it is done".
     */
    fun remaining(now: Long): Long =
        (duration - elapsed(now)).coerceAtLeast(0L)

    /** What the face shows, whichever way it counts. */
    fun display(now: Long): Long =
        if (mode == TimerMode.COUNTDOWN) remaining(now) else elapsed(now)

    /** True once a running countdown has reached zero. */
    fun isDone(now: Long): Boolean =
        mode == TimerMode.COUNTDOWN && duration > 0L && elapsed(now) >= duration

    /** Nothing has been done to it — nothing running, nothing banked. */
    val isIdle: Boolean get() = !running && banked == 0L && finishedAt == 0L

    companion object {
        val DEFAULT_DURATION = 5 * 60_000L
    }
}

/**
 * The four things that can happen to a timer.
 *
 * Pure, and separate from the store for the reason [com.aura.launcher.macros.MacroMatch]
 * is separate from the player: this is where the feature is either right or
 * wrong, and it should be answerable without a device, a clock or a screen.
 * Every one of them takes the current time rather than reading it, so a test
 * can run a whole session in a few lines.
 */
object Timers {

    /** Sets it going, or does nothing if it already is. */
    fun start(state: TimerState, now: Long): TimerState =
        if (state.running) state else state.copy(startedAt = now, finishedAt = 0L)

    /**
     * Stops the clock, keeping what it has counted.
     *
     * The banked total is worked out here rather than left to be recomputed,
     * because from this moment there is no running stretch to add.
     */
    fun pause(state: TimerState, now: Long): TimerState =
        if (!state.running) state else state.copy(banked = state.elapsed(now), startedAt = 0L)

    fun toggle(state: TimerState, now: Long): TimerState =
        if (state.running) pause(state, now) else start(state, now)

    /** Back to zero, keeping the mode and the length that was chosen. */
    fun reset(state: TimerState): TimerState =
        state.copy(startedAt = 0L, banked = 0L, finishedAt = 0L)

    /**
     * Switches between counting up and counting down.
     *
     * Always from zero. Carrying twelve seconds of stopwatch into a countdown
     * would start it already part-spent, which is a thing nobody has ever
     * wanted and which reads as a bug every time.
     */
    fun setMode(state: TimerState, mode: TimerMode): TimerState =
        if (state.mode == mode) state else reset(state).copy(mode = mode)

    /**
     * Sets how long a countdown runs for.
     *
     * Resets the clock with it: changing the length of something already half
     * elapsed has no answer that is not a surprise — five minutes from now, or
     * five minutes from when it started, are different clocks and the user
     * asked for one of them without saying which.
     */
    fun setDuration(state: TimerState, duration: Long): TimerState {
        val length = duration.coerceIn(MIN_DURATION, MAX_DURATION)
        return reset(state).copy(
            mode = TimerMode.COUNTDOWN,
            duration = length,
            lastDuration = length,
        )
    }

    /**
     * What the state becomes once a countdown has run out.
     *
     * Parked at exactly its own length rather than left running, so the face
     * reads 00:00 and stays there instead of the elapsed time creeping past it
     * while nobody is looking.
     */
    fun finish(state: TimerState, now: Long): TimerState =
        if (state.mode != TimerMode.COUNTDOWN) state
        else state.copy(startedAt = 0L, banked = state.duration, finishedAt = now)

    /**
     * When a running countdown will reach zero, or null.
     *
     * What the alarm is set for. Null whenever there is nothing to wake up for
     * — a stopwatch, a paused clock, one already finished.
     */
    fun endsAt(state: TimerState, now: Long): Long? {
        if (state.mode != TimerMode.COUNTDOWN || !state.running) return null
        val left = state.remaining(now)
        return if (left <= 0L) null else now + left
    }

    /** The shortest and longest a countdown may be. */
    val MIN_DURATION = 1_000L
    val MAX_DURATION = 24 * 60 * 60_000L
}

/**
 * The face, as text.
 *
 * `mm:ss` until an hour has passed and `h:mm:ss` after it, because a leading
 * `00:` on a kitchen timer is two characters of nothing — and because the jump
 * only ever happens once per run, long after anybody is watching the digits
 * change.
 *
 * Rounded up rather than down on a countdown, which is what a clock showing
 * whole seconds has to do to be honest: with truncation a timer set to five
 * minutes shows 04:59 for the whole of its first second, and the last second
 * shows 00:00 while it is still running.
 */
fun formatDuration(millis: Long, roundUp: Boolean = false): String {
    val safe = millis.coerceAtLeast(0L)
    val totalSeconds = if (roundUp) (safe + 999L) / 1000L else safe / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
