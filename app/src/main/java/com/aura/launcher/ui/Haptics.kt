package com.aura.launcher.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * What a hands-free exchange feels like in a pocket.
 *
 * The whole point of calling Aura's name is that nobody is looking at the
 * phone, which leaves exactly two channels: sound, and the vibrator. Sound is
 * the one that is rude in a meeting and useless in a noisy kitchen, so the
 * vibrator carries most of the conversation — and a single flat buzz for every
 * stage of it says only "something happened", which is the thing being fixed
 * here.
 *
 * Four moments, four shapes, deliberately distinguishable through a trouser
 * pocket:
 *
 *  - [wake]: light then firm. A rising pair reads as attention being given.
 *  - [captured]: one small tick. The question is in; stop talking.
 *  - [answered]: firm then light. The mirror of the wake, so an exchange opens
 *    and closes with the same gesture played in reverse.
 *  - [missed]: two low, slow, soft pulses. Flat where the others rise and fall,
 *    which is the shape of a shrug — and never mistakable for an answer.
 *
 * Written against two generations of the API on purpose. The composition
 * primitives are the only ones that feel designed rather than timed, but they
 * are Android 12 and optional hardware on top of that, so a phone without them
 * falls back to an amplitude waveform. The patterns are chosen so the *rhythm*
 * survives both, which is the part actually being read.
 */
class Haptics(
    private val context: Context,
    private val enabled: () -> Boolean,
) {

    /** One stage of a hands-free exchange. */
    private enum class Moment { WAKE, CAPTURED, ANSWERED, MISSED }

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        }.getOrNull()
    }

    /**
     * Whether this device can play a composed pattern rather than a timed one.
     *
     * All three primitives are required rather than each being checked where it
     * is used: a phone that can play two of them would otherwise get two of the
     * four moments composed and two of them timed, and the four would stop
     * being recognisably one family.
     */
    private val composed: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsPrimitives()
    }

    /** Aura heard her name. Light, then firm: the phone sitting up straight. */
    fun wake() = play(Moment.WAKE)

    /** The question is in and being worked on. One tick, and out of the way. */
    fun captured() = play(Moment.CAPTURED)

    /** The answer landed. The wake played backwards: firm, then light. */
    fun answered() = play(Moment.ANSWERED)

    /** Called, and nothing understood. Soft and flat, unlike the other three. */
    fun missed() = play(Moment.MISSED)

    private fun play(moment: Moment) {
        if (!enabled()) return
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            // The version check is repeated here rather than left to [composed]
            // alone: it is the same condition, and stating it at the call site
            // is what makes the API level of everything inside [compose]
            // checkable rather than a promise held in a lazy field.
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && composed) {
                compose(moment)
            } else {
                timed(moment)
            }
            vibrator.vibrate(effect)
        }.onFailure { Log.w(TAG, "Could not play a haptic", it) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun supportsPrimitives(): Boolean = vibrator?.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
    ) == true

    /** The good version: scaled primitives, spaced in milliseconds. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun compose(moment: Moment): VibrationEffect {
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        val low = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        val composition = VibrationEffect.startComposition()
        when (moment) {
            Moment.WAKE -> composition
                .addPrimitive(tick, 0.45f, 0)
                .addPrimitive(click, 0.85f, 55)

            Moment.CAPTURED -> composition
                .addPrimitive(tick, 0.55f, 0)

            Moment.ANSWERED -> composition
                .addPrimitive(click, 0.75f, 0)
                .addPrimitive(tick, 0.40f, 70)

            Moment.MISSED -> composition
                .addPrimitive(low, 0.70f, 0)
                .addPrimitive(low, 0.45f, 95)
        }
        return composition.compose()
    }

    /**
     * The fallback: pauses and pulses in alternation, which is all
     * `createWaveform` understands. The amplitudes carry the character; the
     * timings only keep each pulse short enough to read as a single event.
     */
    private fun timed(moment: Moment): VibrationEffect = when (moment) {
        Moment.WAKE -> VibrationEffect.createWaveform(
            longArrayOf(0, 14, 46, 30), intArrayOf(0, 105, 0, 235), NO_REPEAT,
        )

        Moment.CAPTURED -> VibrationEffect.createWaveform(
            longArrayOf(0, 16), intArrayOf(0, 120), NO_REPEAT,
        )

        Moment.ANSWERED -> VibrationEffect.createWaveform(
            longArrayOf(0, 26, 58, 16), intArrayOf(0, 215, 0, 95), NO_REPEAT,
        )

        Moment.MISSED -> VibrationEffect.createWaveform(
            longArrayOf(0, 90, 70, 60), intArrayOf(0, 70, 0, 45), NO_REPEAT,
        )
    }

    private companion object {
        const val TAG = "Haptics"
        const val NO_REPEAT = -1
    }
}
