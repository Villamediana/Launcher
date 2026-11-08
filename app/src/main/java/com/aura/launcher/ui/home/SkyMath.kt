package com.aura.launcher.ui.home

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The sky, worked out on the phone: no network, no permission beyond where you
 * roughly are.
 *
 * Sunrise and sunset from the standard sunrise equation (the one NOAA's own
 * calculator simplifies from) — good to about a minute at the latitudes people
 * live at, which is finer than the widget draws. The moon's phase from its
 * synodic month counted from a known new moon.
 */
object SkyMath {

    data class SunDay(
        /** Epoch millis, or null in polar day/night when the sun does not cross. */
        val rise: Long?,
        val set: Long?,
    )

    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val DAY_MS = 86_400_000.0
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun julian(millis: Long): Double = millis / DAY_MS + UNIX_EPOCH_JD
    fun millis(julian: Double): Long = ((julian - UNIX_EPOCH_JD) * DAY_MS).roundToLong()

    /**
     * The sunrise and sunset of the solar day containing [middayMillis] — pass
     * any moment of the local day; local noon is the safest.
     */
    fun sunDay(latitude: Double, longitude: Double, middayMillis: Long): SunDay {
        val n = floor(julian(middayMillis) - J2000 + 0.0008 + 0.5)
        val meanSolarNoon = n - longitude / 360.0
        val m = (357.5291 + 0.98560028 * meanSolarNoon).mod(360.0)
        val c = 1.9148 * sin(rad(m)) + 0.0200 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180.0 + 102.9372).mod(360.0)
        val transit = J2000 + meanSolarNoon + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val declination = asin(sin(rad(lambda)) * sin(rad(23.4397)))
        val cosHour = (sin(rad(-0.833)) - sin(rad(latitude)) * sin(declination)) /
            (cos(rad(latitude)) * cos(declination))
        if (cosHour < -1.0 || cosHour > 1.0) return SunDay(null, null)
        val hour = deg(acos(cosHour))
        return SunDay(
            rise = millis(transit - hour / 360.0),
            set = millis(transit + hour / 360.0),
        )
    }

    /** How far through the day's arc the sun is: 0 at sunrise, 1 at sunset, outside that at night. */
    fun dayProgress(day: SunDay, now: Long): Float? {
        val rise = day.rise ?: return null
        val set = day.set ?: return null
        return ((now - rise).toDouble() / (set - rise)).toFloat()
    }

    private const val SYNODIC = 29.530588853
    /** A new moon: 2000-01-06 18:14 UTC. */
    private const val KNOWN_NEW_MOON_JD = 2451550.1

    /** 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
    fun moonAge(now: Long): Double = ((julian(now) - KNOWN_NEW_MOON_JD) / SYNODIC).mod(1.0)

    /** The lit fraction of the disc, 0..1. */
    fun moonLit(age: Double): Double = (1 - cos(2 * PI * age)) / 2
}
