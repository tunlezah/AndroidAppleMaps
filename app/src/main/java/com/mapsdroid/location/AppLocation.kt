package com.mapsdroid.location

import com.mapsdroid.core.GeoPoint

/** A single location fix, normalized from Android's [android.location.Location]. */
data class AppLocation(
    val point: GeoPoint,
    val bearingDegrees: Float?,
    val speedMps: Float?,
    val accuracyMetres: Float?,
    val elapsedRealtimeNanos: Long,
)
