package com.mapsdroid.offline

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.ManeuverType
import com.mapsdroid.core.Route
import com.mapsdroid.core.RouteStep
import com.mapsdroid.core.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps a Valhalla `/route` JSON response into our [Route] model: decodes the leg shape (Google
 * polyline, precision 6), slices it per maneuver, and maps Valhalla maneuver types to [ManeuverType].
 */
object ValhallaResponseMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun map(responseJson: String, transport: TransportType): List<Route> {
        val root = runCatching { json.parseToJsonElement(responseJson).jsonObject }.getOrNull() ?: return emptyList()
        val trip = root["trip"]?.jsonObject ?: return emptyList()
        val legs = trip["legs"]?.jsonArray ?: return emptyList()

        val allSteps = mutableListOf<RouteStep>()
        var totalDistanceKm = 0.0
        var totalTimeS = 0.0

        for (legEl in legs) {
            val leg = legEl.jsonObject
            val shape = leg["shape"]?.jsonPrimitive?.content ?: continue
            val points = decodePolyline6(shape)
            val maneuvers = leg["maneuvers"]?.jsonArray ?: continue
            for (mEl in maneuvers) {
                val m = mEl.jsonObject
                val begin = m["begin_shape_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val end = m["end_shape_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: begin
                val lengthKm = m["length"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val timeS = m["time"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val instruction = m["instruction"]?.jsonPrimitive?.content ?: ""
                val typeInt = m["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val roadName = (m["street_names"]?.jsonArray?.firstOrNull())?.jsonPrimitive?.content
                val path = points.subList(begin.coerceIn(0, points.size), (end + 1).coerceIn(0, points.size)).toList()
                allSteps += RouteStep(
                    instruction = instruction,
                    maneuver = mapType(typeInt),
                    distanceMetres = lengthKm * 1000.0,
                    durationSeconds = timeS,
                    path = path.ifEmpty { points.takeLast(1) },
                    roadName = roadName,
                )
                totalDistanceKm += lengthKm
                totalTimeS += timeS
            }
        }
        if (allSteps.isEmpty()) return emptyList()
        return listOf(
            Route(
                name = "Offline route",
                distanceMetres = totalDistanceKm * 1000.0,
                durationSeconds = totalTimeS,
                transportType = transport,
                hasTolls = false,
                steps = allSteps,
            ),
        )
    }

    /** Valhalla maneuver type enum → our [ManeuverType]. */
    private fun mapType(type: Int): ManeuverType = when (type) {
        1, 2, 3 -> ManeuverType.DEPART
        4, 5, 6 -> ManeuverType.ARRIVE
        8 -> ManeuverType.CONTINUE
        9 -> ManeuverType.SLIGHT_RIGHT
        10 -> ManeuverType.TURN_RIGHT
        11 -> ManeuverType.SHARP_RIGHT
        12 -> ManeuverType.UTURN
        13 -> ManeuverType.UTURN
        14 -> ManeuverType.SHARP_LEFT
        15 -> ManeuverType.TURN_LEFT
        16 -> ManeuverType.SLIGHT_LEFT
        18 -> ManeuverType.FORK_RIGHT
        19 -> ManeuverType.FORK_LEFT
        20 -> ManeuverType.FORK_RIGHT
        21 -> ManeuverType.FORK_LEFT
        23 -> ManeuverType.KEEP_RIGHT
        24 -> ManeuverType.KEEP_LEFT
        25 -> ManeuverType.MERGE
        26, 27 -> ManeuverType.ROUNDABOUT
        else -> ManeuverType.CONTINUE
    }

    /** Decodes a Google-encoded polyline at precision 6 (Valhalla's default) into coordinates. */
    fun decodePolyline6(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points += GeoPoint(lat / factor, lng / factor)
        }
        return points
    }
}
