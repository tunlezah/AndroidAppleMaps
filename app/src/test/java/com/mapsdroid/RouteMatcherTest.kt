package com.mapsdroid

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.ManeuverType
import com.mapsdroid.core.Route
import com.mapsdroid.core.RouteStep
import com.mapsdroid.core.TransportType
import com.mapsdroid.nav.MatchableRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMatcherTest {

    /** An L-shaped route: 1 km east, then 1 km north, split into two steps. */
    private fun lShapedRoute(): Route {
        val leg1 = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.008993)) // ~1 km east at equator
        val leg2 = listOf(GeoPoint(0.0, 0.008993), GeoPoint(0.008993, 0.008993)) // ~1 km north
        return Route(
            name = "Test",
            distanceMetres = 2000.0,
            durationSeconds = 240.0,
            transportType = TransportType.AUTOMOBILE,
            hasTolls = false,
            steps = listOf(
                RouteStep("Head east", ManeuverType.DEPART, 1000.0, 120.0, leg1),
                RouteStep("Turn left onto North St", ManeuverType.TURN_LEFT, 1000.0, 120.0, leg2, "North St"),
            ),
        )
    }

    @Test
    fun totalLength_isSumOfLegs() {
        val mr = MatchableRoute(lShapedRoute())
        assertEquals(2000.0, mr.totalLengthMetres, 30.0)
    }

    @Test
    fun match_snapsNearbyPointOntoRoute() {
        val mr = MatchableRoute(lShapedRoute())
        // A point slightly north of the first (east-west) leg, ~halfway along.
        val off = GeoPoint(0.0002, 0.0045)
        val match = mr.match(off)
        assertTrue("should snap close to route", match.perpDistanceMetres < 40.0)
        assertEquals("halfway along first leg", 500.0, match.distanceAlongMetres, 80.0)
    }

    @Test
    fun stepIndex_advancesPastFirstLeg() {
        val mr = MatchableRoute(lShapedRoute())
        assertEquals(0, mr.stepIndexAt(200.0))
        assertEquals(1, mr.stepIndexAt(1200.0))
    }
}
