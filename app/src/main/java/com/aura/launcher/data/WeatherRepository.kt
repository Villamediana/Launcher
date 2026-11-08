package com.aura.launcher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class Weather(
    val celsius: Int,
    val code: Int,
    val fetchedAt: Long = System.currentTimeMillis(),
    /** Daylight where the phone is, as the provider says; null when it did not. */
    val isDay: Boolean? = null,
    /** Wind at 10 m, km/h. */
    val windKmh: Float = 0f,
    /** Rain, showers and snow in the last hour, mm. */
    val precipitationMm: Float = 0f,
) {
    /**
     * A glyph for the sky.
     *
     * WMO weather codes, grouped the way a person would: the difference between
     * "slight" and "moderate" drizzle does not belong on a home screen.
     */
    val glyph: String
        get() = when (code) {
            0 -> "☀"
            1, 2 -> "⛅"
            3 -> "☁"
            in 45..48 -> "🌫"
            in 51..57, in 61..65, in 80..82 -> "🌧"
            in 66..67, in 71..77, in 85..86 -> "❄"
            in 95..99 -> "⛈"
            else -> "🌡"
        }
}

/** One hour of the forecast: when, how warm, and what the sky is doing. */
data class HourPoint(val atMillis: Long, val celsius: Int, val code: Int) {
    val glyph: String get() = Weather(celsius, code).glyph
}

/**
 * Hours and days ahead, fetched together.
 *
 * One request rather than two, because Open-Meteo returns both from the same
 * endpoint and the launcher should not make a phone pay for two round trips to
 * fill one sheet.
 */
data class Forecast(
    val hourly: List<HourPoint>,
    /** IANA zone returned for the forecast coordinates, e.g. America/Sao_Paulo. */
    val timeZoneId: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * The temperature outside, from Open-Meteo.
 *
 * No API key, which is the whole reason for choosing it: a weather line on the
 * home screen should not begin with the user signing up for something. Location
 * normally comes from what the system already knows. If Android has no recent
 * fix (common just after boot), one bounded coarse request fills that gap; no
 * continuous location tracking is left running.
 */
class WeatherRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: Weather? = null
    @Volatile private var cachedForecast: Forecast? = null
    /** Prevents the home line and day sheet from refreshing location/network together. */
    private val refreshLock = Mutex()

    val hasPermission: Boolean
        get() = Locator.hasPermission(context)

    /**
     * The current conditions, cached for [CACHE_MS].
     *
     * The weather does not change in the time it takes to unlock a phone, and a
     * home screen is looked at dozens of times an hour.
     */
    suspend fun current(): Weather? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            cached?.takeIf { System.currentTimeMillis() - it.fetchedAt < CACHE_MS }
                ?.let { return@withLock it }
            if (!hasPermission) return@withLock null

            val location = Locator.currentOrLastKnown(context) ?: return@withLock cached

            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${"%.3f".format(java.util.Locale.US, location.latitude)}" +
                    "&longitude=${"%.3f".format(java.util.Locale.US, location.longitude)}" +
                    "&current=temperature_2m,weather_code,is_day,wind_speed_10m,precipitation"

                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    parseCurrentWeather(response.body?.string().orEmpty())
                }
            }.onSuccess { cached = it }
                .onFailure { Log.w(TAG, "Could not update current weather", it) }
                // Stale beats blank: a failed refresh should not empty the line.
                .getOrElse { cached }
        }
    }

    /**
     * The hours and days ahead, cached for [FORECAST_CACHE_MS].
     *
     * Cached longer than [current] because a forecast is a forecast: the next
     * three days do not change between two openings of a sheet, and this is a
     * heavier response than the one-line current conditions.
     *
     * `timeformat=unixtime` on purpose. The ISO strings Open-Meteo returns by
     * default are local wall-clock without an offset, which is exactly the kind
     * of value that looks right in testing and lands an hour out for anyone in
     * a different zone than the developer.
     */
    suspend fun forecast(): Forecast? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            cachedForecast?.takeIf { System.currentTimeMillis() - it.fetchedAt < FORECAST_CACHE_MS }
                ?.let { return@withLock it }
            if (!hasPermission) return@withLock null

            val location = Locator.currentOrLastKnown(context) ?: return@withLock cachedForecast

            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${"%.3f".format(java.util.Locale.US, location.latitude)}" +
                    "&longitude=${"%.3f".format(java.util.Locale.US, location.longitude)}" +
                    "&hourly=temperature_2m,weather_code" +
                    "&forecast_days=$FORECAST_DAYS&timeformat=unixtime&timezone=auto"

                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    parseWeatherForecast(response.body?.string().orEmpty())
                }
            }.onSuccess { cachedForecast = it }
                .onFailure { Log.w(TAG, "Could not update weather forecast", it) }
                // Stale beats blank, same as the current conditions.
                .getOrElse { cachedForecast }
        }
    }

    private companion object {
        const val TAG = "AuraWeather"
        const val CACHE_MS = 20 * 60 * 1000L

        /** A forecast for the next few days does not go stale in an hour. */
        const val FORECAST_CACHE_MS = 60 * 60 * 1000L
        const val FORECAST_DAYS = 2
    }
}

private val weatherJson = Json { ignoreUnknownKeys = true }
private const val HOURS_SHOWN = 12
private const val ONE_HOUR_MS = 60 * 60 * 1000L

/** Parse separately from transport so the exact provider payload stays testable. */
internal fun parseCurrentWeather(
    body: String,
    fetchedAt: Long = System.currentTimeMillis(),
): Weather {
    val current = weatherJson.parseToJsonElement(body)
        .jsonObject["current"]?.jsonObject ?: error("No current block")
    return Weather(
        celsius = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
            ?: error("No temperature"),
        code = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 0,
        fetchedAt = fetchedAt,
        isDay = current["is_day"]?.jsonPrimitive?.intOrNull?.let { it == 1 },
        windKmh = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
        precipitationMm = current["precipitation"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
    )
}

internal fun parseWeatherForecast(
    body: String,
    nowMillis: Long = System.currentTimeMillis(),
    fetchedAt: Long = System.currentTimeMillis(),
): Forecast {
    val root = weatherJson.parseToJsonElement(body).jsonObject
    val hourlyBlock = root["hourly"]?.jsonObject ?: error("No hourly block")
    val times = hourlyBlock["time"]?.jsonArray.orEmpty()
    val temps = hourlyBlock["temperature_2m"]?.jsonArray.orEmpty()
    val codes = hourlyBlock["weather_code"]?.jsonArray.orEmpty()

    // From this hour onward only. The response starts at midnight, and a
    // carousel that opens on "00:00" at nine is answering nobody's question.
    val hourly = times.indices.mapNotNull { i ->
        val at = times[i].jsonPrimitive.longOrNull?.times(1000L) ?: return@mapNotNull null
        if (at + ONE_HOUR_MS < nowMillis) return@mapNotNull null
        HourPoint(
            atMillis = at,
            celsius = temps.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: return@mapNotNull null,
            code = codes.getOrNull(i)?.jsonPrimitive?.intOrNull ?: 0,
        )
    }.take(HOURS_SHOWN)
    if (hourly.isEmpty()) error("No future hourly forecast")

    return Forecast(
        hourly = hourly,
        timeZoneId = root["timezone"]?.jsonPrimitive?.contentOrNull,
        fetchedAt = fetchedAt,
    )
}
