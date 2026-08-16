package com.mapsdroid.nav

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.ManeuverType
import com.mapsdroid.core.Route
import com.mapsdroid.core.RouteStep

/** High-level phase of a navigation session. */
enum class NavPhase { IDLE, ROUTING, GUIDING, REROUTING, ARRIVED }

/**
 * A single immutable snapshot of guidance, emitted on every location fix. The phone overlay, the
 * foreground notification, and the Android Auto templates all render from this one type.
 */
data class NavigationState(
    val phase: NavPhase = NavPhase.IDLE,
    val route: Route? = null,
    val snappedLocation: GeoPoint? = null,
    val rawLocation: GeoPoint? = null,
    val currentStepIndex: Int = 0,
    /** The maneuver the driver is approaching (start of the upcoming step), or null on the last leg. */
    val nextManeuver: RouteStep? = null,
    val distanceToNextManeuverMetres: Double = 0.0,
    val distanceRemainingMetres: Double = 0.0,
    val durationRemainingSeconds: Double = 0.0,
    val offRoute: Boolean = false,
) {
    val maneuverType: ManeuverType get() = nextManeuver?.maneuver ?: ManeuverType.CONTINUE

    /** Primary line for a turn card: what the driver should do next. */
    val primaryInstruction: String
        get() = when (phase) {
            NavPhase.ROUTING -> "Finding route…"
            NavPhase.REROUTING -> "Rerouting…"
            NavPhase.ARRIVED -> "You have arrived"
            else -> nextManeuver?.instruction ?: "Continue"
        }
}
