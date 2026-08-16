package com.mapsdroid.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType
import com.mapsdroid.nav.RouteProvider

/**
 * Picks the route source by connectivity: Apple's online directions when the network is up, the
 * on-device [ValhallaRouter] when it is not. The [GuidanceEngine] is unaware of the switch, so a
 * commute that loses signal keeps navigating on offline data and returns to Apple routing when it
 * comes back.
 */
class ConnectivityRouteProvider(
    private val context: Context,
    private val online: RouteProvider,
    private val offline: RouteProvider,
) : RouteProvider {

    override suspend fun route(from: GeoPoint, to: GeoPoint, transport: TransportType): List<Route> {
        return if (isOnline()) {
            online.route(from, to, transport).ifEmpty { offline.route(from, to, transport) }
        } else {
            offline.route(from, to, transport)
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
