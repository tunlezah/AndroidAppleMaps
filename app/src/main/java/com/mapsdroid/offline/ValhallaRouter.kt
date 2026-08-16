package com.mapsdroid.offline

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType
import com.mapsdroid.nav.RouteProvider

/**
 * Offline route provider backed by Valhalla routing tiles on the device.
 *
 * The routing graph itself is native (Valhalla is C++), so the actual `route()` call is a JNI hop
 * into `libvalhalla` — the one piece of the offline stack that needs the NDK build (see
 * `native/valhalla/` and `docs/OFFLINE.md`). Everything around it is real: region lookup, transport
 * mapping, and the mapping of Valhalla maneuvers (which include maneuver *types* and lane data,
 * richer than Apple's text-only steps) into our [Route] model via [ValhallaResponseMapper].
 *
 * Because the [com.mapsdroid.nav.GuidanceEngine] consumes any [RouteProvider], turn-by-turn,
 * off-route detection, and rerouting work identically offline once the native library returns routes.
 */
class ValhallaRouter(
    private val offline: OfflineMapManager,
    private val native: ValhallaNative = ValhallaNative(),
) : RouteProvider {

    override suspend fun route(from: GeoPoint, to: GeoPoint, transport: TransportType): List<Route> {
        val region = offline.regionContaining(from)?.takeIf { it.contains(to) } ?: return emptyList()
        val tiles = region.valhallaTilesPath ?: return emptyList()
        if (!native.isAvailable) return emptyList()
        val json = native.route(tiles, from.latitude, from.longitude, to.latitude, to.longitude, transport.name)
            ?: return emptyList()
        return ValhallaResponseMapper.map(json, transport)
    }
}
