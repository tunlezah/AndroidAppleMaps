package com.mapsdroid.core

import kotlinx.serialization.Serializable

/**
 * Maneuver categories our guidance engine understands. Apple's Directions results give only
 * localized instruction *text* (e.g. "Turn right onto New Montgomery St"), not a maneuver enum,
 * so [ManeuverParser] infers the type from the text and the turn geometry.
 */
enum class ManeuverType {
    DEPART,
    CONTINUE,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    UTURN,
    KEEP_LEFT,
    KEEP_RIGHT,
    MERGE,
    ROUNDABOUT,
    FORK_LEFT,
    FORK_RIGHT,
    ARRIVE,
    UNKNOWN,
}

enum class TransportType { AUTOMOBILE, WALKING, CYCLING }

/** One guidance step: the maneuver at its start plus the path the user travels afterwards. */
@Serializable
data class RouteStep(
    val instruction: String,
    val maneuver: ManeuverType,
    val distanceMetres: Double,
    val durationSeconds: Double,
    /** Polyline for this step; its first vertex is the maneuver point. */
    val path: List<GeoPoint>,
    /** Road name being entered, when the parser can extract it. */
    val roadName: String? = null,
)

@Serializable
data class Route(
    val name: String,
    val distanceMetres: Double,
    val durationSeconds: Double,
    val transportType: TransportType,
    val hasTolls: Boolean,
    val steps: List<RouteStep>,
) {
    /** Full route geometry, formed by concatenating step paths (dropping shared vertices). */
    val polyline: List<GeoPoint> by lazy {
        buildList {
            steps.forEachIndexed { i, step ->
                val pts = if (i == 0) step.path else step.path.drop(1)
                addAll(pts)
            }
        }
    }
}
