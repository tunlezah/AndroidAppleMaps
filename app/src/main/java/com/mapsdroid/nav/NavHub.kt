package com.mapsdroid.nav

import android.content.Context
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.TransportType
import com.mapsdroid.directions.DirectionsRepository
import com.mapsdroid.location.LocationRepository
import com.mapsdroid.location.LocationService
import com.mapsdroid.web.AppleMapsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide owner of the single guidance session, shared by the phone UI and the Android Auto
 * service so both render from one [GuidanceEngine] and one location stream. Initialized once from
 * [com.mapsdroid.MapsApp].
 */
object NavHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var appContext: Context

    lateinit var session: AppleMapsSession
        private set
    lateinit var engine: GuidanceEngine
        private set

    private lateinit var directions: DirectionsRepository
    private var announcer: Announcer? = null

    val state: StateFlow<NavigationState> get() = engine.state

    fun init(context: Context) {
        if (::session.isInitialized) return
        appContext = context.applicationContext
        session = AppleMapsSession(appContext)
        directions = DirectionsRepository(session)
        announcer = Announcer(appContext)
        engine = GuidanceEngine(scope, directions, announcer)

        // One location stream drives the engine whenever a route is active.
        scope.launch {
            LocationRepository.location.filterNotNull().collect { loc ->
                if (state.value.phase != NavPhase.IDLE) engine.onLocation(loc)
            }
        }
    }

    fun startNavigation(destination: GeoPoint, transport: TransportType) {
        LocationService.start(appContext)
        scope.launch {
            val start = withTimeoutOrNull(LOCATION_WAIT_MS) {
                LocationRepository.location.filterNotNull().first().point
            } ?: return@launch
            val route = directions.route(start, destination, transport).firstOrNull() ?: return@launch
            engine.start(route, destination, transport)
        }
    }

    fun stopNavigation() {
        engine.stop()
        LocationService.stop(appContext)
    }

    private const val LOCATION_WAIT_MS = 8_000L
}
