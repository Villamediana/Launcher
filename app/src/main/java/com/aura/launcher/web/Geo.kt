package com.aura.launcher.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class Place(
    val name: String,
    val detail: String,
    val lat: Double,
    val lon: Double,
    /** Metres from wherever the search started, or null if it was not local. */
    val distanceM: Double? = null,
    /** OpenStreetMap's opening_hours, as written there ("Mo-Fr 08:00-18:00"). */
    val hours: String? = null,
    val phone: String? = null,
) {
    val distanceText: String?
        get() = distanceM?.let {
            if (it < 950) "${it.roundToLong()} m" else "%.1f km".format(Locale.US, it / 1000)
        }
}

data class RouteStep(val instruction: String, val distanceM: Double)

data class Route(
    val distanceM: Double,
    val durationS: Double,
    /** Decoded shape, in order, as lat/lon pairs. */
    val points: List<Pair<Double, Double>>,
    val steps: List<RouteStep>,
) {
    val distanceText: String
        get() = if (distanceM < 950) "${distanceM.roundToLong()} m"
        else "%.1f km".format(Locale.US, distanceM / 1000)

    val durationText: String
        get() {
            val minutes = (durationS / 60).roundToLong()
            return when {
                minutes < 1 -> "under a minute"
                minutes < 60 -> "$minutes min"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }
}

enum class TravelMode(val profile: String) {
    DRIVING("car"),
    WALKING("foot"),
    CYCLING("bike"),
}

/**
 * Places and routes, from OpenStreetMap's own public services.
 *
 * Nominatim for names and Project OSRM for routing, both chosen for the same
 * reason Open-Meteo was chosen for the weather: no account, no key, no signup
 * standing between the user and an answer. They are courtesy services, so this
 * asks for as little as it can — one request per question, a real User-Agent as
 * their policy requires, and results cached for the length of a conversation.
 */
class Geo {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Answers repeat constantly inside one exchange — "there" asked three ways.
     *
     * Concurrent, and bounded. Tools run on the assistant's thread pool and a
     * single request can have several of them geocoding at once, which a plain
     * LinkedHashMap does not survive: interleaved writes lose entries at best
     * and corrupt the table at worst. The bound is because nothing here ever
     * evicted — a phone left on for a week accumulated every place anyone had
     * ever asked about, in a map that is only useful for the last few minutes.
     */
    private val geocodeCache = ConcurrentHashMap<String, List<Place>>()

    /**
     * Finds [query] by name.
     *
     * When [nearLat]/[nearLon] are given the search is biased towards that area
     * and results come back with distances, which is what makes "a pharmacy" mean
     * a pharmacy here rather than the most famous one on the continent.
     */
    suspend fun search(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        limit: Int = 6,
    ): Result<List<Place>> = withContext(Dispatchers.IO) {
        val key = "$query|$nearLat|$nearLon|$limit"
        geocodeCache[key]?.let { return@withContext Result.success(it) }

        runCatching {
            val url = buildString {
                append("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&extratags=1")
                append("&limit=").append(limit)
                append("&q=").append(URLEncoder.encode(query, "UTF-8"))
                if (nearLat != null && nearLon != null) {
                    // A box roughly 40 km across, ordered by distance from the
                    // middle of it. Nominatim will still look outside the box —
                    // `bounded` would return nothing at all for a town with no
                    // match, which is worse than a slightly distant one.
                    val d = 0.18
                    append("&viewbox=")
                    append(num(nearLon - d)).append(',').append(num(nearLat + d)).append(',')
                    append(num(nearLon + d)).append(',').append(num(nearLat - d))
                }
            }

            val body = get(url)
            val places = json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                val display = o["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val short = o["name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: display.substringBefore(',').ifBlank { "Unnamed place" }
                val extra = (o["extratags"] as? kotlinx.serialization.json.JsonObject)
                fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { k -> extra?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
                Place(
                    name = short,
                    detail = display.substringAfter(',').trim().take(120),
                    lat = lat,
                    lon = lon,
                    hours = tag("opening_hours"),
                    phone = tag("phone", "contact:phone", "contact:mobile"),
                    distanceM = if (nearLat != null && nearLon != null) {
                        distanceMetres(nearLat, nearLon, lat, lon)
                    } else {
                        null
                    },
                )
            }.sortedBy { it.distanceM ?: 0.0 }

            // Cheapest eviction there is, and the right one for a cache whose
            // value is entirely in the current exchange: when it fills up, drop
            // it and start again.
            if (geocodeCache.size >= MAX_CACHED_LOOKUPS) geocodeCache.clear()
            geocodeCache[key] = places
            places
        }
    }

    /** Route between two points, or a failure explaining why there is none. */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        mode: TravelMode = TravelMode.DRIVING,
    ): Result<Route> = withContext(Dispatchers.IO) {
        runCatching {
            // FOSSGIS's OSRM, not the project's demo server: the demo only
            // loads the car profile and answers every "foot" or "bike" request
            // with the car route — 8.5 km on foot in 12 minutes. Here each
            // profile is its own instance (routed-car / -foot / -bike), and
            // the path segment after /v1/ is ignored.
            val url = "https://routing.openstreetmap.de/routed-${mode.profile}/route/v1/driving/" +
                "${num(fromLon)},${num(fromLat)};${num(toLon)},${num(toLat)}" +
                "?overview=full&geometries=geojson&steps=true&alternatives=false"

            val root = json.parseToJsonElement(get(url)).jsonObject
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            if (code != null && code != "Ok") {
                error(
                    when (code) {
                        "NoRoute" -> "There is no route between those two places by ${mode.name.lowercase()}."
                        "NoSegment" -> "One of those places is not near any road OSRM knows about."
                        else -> "The routing service said: $code"
                    }
                )
            }

            val leg = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("The routing service returned no route.")

            val coordinates = leg["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
                ?.mapNotNull { pair ->
                    val a = pair.jsonArray
                    val lon = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    lat to lon
                }
                .orEmpty()

            val steps = leg["legs"]?.jsonArray?.flatMap { legEntry ->
                legEntry.jsonObject["steps"]?.jsonArray?.mapNotNull { step ->
                    val s = step.jsonObject
                    val distance = s["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val instruction = describeStep(s)
                    if (instruction.isBlank()) null else RouteStep(instruction, distance)
                }.orEmpty()
            }.orEmpty()

            Route(
                distanceM = leg["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                durationS = leg["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                points = coordinates,
                steps = steps,
            )
        }
    }

    /**
     * OSRM's manoeuvres are structured, not written, so the sentence is built
     * here. Turn-by-turn text is the part a person actually reads out loud.
     */
    private fun describeStep(step: kotlinx.serialization.json.JsonObject): String {
        val name = step["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val manoeuvre = step["maneuver"]?.jsonObject
        val type = manoeuvre?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val modifier = manoeuvre?.get("modifier")?.jsonPrimitive?.contentOrNull.orEmpty()

        val verb = when (type) {
            "depart" -> "Start out"
            "arrive" -> "Arrive"
            "roundabout", "rotary" -> "Take the roundabout"
            "merge" -> "Merge"
            "fork" -> "Keep ${modifier.ifBlank { "ahead" }}"
            "end of road" -> "At the end of the road, turn ${modifier.ifBlank { "" }}".trim()
            "continue" -> if (modifier.isBlank() || modifier == "straight") "Continue" else "Continue $modifier"
            "new name" -> "Continue"
            "turn" -> "Turn ${modifier.ifBlank { "" }}".trim()
            else -> if (modifier.isBlank()) "Continue" else "Keep $modifier"
        }
        return if (name != null) "$verb onto $name" else verb
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            // Both services ask for an identifiable agent, and both are within
            // their rights to refuse one that has none.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(200)}")
            return body
        }
    }

    private fun num(v: Double) = String.format(Locale.US, "%.6f", v)

    companion object {
        /**
         * How many lookups the cache keeps before it is emptied.
         *
         * Generous for one exchange and small enough to be invisible: a place
         * is a name and a handful of numbers.
         */
        private const val MAX_CACHED_LOOKUPS = 200

        private const val USER_AGENT = "AuraLauncher/1.0 (Android home screen assistant)"

        /**
         * Great-circle distance in metres.
         *
         * Haversine, which is accurate to well under a percent at the scale
         * anything here asks about — "is this shop closer than that one".
         */
        fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
