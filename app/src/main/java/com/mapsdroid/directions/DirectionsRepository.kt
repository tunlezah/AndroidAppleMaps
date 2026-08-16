package com.mapsdroid.directions

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType
import com.mapsdroid.nav.RouteProvider
import com.mapsdroid.web.AppleMapsSession

/**
 * [RouteProvider] backed by mapkit.Directions running inside the Apple page (via [AppleMapsSession]).
 * This is the online route source; Phase 6 adds a Valhalla-backed provider for offline, selected by
 * connectivity at the call site.
 */
class DirectionsRepository(private val session: AppleMapsSession) : RouteProvider {
    override suspend fun route(from: GeoPoint, to: GeoPoint, transport: TransportType): List<Route> =
        session.route(from, to, transport)
}
