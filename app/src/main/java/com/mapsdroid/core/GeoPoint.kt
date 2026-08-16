package com.mapsdroid.core

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate. Matches the `{latitude, longitude}` shape returned by mapkit.js. */
@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
    }

    /** Great-circle distance to [other] in metres (haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing to [other] in degrees, 0..360 clockwise from north. */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

/**
 * Planar helpers used by the route matcher. Over the short spans between GPS fixes and route
 * vertices, an equirectangular projection around a local latitude is accurate to well within the
 * GPS error we care about, and it keeps the snap-to-route math cheap enough to run at 1 Hz.
 */
object LocalProjection {
    private const val METRES_PER_DEGREE_LAT = 111_320.0

    fun metresPerDegreeLon(latitude: Double): Double =
        METRES_PER_DEGREE_LAT * cos(Math.toRadians(latitude))

    /** Metres east/north of [origin]. */
    fun toLocalMetres(point: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val east = (point.longitude - origin.longitude) * metresPerDegreeLon(origin.latitude)
        val north = (point.latitude - origin.latitude) * METRES_PER_DEGREE_LAT
        return east to north
    }

    /**
     * Projects [p] onto the segment [a]-[b]. Returns the projected point, the perpendicular
     * distance in metres, and the fraction (0..1) of the segment covered.
     */
    fun projectOntoSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val (ax, ay) = toLocalMetres(a, a)
        val (bx, by) = toLocalMetres(b, a)
        val (px, py) = toLocalMetres(p, a)
        val abx = bx - ax
        val aby = by - ay
        val segLenSq = abx * abx + aby * aby
        val t = if (segLenSq == 0.0) 0.0 else (((px - ax) * abx + (py - ay) * aby) / segLenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * abx
        val projY = ay + t * aby
        val dist = hypot(px - projX, py - projY)
        val mPerLon = metresPerDegreeLon(a.latitude)
        val projected = GeoPoint(
            latitude = a.latitude + projY / METRES_PER_DEGREE_LAT,
            longitude = a.longitude + projX / mPerLon,
        )
        return Projection(projected, dist, t)
    }

    data class Projection(val point: GeoPoint, val distanceMetres: Double, val fraction: Double)
}
