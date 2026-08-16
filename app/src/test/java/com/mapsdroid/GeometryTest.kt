package com.mapsdroid

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.LocalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun haversine_knownDistance() {
        // ~1 degree of latitude is ~111.2 km.
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(1.0, 0.0)
        assertEquals(111_320.0, a.distanceTo(b), 500.0)
    }

    @Test
    fun bearing_dueEast_is90() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 1.0)
        assertEquals(90.0, a.bearingTo(b), 1.0)
    }

    @Test
    fun bearing_dueNorth_is0() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(1.0, 0.0)
        assertEquals(0.0, a.bearingTo(b), 1.0)
    }

    @Test
    fun projectOntoSegment_midpointSnapsOnLine() {
        // A point offset north of the midpoint of an east-west segment projects onto the segment.
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 0.01)
        val p = GeoPoint(0.0005, 0.005)
        val proj = LocalProjection.projectOntoSegment(p, a, b)
        assertEquals(0.5, proj.fraction, 0.05)
        assertTrue("perp distance should be > 0", proj.distanceMetres > 10.0)
        assertEquals(0.0, proj.point.latitude, 1e-4)
    }
}
