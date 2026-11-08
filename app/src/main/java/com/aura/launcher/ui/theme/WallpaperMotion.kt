package com.aura.launcher.ui.theme

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The clock and the tilt the animated wallpapers are drawn from.
 *
 * Both are here rather than in the scenes because both are about what the
 * wallpaper costs to run, and a home screen runs all day. Three decisions carry
 * that whole budget.
 *
 * **One state write per frame, and fewer frames than the display has.** A
 * wallpaper is not a game; nothing in these scenes moves fast enough to need
 * sixty samples a second, let alone the hundred and twenty a modern panel will
 * happily give. The loop below emits at [FPS] and drops everything in between,
 * which is a third to a quarter of the draws — and because the clock and the
 * tilt land in the same object, a frame is one invalidation rather than two.
 *
 * **The loop is driven by `withFrameNanos`.** It asks the platform for the next
 * frame and suspends until there is one. When the screen goes off, or the
 * launcher is behind another app, the platform stops producing frames and the
 * loop stops with it — no timer to remember to cancel, no wakelock, nothing
 * running behind a black screen.
 *
 * **The sensor is registered only while the launcher is resumed**, at the
 * slowest rate that still reads as smooth, and the cheapest sensor that can
 * answer the question. Parallax is worth very little if the price is a
 * gyroscope spinning while the user is in another app.
 */

/** One frame's worth of the world: what time it is, and which way the phone is held. */
@Stable
class SceneFrame(
    /** Milliseconds since this wallpaper started drawing. Monotonic; never wraps. */
    val timeMs: Long,
    /** Roll, -1 (tipped left) to 1 (tipped right), already smoothed. */
    val tiltX: Float,
    /** Pitch, -1 (top tipped away) to 1 (top tipped toward you). */
    val tiltY: Float,
)

/**
 * Whether the scenes react to how the phone is held.
 *
 * Off while the battery is being measured, and it is the parallax alone that is
 * off — not the motion. The moon still crosses, the cloud still drifts, the
 * brush still paints. What stops is the one part of this that reaches outside
 * the frame loop and asks the hardware a question, which is the only part worth
 * ruling out first: with this false no sensor is registered, nothing is
 * subscribed to a lifecycle for it, and every layer is drawn at rest.
 */
const val WALLPAPER_PARALLAX_ENABLED = false

/** Frames the tilt takes to catch up. Slow enough to ignore a footstep. */
private const val TILT_SMOOTHING = 0.10f

/**
 * How far the phone tips before the parallax is at full stretch: about thirty
 * degrees off however it is being held, which is a comfortable wrist movement
 * rather than something that needs a shoulder.
 */
private const val TILT_SPAN = 0.5f

/**
 * The attitude parallax is measured from — a phone held at a natural reading
 * angle, tipped back about twenty degrees, rather than lying flat on a table.
 * Centring on this is what makes the scene sit still in the hand instead of
 * arriving already pushed to one side.
 */
private const val TILT_REST_Z = 0.34f

/** Written by the sensor thread, read once a frame. Deliberately not state. */
private class Gravity {
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var live = false
}

@Composable
internal fun rememberSceneFrame(): State<SceneFrame> {
    val frame = remember { mutableStateOf(SceneFrame(0L, 0f, 0f)) }
    val gravity = remember { Gravity() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, WALLPAPER_PARALLAX_ENABLED) {
        if (!WALLPAPER_PARALLAX_ENABLED) return@DisposableEffect onDispose { }
        val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val norm = sqrt(x * x + y * y + z * z)
                if (norm < 1f) return
                gravity.x = (x / norm / TILT_SPAN).coerceIn(-1f, 1f)
                gravity.y = ((z / norm - TILT_REST_Z) / TILT_SPAN).coerceIn(-1f, 1f)
                gravity.live = true
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        var attached = false
        fun attach() {
            if (attached || sensor == null) return
            attached = sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        fun detach() {
            if (attached) sensors.unregisterListener(listener)
            attached = false
            gravity.live = false
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> attach()
                Lifecycle.Event.ON_PAUSE -> detach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) attach()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            detach()
        }
    }

    // Crucially, LaunchedEffect contributes Compose's MonotonicFrameClock to
    // this coroutine. A standalone Main dispatcher does not have one;
    // withFrameNanos then throws as soon as an animated wallpaper is composed.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // On the home screen's shared clock (see [Ambient]): asleep between
            // ticks rather than woken on every vsync, and on the same ticks as
            // the orb and the sky, so they are drawn in one frame.
            val fps = Ambient.fps(context)
            val base = frame.value.timeMs
            var origin = 0L
            var smoothX = frame.value.tiltX
            var smoothY = frame.value.tiltY
            while (true) {
                if (origin != 0L) Ambient.nextTick(fps)
                withFrameNanos { now ->
                    if (origin == 0L) origin = now
                    val targetX = if (gravity.live) gravity.x else 0f
                    val targetY = if (gravity.live) gravity.y else 0f
                    smoothX += (targetX - smoothX) * TILT_SMOOTHING
                    smoothY += (targetY - smoothY) * TILT_SMOOTHING
                    frame.value = SceneFrame(
                        timeMs = base + (now - origin) / 1_000_000L,
                        tiltX = smoothX,
                        tiltY = smoothY,
                    )
                }
            }
        }
    }

    return frame
}

/**
 * The scene's sense of time.
 *
 * Kept in double seconds and never wrapped, because wrapping is what forces
 * every animation onto one shared period. Here each one asks for the phase of
 * its *own* period — and since those periods have no common multiple worth
 * reaching, the scene as a whole never repeats. That is the whole trick: an
 * hour of watching is an hour of arrangements that have not been on screen
 * before, out of code that holds no more state than a number.
 */
@JvmInline
value class Clock(val seconds: Double) {

    /** Where we are inside a cycle of [period] seconds, 0..1. Continuous at the wrap. */
    fun phase(period: Double): Float {
        val p = (seconds / period) % 1.0
        return (if (p < 0.0) p + 1.0 else p).toFloat()
    }

    /**
     * Which run of a repeating one-shot we are in.
     *
     * A meteor, a brush stroke, a constellation being traced: things that happen
     * rather than cycle. The index seeds everything about that run, so the next
     * one is a different meteor rather than the same one again.
     */
    fun episode(period: Double): Long = floor(seconds / period).toLong()

    /** How far through the current run of [period], 0..1. */
    fun within(period: Double): Float = phase(period)
}

/**
 * A deterministic number for (episode, salt), 0..1.
 *
 * This is what makes the scenes procedural rather than choreographed. Nothing
 * about the ninth meteor is stored anywhere; its path, its length and whether
 * it happens at all are read back out of the number nine whenever they are
 * needed, and they are the same every time they are asked for.
 */
fun hashed(episode: Long, salt: Int): Float {
    var h = episode * -0x61c8864680b583ebL xor (salt * 0x27d4eb2d).toLong()
    h = h xor (h ushr 33)
    h *= -0x7ee3623a03d3c83fL
    h = h xor (h ushr 29)
    h *= -0x3b314601e57a13adL
    h = h xor (h ushr 32)
    return abs((h ushr 11).toDouble() / 9_007_199_254_740_992.0).toFloat()
}

/** [hashed], spread over a range. */
fun hashed(episode: Long, salt: Int, from: Float, to: Float): Float =
    from + (to - from) * hashed(episode, salt)

/** True for the given share of episodes — how a thing becomes occasional. */
fun hashedChance(episode: Long, salt: Int, chance: Float): Boolean =
    hashed(episode, salt) < chance
