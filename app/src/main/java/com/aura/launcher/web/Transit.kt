package com.aura.launcher.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Public transport and place search, from Transitous (transitous.org).
 *
 * A free, community-run MOTIS instance fed by the agencies' own GTFS — for the
 * two cities this was built for that means SPTrans buses and the São Paulo
 * metro, and Rio's buses — with no key and no account. It plans door to door
 * (walk, bus, metro, walk) and hands back each leg's shape and the line's own
 * colour, which is what lets the map draw a trip the way the signs in the
 * station do.
 *
 * Also its geocoder, for the "where to?" box: it autocompletes and takes a
 * position to rank near things first.
 *
 * Fair use: one request per keystroke-pause or per plan, and a real
 * User-Agent, as their operators ask.
 */
class Transit(
    private val base: String = "https://api.transitous.org",
) {
    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    data class Leg(
        /** WALK, BUS, SUBWAY, RAIL, TRAM, COACH, FERRY… as MOTIS names them. */
        val mode: String,
        /** Line number or name — "6200-10", "METRÔ L4". Blank for walking. */
        val line: String,
        /** The line's colour as #RRGGBB, when the agency publishes one. */
        val color: String?,
        val fromName: String,
        val headsign: String,
        val durationS: Int,
        val points: List<Pair<Double, Double>>,
    ) {
        val isWalk: Boolean get() = mode == "WALK"
    }

    data class Itinerary(val durationS: Int, val startMs: Long, val endMs: Long, val transfers: Int, val legs: List<Leg>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun suggest(text: String, nearLat: Double?, nearLon: Double?): Result<List<Place>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val near = if (nearLat != null && nearLon != null) "&place=${num(nearLat)},${num(nearLon)}" else ""
                val lang = Locale.getDefault().language
                parsePlaces(get("$base/api/v1/geocode?text=${enc(text)}$near&language=$lang"))
            }
        }

    suspend fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Result<List<Itinerary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                parseItineraries(
                    get("$base/api/v5/plan?fromPlace=${num(fromLat)},${num(fromLon)}&toPlace=${num(toLat)},${num(toLon)}")
                )
            }
        }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Transitous answered ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val USER_AGENT = "Aura-Launcher/1.0 (Android; +https://github.com/Villamediana)"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun num(v: Double) = String.format(Locale.US, "%.6f", v)
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        internal fun parsePlaces(body: String): List<Place> =
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val name = o.str("name").trim().trimEnd(',', '-', ' ')
                if (name.isEmpty()) return@mapNotNull null
                // The most local area that is not the country or a macro-region:
                // "Bela Vista", "São Paulo".
                val areas = (o["areas"] as? JsonArray).orEmpty()
                    .map { it.jsonObject }
                    .sortedByDescending { it["adminLevel"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
                    .mapNotNull { it.str("name").takeIf { n -> n.isNotBlank() } }
                    .filter { it != name }
                Place(name, areas.take(2).joinToString(", "), lat, lon)
            }

        internal fun parseItineraries(body: String): List<Itinerary> {
            val root = json.parseToJsonElement(body).jsonObject
            return (root["itineraries"] as? JsonArray).orEmpty().map { it.jsonObject }.map { it ->
                Itinerary(
                    durationS = it["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                    startMs = instant(it.str("startTime")),
                    endMs = instant(it.str("endTime")),
                    transfers = it["transfers"]?.jsonPrimitive?.intOrNull ?: 0,
                    legs = (it["legs"] as? JsonArray).orEmpty().map { leg -> parseLeg(leg.jsonObject) },
                )
            }
        }

        private fun parseLeg(o: JsonObject): Leg {
            val geometry = o["legGeometry"] as? JsonObject
            val precision = geometry?.get("precision")?.jsonPrimitive?.intOrNull ?: 6
            val encoded = geometry?.str("points").orEmpty()
            return Leg(
                mode = o.str("mode").ifBlank { "WALK" },
                line = o.str("routeShortName").ifBlank { o.str("displayName") },
                color = o.str("routeColor").takeIf { it.length == 6 }?.let { "#$it" },
                fromName = (o["from"] as? JsonObject)?.str("name").orEmpty(),
                headsign = o.str("headsign"),
                durationS = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                points = decodePolyline(encoded, precision),
            )
        }

        private fun instant(iso: String): Long =
            runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

        private fun JsonObject.str(key: String): String =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        /** Google's encoded polyline, at any precision (MOTIS uses 6 or 7). */
        fun decodePolyline(encoded: String, precision: Int = 5): List<Pair<Double, Double>> {
            val factor = Math.pow(10.0, precision.toDouble())
            val out = ArrayList<Pair<Double, Double>>()
            var index = 0
            var lat = 0L
            var lon = 0L
            fun next(): Long {
                var result = 0L
                var shift = 0
                while (index < encoded.length) {
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            }
            while (index < encoded.length) {
                lat += next()
                if (index > encoded.length) break
                lon += next()
                out += (lat / factor) to (lon / factor)
            }
            return out
        }
    }
}
