package com.mapsdroid.offline

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType
import com.mapsdroid.nav.RouteProvider

/**
 * Offline route provider backed by Valhalla routing tiles on the device.
 *
 * Phase 6 scaffold. The plan (see docs/OFFLINE.md): bundle Valhalla's C++ core via the NDK, load the
 * region's routing tiles, call `valhalla::route`, and map its `trip.legs[].maneuvers[]` (which include
 * maneuver *types* and lane info — richer than Apple's text-only steps) into our [Route] model. Because
 * the [GuidanceEngine] consumes any [RouteProvider], turn-by-turn, off-route detection, and rerouting
 * work identically offline once this returns real routes.
 *
 * Until the native library is wired, this returns an empty list so the engine cleanly reports "no
 * offline route available" rather than crashing.
 */
class ValhallaRouter(
    private val regionProvider: () -> OfflineRegion?,
) : RouteProvider {

    override suspend fun route(from: GeoPoint, to: GeoPoint, transport: TransportType): List<Route> {
        val region = regionProvider() ?: return emptyList()
        if (!region.contains(from) || !region.contains(to) || region.valhallaTilesPath == null) {
            return emptyList()
        }
        // TODO(phase6): JNI call into libvalhalla; map maneuvers → Route. See docs/OFFLINE.md.
        return emptyList()
    }
}
