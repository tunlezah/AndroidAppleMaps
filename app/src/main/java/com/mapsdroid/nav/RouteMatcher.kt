package com.mapsdroid.nav

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.LocalProjection
import com.mapsdroid.core.Route

/**
 * Pre-indexes a [Route] for fast map-matching. Cumulative distances along the full polyline and the
 * distance at which each step's path ends are computed once, so each GPS fix costs one linear scan
 * of segments rather than repeated geometry over the whole route.
 */
class MatchableRoute(val route: Route) {

    private val points: List<GeoPoint> = route.polyline

    /** Cumulative metres from the route start to each polyline vertex. */
    private val cumulative: DoubleArray = DoubleArray(points.size).also { arr ->
        for (i in 1 until points.size) {
            arr[i] = arr[i - 1] + points[i - 1].distanceTo(points[i])
        }
    }

    val totalLengthMetres: Double = cumulative.lastOrNull() ?: 0.0

    /** Distance-along-route at the END of each step (i.e. where that step's maneuver segment finishes). */
    val stepEndDistances: DoubleArray = DoubleArray(route.steps.size).also { arr ->
        var acc = 0.0
        route.steps.forEachIndexed { i, step ->
            val segLen = step.path.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
            acc += segLen
            arr[i] = acc
        }
    }

    /** Snap [p] to the route. Scans all segments; routes are short enough that this is cheap at 1 Hz. */
    fun match(p: GeoPoint): Match {
        var best = Match(
            snapped = points.firstOrNull() ?: p,
            perpDistanceMetres = Double.MAX_VALUE,
            distanceAlongMetres = 0.0,
        )
        for (i in 0 until points.size - 1) {
            val proj = LocalProjection.projectOntoSegment(p, points[i], points[i + 1])
            if (proj.distanceMetres < best.perpDistanceMetres) {
                val along = cumulative[i] + proj.fraction * (cumulative[i + 1] - cumulative[i])
                best = Match(proj.point, proj.distanceMetres, along)
            }
        }
        return best
    }

    /** Index of the step whose path we are currently traversing, given distance-along-route. */
    fun stepIndexAt(distanceAlong: Double): Int {
        val idx = stepEndDistances.indexOfFirst { distanceAlong < it }
        return if (idx == -1) route.steps.lastIndex.coerceAtLeast(0) else idx
    }

    data class Match(
        val snapped: GeoPoint,
        val perpDistanceMetres: Double,
        val distanceAlongMetres: Double,
    )
}
