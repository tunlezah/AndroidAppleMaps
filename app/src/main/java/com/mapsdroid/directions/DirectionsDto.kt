package com.mapsdroid.directions

import com.mapsdroid.core.GeoPoint
import kotlinx.serialization.Serializable

/**
 * JSON contract between the injected `mapkit.Directions` bridge script and native code.
 *
 * We control the script that produces this (see assets/web/bridge.js), so the shape is chosen to
 * map cleanly onto these DTOs rather than mirroring mapkit.js's own object graph verbatim.
 */
@Serializable
data class DirectionsResultDto(
    val routes: List<RouteDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class RouteDto(
    val name: String = "",
    val distance: Double = 0.0,
    val expectedTravelTime: Double = 0.0,
    val transportType: String = "Automobile",
    val hasTolls: Boolean = false,
    val steps: List<StepDto> = emptyList(),
)

@Serializable
data class StepDto(
    val instructions: String = "",
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val path: List<GeoPoint> = emptyList(),
)
