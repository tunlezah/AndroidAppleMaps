package com.mapsdroid.directions

import com.mapsdroid.core.Route
import com.mapsdroid.core.RouteStep
import com.mapsdroid.core.TransportType

/** Converts the bridge's [DirectionsResultDto] into the domain [Route] model. */
object RouteMapper {

    fun map(dto: DirectionsResultDto): List<Route> = dto.routes.map { r ->
        val stepPaths = r.steps.map { it.path }
        val steps = r.steps.mapIndexed { i, s ->
            val incoming = stepPaths.getOrNull(i - 1)?.let { ManeuverParser.headingOf(it, atStart = false) }
            val outgoing = ManeuverParser.headingOf(s.path, atStart = true)
            val maneuver = ManeuverParser.parse(
                instruction = s.instructions,
                incomingHeading = incoming,
                outgoingHeading = outgoing,
                isFirst = i == 0,
                isLast = i == r.steps.lastIndex,
            )
            RouteStep(
                instruction = s.instructions,
                maneuver = maneuver,
                distanceMetres = s.distance,
                durationSeconds = s.duration,
                path = s.path,
                roadName = ManeuverParser.roadName(s.instructions),
            )
        }
        Route(
            name = r.name,
            distanceMetres = r.distance,
            durationSeconds = r.expectedTravelTime,
            transportType = when (r.transportType.lowercase()) {
                "walking" -> TransportType.WALKING
                "cycling" -> TransportType.CYCLING
                else -> TransportType.AUTOMOBILE
            },
            hasTolls = r.hasTolls,
            steps = steps,
        )
    }
}
