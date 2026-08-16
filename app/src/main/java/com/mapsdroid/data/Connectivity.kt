package com.mapsdroid.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes validated internet connectivity. Drives the switch between the online Apple map/routing
 * and the offline open-data stack.
 */
class Connectivity(context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = currentlyOnline() }
        override fun onLost(network: Network) { _online.value = currentlyOnline() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasInternet()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasInternet()
    }

    // Require only INTERNET, not VALIDATED: VALIDATED can lag for seconds after a network appears,
    // and treating that gap as "offline" would wrongly show the (blank, region-less) offline map at
    // startup. We would rather attempt the Apple web map and let it report a load error.
    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
