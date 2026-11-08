package com.aura.launcher

import com.aura.launcher.web.Transit
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class TransitTest {

    @Test
    fun `decodes google's reference polyline`() {
        val points = Transit.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        val expected = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        assertEquals(expected.size, points.size)
        expected.zip(points).forEach { (e, p) ->
            assertEquals(e.first, p.first, 1e-6)
            assertEquals(e.second, p.second, 1e-6)
        }
    }

    @Test
    fun `places keep the name and the most local areas`() {
        val body = """[{"type":"ADDRESS","name":"Avenida Paulista 1000,  -","lat":-23.5648865,"lon":-46.651918,
            "areas":[{"name":"Brasil","adminLevel":2},{"name":"São Paulo","adminLevel":8},{"name":"Bela Vista","adminLevel":10}]}]"""
        val place = Transit.parsePlaces(body).single()
        assertEquals("Avenida Paulista 1000", place.name)
        assertEquals("Bela Vista, São Paulo", place.detail)
        assertEquals(-23.5648865, place.lat, 1e-9)
    }

    @Test
    fun `an itinerary reads its legs, lines and colours`() {
        val body = """{"itineraries":[{"duration":2700,"startTime":"2026-09-24T15:02:00Z","endTime":"2026-09-24T15:47:00Z",
            "transfers":1,"legs":[
              {"mode":"WALK","duration":660,"from":{"name":"Início"},"legGeometry":{"points":"_p~iF~ps|U_ulLnnqC","precision":5}},
              {"mode":"BUS","duration":600,"routeShortName":"6200-10","routeColor":"782f40","headsign":"Term. Sto. Amaro",
               "from":{"name":"Parada 1"},"legGeometry":{"points":"_p~iF~ps|U_ulLnnqC","precision":5}},
              {"mode":"SUBWAY","duration":900,"routeShortName":"METRÔ L4","routeColor":"","from":{"name":"Paulista"},
               "legGeometry":{"points":"","precision":6}}]}]}"""
        val trip = Transit.parseItineraries(body).single()
        assertEquals(2700, trip.durationS)
        assertEquals(1, trip.transfers)
        assertEquals(listOf("WALK", "BUS", "SUBWAY"), trip.legs.map { it.mode })
        assertEquals("#782f40", trip.legs[1].color)
        assertEquals(null, trip.legs[2].color)
        assertEquals("6200-10", trip.legs[1].line)
        assertEquals(2, trip.legs[1].points.size)
        assertEquals(true, trip.legs[0].isWalk)
        assert(abs(trip.startMs - 1790262120000L) < 1)
    }
}
