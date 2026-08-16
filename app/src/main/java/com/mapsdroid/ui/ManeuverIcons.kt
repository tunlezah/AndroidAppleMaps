package com.mapsdroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.ui.graphics.vector.ImageVector
import com.mapsdroid.core.ManeuverType

/** Maps an inferred maneuver to a Material icon for the turn card. */
fun ManeuverType.icon(): ImageVector = when (this) {
    ManeuverType.DEPART -> Icons.Filled.Navigation
    ManeuverType.CONTINUE -> Icons.Filled.Straight
    ManeuverType.TURN_LEFT -> Icons.Filled.TurnLeft
    ManeuverType.TURN_RIGHT -> Icons.Filled.TurnRight
    ManeuverType.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverType.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    ManeuverType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    ManeuverType.UTURN -> Icons.Filled.UTurnLeft
    ManeuverType.KEEP_LEFT -> Icons.Filled.ForkLeft
    ManeuverType.KEEP_RIGHT -> Icons.Filled.ForkRight
    ManeuverType.MERGE -> Icons.AutoMirrored.Filled.MergeType
    ManeuverType.ROUNDABOUT -> Icons.Filled.RoundaboutLeft
    ManeuverType.FORK_LEFT -> Icons.Filled.ForkLeft
    ManeuverType.FORK_RIGHT -> Icons.Filled.ForkRight
    ManeuverType.ARRIVE -> Icons.Filled.Flag
    ManeuverType.UNKNOWN -> Icons.Filled.Straight
}

/** Human-friendly distance for the turn card, in metric. */
fun formatDistanceMetric(metres: Double): String = when {
    metres >= 1000 -> "%.1f km".format(metres / 1000.0)
    metres >= 10 -> "${(metres / 10).toInt() * 10} m"
    else -> "${metres.toInt()} m"
}
