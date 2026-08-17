package com.mapsdroid.nav

import android.content.Context
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.TransportType
import com.mapsdroid.data.Connectivity
import com.mapsdroid.directions.DirectionsRepository
import com.mapsdroid.location.LocationRepository
import com.mapsdroid.location.LocationService
import com.mapsdroid.offline.ConnectivityRouteProvider
import com.mapsdroid.offline.OfflineMapManager
import com.mapsdroid.offline.ValhallaRouter
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
 * service so both render from one [GuidanceEngine] and one location stream. The route provider is
 * connectivity-aware: Apple's online directions when the network is up, on-device Valhalla when it is
 * not. Initialized once from [com.mapsdroid.MapsApp].
 */
object NavHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var appContext: Context

    lateinit var session: AppleMapsSession
        private set
    lateinit var engine: GuidanceEngine
        private set
    lateinit var connectivity: Connectivity
        private set
    lateinit var offlineManager: OfflineMapManager
        private set

    private lateinit var routeProvider: ConnectivityRouteProvider
    private var announcer: Announcer? = null

    /** True once [init] has fully completed. Guards against a partially-initialized hub. */
    @Volatile
    var isReady: Boolean = false
        private set

    val state: StateFlow<NavigationState> get() = engine.state

    fun init(context: Context) {
        if (isReady) return
        appContext = context.applicationContext
        session = AppleMapsSession(appContext) { android.util.Log.d("MapsDroid", it) }
        offlineManager = OfflineMapManager(appContext)

        // Anything that touches system services or the TTS engine is best-effort: a failure here
        // must not leave the hub half-built, because the UI reads engine/connectivity on first frame
        // and an UninitializedPropertyAccessException would blank the screen.
        connectivity = Connectivity(appContext)
        announcer = runCatching { Announcer(appContext) }
            .onFailure { android.util.Log.w("MapsDroid", "TTS unavailable", it) }
            .getOrNull()

        val online = DirectionsRepository(session)
        val offline = ValhallaRouter(offlineManager)
        routeProvider = ConnectivityRouteProvider(appContext, online, offline)
        engine = GuidanceEngine(scope, routeProvider, announcer)
        isReady = true

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
            val route = routeProvider.route(start, destination, transport).firstOrNull() ?: return@launch
            engine.start(route, destination, transport)
        }
    }

    fun stopNavigation() {
        engine.stop()
        LocationService.stop(appContext)
    }

    private const val LOCATION_WAIT_MS = 8_000L
}
