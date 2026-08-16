package com.mapsdroid.nav

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType
import com.mapsdroid.location.AppLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The client-side turn-by-turn engine. Apple (and, in fact, no third-party API even on iOS) exposes
 * a live guidance *session* — voice prompts, off-route detection, rerouting. This class is that
 * engine, built on top of the route geometry Apple's Directions returns:
 *
 *  - snaps each fix to the route ([MatchableRoute]),
 *  - tracks distance to the next maneuver and announces at speed-scaled distance bands,
 *  - detects arrival and off-route, and asks the [RouteProvider] for a fresh route on divergence.
 *
 * It holds no Android dependencies beyond the [Announcer] it drives, so it is unit-testable and is
 * reused verbatim by both the phone overlay and the Android Auto session.
 */
class GuidanceEngine(
    private val scope: CoroutineScope,
    private val routeProvider: RouteProvider,
    private val announcer: Announcer? = null,
) {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var matchable: MatchableRoute? = null
    private var destination: GeoPoint? = null
    private var transport: TransportType = TransportType.AUTOMOBILE
    private val spokenBands = mutableSetOf<String>()
    private var offRouteStreak = 0
    private var rerouting = false

    fun start(route: Route, destination: GeoPoint, transport: TransportType) {
        this.matchable = MatchableRoute(route)
        this.destination = destination
        this.transport = transport
        spokenBands.clear()
        offRouteStreak = 0
        rerouting = false
        _state.value = NavigationState(
            phase = NavPhase.GUIDING,
            route = route,
            nextManeuver = route.steps.getOrNull(1) ?: route.steps.firstOrNull(),
            distanceRemainingMetres = route.distanceMetres,
            durationRemainingSeconds = route.durationSeconds,
        )
        // Speak the first instruction immediately.
        route.steps.firstOrNull()?.let { announcer?.speak(it.instruction) }
    }

    fun stop() {
        matchable = null
        destination = null
        _state.value = NavigationState(phase = NavPhase.IDLE)
    }

    fun onLocation(location: AppLocation) {
        val mr = matchable ?: return
        if (_state.value.phase == NavPhase.ARRIVED) return

        val match = mr.match(location.point)
        val distanceRemaining = (mr.totalLengthMetres - match.distanceAlongMetres).coerceAtLeast(0.0)
        val stepIndex = mr.stepIndexAt(match.distanceAlongMetres)
        val distanceToManeuver = (mr.stepEndDistances.getOrElse(stepIndex) { mr.totalLengthMetres } - match.distanceAlongMetres)
            .coerceAtLeast(0.0)

        // Arrival: within the last few metres of the final step.
        if (distanceRemaining < ARRIVAL_RADIUS_M) {
            announceOnce("arrive") { mr.route.steps.lastOrNull()?.instruction ?: "You have arrived" }
            _state.value = _state.value.copy(
                phase = NavPhase.ARRIVED,
                snappedLocation = match.snapped,
                rawLocation = location.point,
                distanceRemainingMetres = 0.0,
                durationRemainingSeconds = 0.0,
            )
            return
        }

        // Off-route detection: sustained perpendicular divergence triggers a reroute.
        val offRoute = match.perpDistanceMetres > offRouteThreshold(location)
        if (offRoute) offRouteStreak++ else offRouteStreak = 0
        if (offRouteStreak >= OFF_ROUTE_FIXES && !rerouting) {
            triggerReroute(location.point)
        }

        val nextManeuverStep = mr.route.steps.getOrNull(stepIndex + 1) ?: mr.route.steps.lastOrNull()
        val durationRemaining = estimateDurationRemaining(mr, stepIndex, distanceToManeuver, distanceRemaining)

        maybeAnnounce(stepIndex, nextManeuverStep?.instruction, distanceToManeuver, location.speedMps)

        _state.value = _state.value.copy(
            phase = if (rerouting) NavPhase.REROUTING else NavPhase.GUIDING,
            snappedLocation = match.snapped,
            rawLocation = location.point,
            currentStepIndex = stepIndex,
            nextManeuver = nextManeuverStep,
            distanceToNextManeuverMetres = distanceToManeuver,
            distanceRemainingMetres = distanceRemaining,
            durationRemainingSeconds = durationRemaining,
            offRoute = offRoute,
        )
    }

    private fun triggerReroute(from: GeoPoint) {
        val dest = destination ?: return
        rerouting = true
        _state.value = _state.value.copy(phase = NavPhase.REROUTING)
        announceOnce("reroute-${System.identityHashCode(from)}") { "Rerouting" }
        scope.launch {
            val routes = runCatching { routeProvider.route(from, dest, transport) }.getOrDefault(emptyList())
            val fresh = routes.firstOrNull()
            if (fresh != null) {
                start(fresh, dest, transport)
            } else {
                // Keep guiding on the stale route; a subsequent fix retries. This is the graceful
                // degradation the research called for when the directions call is throttled (HTTP 429).
                rerouting = false
            }
        }
    }

    /**
     * Announce at descending distance bands, once each per step. Bands scale with the transport mode
     * so walking gets close-in prompts and driving gets early warning.
     */
    private fun maybeAnnounce(stepIndex: Int, instruction: String?, distanceToManeuver: Double, speedMps: Float?) {
        if (instruction.isNullOrBlank()) return
        for (band in bands()) {
            if (distanceToManeuver <= band && distanceToManeuver > band - BAND_WINDOW_M) {
                announceOnce("$stepIndex@$band") {
                    if (band <= NOW_BAND_M) instruction else "In ${formatDistance(band)}, $instruction"
                }
            }
        }
    }

    private fun bands(): List<Double> = when (transport) {
        TransportType.WALKING -> listOf(60.0, NOW_BAND_M)
        TransportType.CYCLING -> listOf(150.0, 50.0, NOW_BAND_M)
        TransportType.AUTOMOBILE -> listOf(800.0, 200.0, NOW_BAND_M)
    }

    private fun offRouteThreshold(location: AppLocation): Double {
        // Be lenient when GPS is poor so we do not reroute on noise.
        val accuracy = location.accuracyMetres ?: 15f
        return (OFF_ROUTE_BASE_M + accuracy).coerceAtMost(80.0)
    }

    private fun estimateDurationRemaining(
        mr: MatchableRoute,
        stepIndex: Int,
        distanceToManeuver: Double,
        distanceRemaining: Double,
    ): Double {
        val remainingSteps = mr.route.steps.drop(stepIndex + 1)
        val laterDuration = remainingSteps.sumOf { it.durationSeconds }
        val currentStep = mr.route.steps.getOrNull(stepIndex)
        val currentStepLen = currentStep?.path?.zipWithNext()?.sumOf { (a, b) -> a.distanceTo(b) } ?: 0.0
        val currentFractionRemaining = if (currentStepLen > 0) (distanceToManeuver / currentStepLen).coerceIn(0.0, 1.0) else 0.0
        val currentRemaining = (currentStep?.durationSeconds ?: 0.0) * currentFractionRemaining
        val estimate = laterDuration + currentRemaining
        // Fall back to a speed-based estimate if step durations are missing.
        return if (estimate > 0) estimate else distanceRemaining / AVERAGE_SPEED_MPS
    }

    private inline fun announceOnce(key: String, text: () -> String) {
        if (spokenBands.add(key)) announcer?.speak(text())
    }

    private fun formatDistance(metres: Double): String = when {
        metres >= 1000 -> "${(metres / 100).roundToInt() / 10.0} kilometers"
        else -> "${metres.roundToInt()} meters"
    }

    companion object {
        private const val ARRIVAL_RADIUS_M = 15.0
        private const val OFF_ROUTE_BASE_M = 25.0
        private const val OFF_ROUTE_FIXES = 3
        private const val NOW_BAND_M = 30.0
        private const val BAND_WINDOW_M = 60.0
        private const val AVERAGE_SPEED_MPS = 11.0 // ~40 km/h fallback
    }
}
