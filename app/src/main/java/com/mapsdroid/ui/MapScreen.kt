package com.mapsdroid.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mapsdroid.links.AppleMapsIntent
import com.mapsdroid.nav.NavPhase
import com.mapsdroid.offline.OfflineMapView

/**
 * The phone experience: the Apple Maps consumer site full-screen (search, place cards, Look Around,
 * and map all provided by Apple), with our native turn-by-turn overlay on top during guidance.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(viewModel: MainViewModel) {
    val navState by viewModel.navState.collectAsState()
    val pending by viewModel.pendingIntent.collectAsState()
    val navTarget by viewModel.navTarget.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val regions by viewModel.offlineManager.regions.collectAsState()
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Only use the offline map when we are actually offline AND have a downloaded region to render;
    // otherwise the offline style is a blank background. With no regions downloaded (the default),
    // this always shows the Apple WebView.
    val region = location?.point?.let { p -> regions.firstOrNull { it.contains(p) } }
        ?: regions.firstOrNull()
    val showOffline = !isOnline && region != null

    Box(modifier = Modifier.fillMaxSize()) {
        if (showOffline) {
            OfflineMapView(
                region = region,
                navState = navState,
                location = location,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Online (or offline with no region): the real Apple map via the consumer site.
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).also { web ->
                        viewModel.session.attach(web)
                        webViewRef.value = web
                        viewModel.onWebViewReady()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        BackHandler(enabled = navState.phase == NavPhase.IDLE) {
            webViewRef.value?.takeIf { it.canGoBack() }?.goBack()
        }

        // Deep link handling runs as an effect (not during composition), then clears the intent.
        LaunchedEffect(pending, webViewRef.value) {
            val intent = pending ?: return@LaunchedEffect
            val web = webViewRef.value ?: return@LaunchedEffect
            deepLinkUrl(intent)?.let(web::loadUrl)
            viewModel.consumeIntent()
        }

        // Native guidance affordance when a destination coordinate is known and we are idle.
        val target = navTarget
        if (navState.phase == NavPhase.IDLE && target?.destination != null) {
            ExtendedFloatingActionButton(
                onClick = { viewModel.startNavigation(target.destination, target.mode) },
                icon = { Icon(Icons.Filled.Navigation, contentDescription = null) },
                text = { Text("Start") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            )
        }

        NavOverlay(
            state = navState,
            onEnd = viewModel::stopNavigation,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun deepLinkUrl(intent: AppleMapsIntent): String? = when (intent) {
    is AppleMapsIntent.ShowPlace ->
        intent.point?.let { "https://maps.apple.com/place?coordinate=${it.latitude},${it.longitude}" }
            ?: intent.query?.let { "https://maps.apple.com/search?query=${Uri.encode(it)}" }
    is AppleMapsIntent.Search -> "https://maps.apple.com/search?query=${Uri.encode(intent.query)}"
    is AppleMapsIntent.Directions -> {
        val d = intent.destination?.let { "${it.latitude},${it.longitude}" }
            ?: intent.destinationQuery?.let(Uri::encode)
        d?.let { "https://maps.apple.com/directions?destination=$it" }
    }
    is AppleMapsIntent.LookAround ->
        "https://maps.apple.com/look-around?coordinate=${intent.point.latitude},${intent.point.longitude}"
    AppleMapsIntent.Unknown -> null
}
