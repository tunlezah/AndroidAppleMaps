package com.mapsdroid.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mapsdroid.links.AppleMapsIntent
import com.mapsdroid.nav.NavPhase
import com.mapsdroid.offline.OfflineMapView
import com.mapsdroid.web.AppleMapsSession

/**
 * The phone experience: the Apple Maps consumer site full-screen (search, place cards, Look Around,
 * and map all provided by Apple), with our native turn-by-turn overlay on top during guidance, and a
 * persistent status line so a failure to render is always explained rather than silent.
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
    val pageStatus by viewModel.pageStatus.collectAsState()
    val trayHidden by viewModel.trayHidden.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showDiagnostics) {
        DiagnosticsSheet(
            lines = diagnostics,
            onClose = { showDiagnostics = false },
            onClear = viewModel::clearDiagnostics,
            onReload = viewModel::reloadMap,
            onRenderTest = {
                viewModel.runRenderTest()
                showDiagnostics = false
            },
            onOpenExternally = { openAppleMapsExternally(context) },
        )
        return
    }

    // Only use the offline map when we are actually offline AND have a downloaded region to render;
    // otherwise the offline style is a blank background. With no regions downloaded (the default),
    // this always shows the Apple WebView.
    val region = location?.point?.let { p -> regions.firstOrNull { it.contains(p) } }
        ?: regions.firstOrNull()
    val showOffline = !isOnline && region != null

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        StatusBar(
            online = isOnline,
            pageStatus = pageStatus,
            offlineRegions = regions.size,
            onReload = viewModel::reloadMap,
            onToggleTray = viewModel::toggleTray,
            trayHidden = trayHidden,
            onShowDiagnostics = { showDiagnostics = true },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (showOffline) {
                OfflineMapView(
                    region = region,
                    navState = navState,
                    location = location,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(
                    // Reuse the session's single WebView: creating a new one per composition leaks a
                    // WebGL context each time and the browser then drops the live map's context.
                    factory = { ctx ->
                        viewModel.session.obtainWebView(ctx).also { web ->
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
}

private fun openAppleMapsExternally(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppleMapsSession.CONSUMER_URL)))
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
