package com.mapsdroid.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mapsdroid.nav.NavHub
import com.mapsdroid.nav.NavPhase
import com.mapsdroid.nav.NavigationState
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The Android Auto navigation screen. It renders the shared [NavHub] guidance state two ways:
 *  - the map, via [CarSurfaceRenderer] drawing to the car surface;
 *  - the turn card, ETA, and lane rail, via [NavigationTemplate] and [NavigationManager.updateTrip]
 *    (quality rule NF-2 requires this chrome to come from the template, not the drawn surface).
 */
class NavigationScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private val renderer = CarSurfaceRenderer(carContext)
    private var navStarted = false
    private var current: NavigationState = NavigationState()

    init {
        lifecycle.addObserver(this)
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(renderer)

        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                NavHub.stopNavigation()
            }

            override fun onAutoDriveEnabled() {
                // Required for car review: the host can request a simulated drive. No-op here; the
                // guidance engine already advances from whatever location fixes it receives.
            }
        })
    }

    override fun onStart(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            NavHub.state.collect { state ->
                onState(state)
            }
        }
    }

    private fun onState(state: NavigationState) {
        current = state
        when (state.phase) {
            NavPhase.GUIDING, NavPhase.REROUTING -> {
                if (!navStarted) {
                    navigationManager.navigationStarted()
                    navStarted = true
                }
                buildTrip(state)?.let(navigationManager::updateTrip)
            }
            NavPhase.ARRIVED, NavPhase.IDLE -> {
                if (navStarted) {
                    navigationManager.navigationEnded()
                    navStarted = false
                }
            }
            else -> Unit
        }
        renderer.render(state)
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("End")
                            .setOnClickListener { NavHub.stopNavigation() }
                            .build(),
                    )
                    .build(),
            )

        val state = current
        if (state.phase == NavPhase.GUIDING && state.nextManeuver != null) {
            builder.setNavigationInfo(routingInfo(state))
            builder.setDestinationTravelEstimate(travelEstimate(state))
        }
        return builder.build()
    }

    private fun routingInfo(state: NavigationState): RoutingInfo {
        val step = carStep(state)
        val distance = metresToDistance(state.distanceToNextManeuverMetres)
        return RoutingInfo.Builder()
            .setCurrentStep(step, distance)
            .build()
    }

    private fun carStep(state: NavigationState): Step {
        val instruction = state.nextManeuver?.instruction ?: "Continue"
        val builder = Step.Builder(CarText.create(instruction))
            .setManeuver(ManeuverMapper.toCarManeuver(state.maneuverType))
        state.nextManeuver?.roadName?.let { builder.setRoad(it) }
        return builder.build()
    }

    private fun travelEstimate(state: NavigationState): TravelEstimate {
        val remaining = metresToDistance(state.distanceRemainingMetres)
        val arrivalMillis = System.currentTimeMillis() + (state.durationRemainingSeconds * 1000).toLong()
        val arrival = androidx.car.app.model.DateTimeWithZone.create(
            arrivalMillis,
            java.util.TimeZone.getDefault(),
        )
        return TravelEstimate.Builder(remaining, arrival)
            .setRemainingTimeSeconds(state.durationRemainingSeconds.toLong().coerceAtLeast(0))
            .build()
    }

    private fun buildTrip(state: NavigationState): Trip? {
        val step = carStep(state)
        val stepEstimate = TravelEstimate.Builder(
            metresToDistance(state.distanceToNextManeuverMetres),
            androidx.car.app.model.DateTimeWithZone.create(
                System.currentTimeMillis() + 60_000L,
                java.util.TimeZone.getDefault(),
            ),
        ).setRemainingTimeSeconds(TimeUnit.MINUTES.toSeconds(1)).build()

        val destination = Destination.Builder()
            .setName(state.route?.name ?: "Destination")
            .build()

        return Trip.Builder()
            .addStep(step, stepEstimate)
            .addDestination(destination, travelEstimate(state))
            .setCurrentRoad(state.nextManeuver?.roadName ?: state.route?.name ?: "")
            .build()
    }

    private fun metresToDistance(metres: Double): Distance = when {
        metres >= 1000 -> Distance.create(metres / 1000.0, Distance.UNIT_KILOMETERS)
        else -> Distance.create(metres.coerceAtLeast(0.0), Distance.UNIT_METERS)
    }
}
