package com.mapsdroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.TransportType
import com.mapsdroid.data.AppPreferences
import com.mapsdroid.links.AppleMapsIntent
import com.mapsdroid.location.LocationRepository
import com.mapsdroid.location.LocationService
import com.mapsdroid.nav.NavHub
import com.mapsdroid.nav.NavPhase
import com.mapsdroid.nav.NavigationState
import com.mapsdroid.web.AppleMapsSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mapsdroid.offline.OfflineMapManager

/**
 * Adapts the shared [NavHub] for the phone Compose UI: exposes the guidance state, mediates the
 * WebView lifecycle, routes incoming Apple Maps links, and keeps the ongoing notification in sync.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val session: AppleMapsSession get() = NavHub.session
    val navState: StateFlow<NavigationState> get() = NavHub.state
    val tokenCaptured: StateFlow<String?> get() = NavHub.session.token

    private val _pendingIntent = MutableStateFlow<AppleMapsIntent?>(null)
    val pendingIntent: StateFlow<AppleMapsIntent?> = _pendingIntent

    private val _navTarget = MutableStateFlow<AppleMapsIntent.Directions?>(null)
    val navTarget: StateFlow<AppleMapsIntent.Directions?> = _navTarget

    val currentLocation = LocationRepository.location
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val preferences = AppPreferences(app)
    val eulaAccepted: StateFlow<Boolean?> = preferences.eulaAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val offlineManager: OfflineMapManager get() = NavHub.offlineManager

    /** True when the network is up; drives the switch between the Apple WebView and the offline map. */
    val isOnline: StateFlow<Boolean> get() = NavHub.connectivity.online

    fun acceptEula() {
        viewModelScope.launch { preferences.setEulaAccepted(true) }
    }

    init {
        // Mirror the current instruction into the foreground notification.
        viewModelScope.launch {
            var lastStatus: String? = null
            NavHub.state.collect { state ->
                if (state.phase == NavPhase.GUIDING || state.phase == NavPhase.REROUTING) {
                    val status = "${state.primaryInstruction} • ${formatEta(state.durationRemainingSeconds)}"
                    if (status != lastStatus) {
                        LocationService.start(getApplication(), status)
                        lastStatus = status
                    }
                }
            }
        }
    }

    val pageStatus: StateFlow<AppleMapsSession.PageStatus> get() = NavHub.session.pageStatus
    val diagnostics: StateFlow<List<String>> get() = NavHub.session.diagnostics

    /** Loads the map once; re-entering composition must not restart an already-loaded page. */
    fun onWebViewReady() {
        if (!session.hasLoaded) session.load()
    }

    fun reloadMap() = session.reload()

    fun clearDiagnostics() = session.clearDiagnostics()

    private val _trayHidden = MutableStateFlow(false)
    val trayHidden: StateFlow<Boolean> = _trayHidden

    /** Hides/shows the Apple page's bottom panel; its own drag handle is inert in a WebView. */
    fun toggleTray() {
        val hide = !_trayHidden.value
        _trayHidden.value = hide
        session.setTrayCollapsed(hide)
    }

    /** Diagnostic: replaces the Apple page with a trivial local page to test rendering. */
    fun runRenderTest() = session.loadRenderTest()

    fun setIntent(intent: AppleMapsIntent) {
        _pendingIntent.value = intent
        if (intent is AppleMapsIntent.Directions && intent.destination != null) {
            _navTarget.value = intent
        }
    }

    fun consumeIntent() { _pendingIntent.value = null }

    fun openLookAround(point: GeoPoint) = session.openLookAround(point)

    fun startNavigation(destination: GeoPoint, transport: TransportType) {
        NavHub.startNavigation(destination, transport)
    }

    fun stopNavigation() {
        NavHub.stopNavigation()
        _navTarget.value = null
    }

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    }
}
