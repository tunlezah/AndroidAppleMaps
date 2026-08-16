package com.mapsdroid.location

import android.location.Location
import com.mapsdroid.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide sink for location fixes. The foreground [LocationService] is the single writer;
 * the phone guidance engine, the Compose UI, and the Android Auto session all read the same flow,
 * so the car and phone stay perfectly in sync off one GPS stream.
 */
object LocationRepository {

    private val _location = MutableStateFlow<AppLocation?>(null)
    val location: StateFlow<AppLocation?> = _location.asStateFlow()

    fun publish(location: Location) {
        _location.value = AppLocation(
            point = GeoPoint(location.latitude, location.longitude),
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            accuracyMetres = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        )
    }

    fun publish(location: AppLocation) {
        _location.value = location
    }
}
