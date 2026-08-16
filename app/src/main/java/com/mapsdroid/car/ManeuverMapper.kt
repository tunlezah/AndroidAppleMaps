package com.mapsdroid.car

import androidx.car.app.navigation.model.Maneuver
import com.mapsdroid.core.ManeuverType

/**
 * Maps our inferred [ManeuverType] to the Android for Cars [Maneuver] type constants. The host draws
 * its own standard iconography for these types, so we do not attach a custom icon. Roundabouts are
 * mapped to a plain turn because the enter-and-exit maneuver types require an exit number we do not
 * have from Apple's text-only instructions.
 */
object ManeuverMapper {

    fun toCarManeuver(type: ManeuverType): Maneuver {
        val carType = when (type) {
            ManeuverType.DEPART -> Maneuver.TYPE_DEPART
            ManeuverType.CONTINUE -> Maneuver.TYPE_STRAIGHT
            ManeuverType.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ManeuverType.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverType.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ManeuverType.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverType.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ManeuverType.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverType.UTURN -> Maneuver.TYPE_U_TURN_LEFT
            ManeuverType.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            ManeuverType.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            ManeuverType.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            ManeuverType.ROUNDABOUT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverType.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
            ManeuverType.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
            ManeuverType.ARRIVE -> Maneuver.TYPE_DESTINATION
            ManeuverType.UNKNOWN -> Maneuver.TYPE_STRAIGHT
        }
        return Maneuver.Builder(carType).build()
    }
}
