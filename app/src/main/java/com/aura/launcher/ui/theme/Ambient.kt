package com.aura.launcher.ui.theme

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * One clock for everything on the home screen that moves by itself: the
 * orb's face, the live sky, an animated wallpaper, a breathing dot.
 *
 * Each used to keep its own rate — about 29, 20 to 30, and 30 frames a second
 * — and since their ticks fell at different moments, the screen was drawn once
 * for each of them: seventy-odd frames a second for three slow things, which
 * is most of what a 60 Hz panel can do, spent on a screen nobody is touching.
 * Waking on the same boundaries of the same clock, they land on the same
 * vsync and share one frame.
 *
 * And fewer of them when the battery needs it: battery saver on, or low and
 * not charging, everything ambient drops to [SAVING_FPS]. Nor when nobody is
 * touching the phone: a home screen left on the table, or read without a
 * finger on it, drops to [IDLE_FPS] after [IDLE_MS] and is back at the full
 * rate on the next touch ([touched]).
 */
object Ambient {
    /** Frames a second for what moves slowly on its own. */
    const val FPS = 24

    /** The same, on battery saver or a low battery. */
    const val SAVING_FPS = 12

    /** The same, a while after the last touch. */
    const val IDLE_FPS = 12

    /** How long after the last touch the home screen calms down. */
    const val IDLE_MS = 15_000L

    @Volatile private var touchedAt = SystemClock.uptimeMillis()

    /** A finger on the screen: everything ambient back to its full rate. */
    fun touched() { touchedAt = SystemClock.uptimeMillis() }

    /** The rate right now. Cheap enough to read when a loop starts, not every frame. */
    fun fps(context: Context): Int = if (saving(context)) SAVING_FPS else FPS

    /**
     * Sleeps to the next tick of the shared clock at [fps]: the next multiple
     * of its period on the uptime clock, so every loop that calls this wakes
     * at the same moment and asks for the same frame.
     */
    suspend fun nextTick(fps: Int) {
        val now = SystemClock.uptimeMillis()
        delay(untilNextTick(now, rate(fps, now - touchedAt)))
        // Under a game the home screen is not seen: it waits, still, until it is.
        if (covers.value > 0) covers.first { it == 0 }
    }

    /**
     * How many full-screen things are over the home screen right now. A game
     * opens in its own window, and the activity under it stays resumed — so
     * the orb, the sky and the wallpaper went on drawing behind it, for nobody.
     */
    private val covers = MutableStateFlow(0)

    /** The home screen is hidden until the returned call is made. */
    fun cover(): () -> Unit {
        covers.update { it + 1 }
        var done = false
        return { if (!done) { done = true; covers.update { it - 1 } } }
    }

    /** The rate a loop asking for [fps] gets, [sinceTouch] ms after the last touch. */
    internal fun rate(fps: Int, sinceTouch: Long): Int = if (sinceTouch >= IDLE_MS) minOf(fps, IDLE_FPS) else fps

    /** Milliseconds from [now] to the next tick at [fps]; never zero, so a loop always yields. */
    internal fun untilNextTick(now: Long, fps: Int): Long {
        val period = 1000L / fps.coerceIn(1, 60)
        return period - now % period
    }

    /** Battery saver, or under 15% and not charging. */
    fun saving(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isPowerSaveMode == true) return true
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1..15 && !battery.isCharging
    }
}
