package com.aura.launcher

import com.aura.launcher.data.parseCurrentWeather
import com.aura.launcher.data.parseWeatherForecast
import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider payloads stay aligned with what both weather surfaces render. */
class WeatherRepositoryTest {

    @Test
    fun `current conditions round temperature instead of truncating it`() {
        val weather = parseCurrentWeather(
            body = """{"current":{"temperature_2m":14.6,"weather_code":53}}""",
            fetchedAt = 42L,
        )

        assertEquals(15, weather.celsius)
        assertEquals(53, weather.code)
        assertEquals(42L, weather.fetchedAt)
    }

    @Test
    fun `forecast keeps future hours and provider timezone`() {
        val forecast = parseWeatherForecast(
            body = """
                {
                  "timezone":"America/Sao_Paulo",
                  "hourly":{
                    "time":[0,3600,7200,10800],
                    "temperature_2m":[10.2,11.4,12.6,13.8],
                    "weather_code":[0,1,2,3]
                  }
                }
            """.trimIndent(),
            nowMillis = 7_500_000L,
            fetchedAt = 99L,
        )

        assertEquals("America/Sao_Paulo", forecast.timeZoneId)
        assertEquals(listOf(13, 14), forecast.hourly.map { it.celsius })
        assertEquals(listOf(2, 3), forecast.hourly.map { it.code })
        assertEquals(99L, forecast.fetchedAt)
    }
}
