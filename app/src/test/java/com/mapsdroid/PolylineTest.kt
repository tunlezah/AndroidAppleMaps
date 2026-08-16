package com.mapsdroid

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.offline.ValhallaResponseMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineTest {

    /** Reference encoder (precision 6) used only to validate the decoder round-trips. */
    private fun encode(points: List<GeoPoint>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for (p in points) {
            val lat = Math.round(p.latitude * factor)
            val lng = Math.round(p.longitude * factor)
            encodeValue(lat - lastLat, sb)
            encodeValue(lng - lastLng, sb)
            lastLat = lat
            lastLng = lng
        }
        return sb.toString()
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v.toInt() and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }

    @Test
    fun decodeRoundTripsEncodedPoints() {
        val original = listOf(
            GeoPoint(38.5, -120.2),
            GeoPoint(40.7, -120.95),
            GeoPoint(43.252, -126.453),
        )
        val decoded = ValhallaResponseMapper.decodePolyline6(encode(original))
        assertEquals(original.size, decoded.size)
        original.forEachIndexed { i, p ->
            assertEquals(p.latitude, decoded[i].latitude, 1e-5)
            assertEquals(p.longitude, decoded[i].longitude, 1e-5)
        }
    }

    @Test
    fun mapsValhallaJsonIntoRoute() {
        val shape = encode(listOf(GeoPoint(37.0, -122.0), GeoPoint(37.001, -122.0), GeoPoint(37.002, -122.001)))
        val json = """
            {"trip":{"legs":[{"shape":"$shape","maneuvers":[
              {"type":1,"instruction":"Head north","length":0.2,"time":30,"begin_shape_index":0,"end_shape_index":1},
              {"type":15,"instruction":"Turn left onto Main St","length":0.1,"time":20,"begin_shape_index":1,"end_shape_index":2,"street_names":["Main St"]},
              {"type":4,"instruction":"Arrive","length":0.0,"time":0,"begin_shape_index":2,"end_shape_index":2}
            ]}]}}
        """.trimIndent()
        val routes = ValhallaResponseMapper.map(json, com.mapsdroid.core.TransportType.AUTOMOBILE)
        assertEquals(1, routes.size)
        assertEquals(3, routes[0].steps.size)
        assertEquals("Main St", routes[0].steps[1].roadName)
        assertEquals(300.0, routes[0].distanceMetres, 1.0)
    }
}
