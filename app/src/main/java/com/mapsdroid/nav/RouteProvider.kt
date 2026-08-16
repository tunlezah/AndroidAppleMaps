package com.mapsdroid.nav

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType

/**
 * Source of routes for the guidance engine. Online this is backed by `mapkit.Directions` run inside
 * the Apple map's own origin (see DirectionsRepository); offline it is backed by the on-device
 * Valhalla engine (Phase 6). The engine itself is agnostic to which is in use, so rerouting works
 * identically on either data source.
 */
fun interface RouteProvider {
    suspend fun route(from: GeoPoint, to: GeoPoint, transport: TransportType): List<Route>
}
