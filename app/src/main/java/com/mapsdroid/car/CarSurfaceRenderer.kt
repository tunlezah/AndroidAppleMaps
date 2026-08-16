package com.mapsdroid.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.nav.NavHub
import com.mapsdroid.nav.NavigationState
import com.mapsdroid.offline.StyleProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws the map onto the Android Auto surface, composited via a [VirtualDisplay] + [Presentation].
 *
 * Two renderers share the same virtual display:
 *  - **Primary — chromeless MapKit JS WebView** (`assets/web/car.html`): keeps Apple's own map on the
 *    car screen, reusing the token captured by the phone session.
 *  - **Fallback — MapLibre** (this file): a native Apple-look map over the downloaded PMTiles region.
 *    Activated automatically when the WebView reports a MapKit failure (the token is origin-locked to
 *    apple.com and may be rejected from the car page's file:// origin) — see [CarBridge.onMapError].
 *
 * Only map content is drawn here; all turn/ETA UI comes from the NavigationTemplate (quality rule NF-2).
 */
class CarSurfaceRenderer(private val carContext: CarContext) : SurfaceCallback {

    private val json = Json { ignoreUnknownKeys = true }
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    private var webView: WebView? = null
    private var webReady = false
    private var lastRouteRendered: String? = null

    // MapLibre fallback state.
    private var usingFallback = false
    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null
    private var routeSource: GeoJsonSource? = null
    private var puckSource: GeoJsonSource? = null
    private var fallbackStyleReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val dm = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val vd = dm.createVirtualDisplay(
            "MapsCarDisplay",
            surfaceContainer.width.coerceAtLeast(1),
            surfaceContainer.height.coerceAtLeast(1),
            surfaceContainer.dpi.coerceAtLeast(1),
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        virtualDisplay = vd
        val display = vd.display ?: return
        val pres = Presentation(carContext, display)
        pres.window?.setFormat(PixelFormat.TRANSLUCENT)
        presentation = pres

        val web = WebView(pres.context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(CarBridge(), "AndroidCar")
            loadUrl("file:///android_asset/web/car.html")
        }
        pres.setContentView(web)
        pres.show()
        webView = web
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        webView?.destroy()
        webView = null
        teardownFallback()
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        webReady = false
    }

    /** Called by the screen on each guidance update to follow the vehicle and draw the route. */
    fun render(state: NavigationState) {
        if (usingFallback) renderFallback(state) else renderWebView(state)
    }

    private fun renderWebView(state: NavigationState) {
        val web = webView ?: return
        if (!webReady) return
        val puck = state.snappedLocation ?: state.rawLocation
        state.route?.let { route ->
            val routeKey = "${route.name}:${route.polyline.size}"
            if (routeKey != lastRouteRendered) {
                val coords = json.encodeToString(route.polyline)
                web.post { web.evaluateJavascript("window.__car && __car.setRoute($coords);", null) }
                lastRouteRendered = routeKey
            }
        }
        if (puck != null) {
            val heading = headingOf(state)
            web.post {
                web.evaluateJavascript("window.__car && __car.follow(${puck.latitude},${puck.longitude},$heading);", null)
            }
        }
    }

    // --- SurfaceCallback pan/zoom: the host forwards only abstract gestures, never raw touch. ---

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (usingFallback) {
            val map = maplibreMap ?: return
            val center = map.cameraPosition.target ?: return
            val screen = map.projection.toScreenLocation(center)
            val moved = map.projection.fromScreenLocation(PointF(screen.x + distanceX, screen.y + distanceY))
            map.moveCamera(CameraUpdateFactory.newLatLng(moved))
        } else {
            webView?.post { webView?.evaluateJavascript("window.__car && __car.pan($distanceX,$distanceY);", null) }
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (usingFallback) {
            maplibreMap?.let { it.moveCamera(CameraUpdateFactory.zoomBy((scaleFactor - 1.0).toDouble())) }
        } else {
            webView?.post { webView?.evaluateJavascript("window.__car && __car.zoom($scaleFactor);", null) }
        }
    }

    // --- MapLibre fallback ---

    private fun switchToMapLibreFallback(reason: String) {
        if (usingFallback) return
        usingFallback = true
        Log.w(TAG, "Switching car map to MapLibre fallback: $reason")
        val pres = presentation ?: return
        MapLibre.getInstance(pres.context)
        val mv = MapView(pres.context)
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        mv.getMapAsync { map ->
            maplibreMap = map
            val region = NavHub.offlineManager.regionContaining(
                NavHub.state.value.snappedLocation ?: GeoPoint(0.0, 0.0),
            ) ?: NavHub.offlineManager.regions.value.firstOrNull()
            val styleJson = StyleProvider.styleJson(pres.context, region, dark = true)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                routeSource = GeoJsonSource(ROUTE_SRC).also(style::addSource)
                puckSource = GeoJsonSource(PUCK_SRC).also(style::addSource)
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
                        PropertyFactory.lineColor("#1D6FF2"),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                    ),
                )
                style.addLayer(
                    CircleLayer(PUCK_LAYER, PUCK_SRC).withProperties(
                        PropertyFactory.circleColor("#1D6FF2"),
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                fallbackStyleReady = true
                renderFallback(NavHub.state.value)
            }
        }
        webView?.destroy()
        webView = null
        pres.setContentView(mv)
        mapView = mv
    }

    private fun renderFallback(state: NavigationState) {
        if (!fallbackStyleReady) return
        val map = maplibreMap ?: return
        state.route?.polyline?.takeIf { it.size >= 2 }?.let { pts ->
            val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })
            routeSource?.setGeoJson(Feature.fromGeometry(line))
        }
        val puck = state.snappedLocation ?: state.rawLocation
        if (puck != null) {
            puckSource?.setGeoJson(
                FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(puck.longitude, puck.latitude))),
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(puck.latitude, puck.longitude), 16.0))
        }
    }

    private fun teardownFallback() {
        mapView?.onPause()
        mapView?.onStop()
        mapView?.onDestroy()
        mapView = null
        maplibreMap = null
        routeSource = null
        puckSource = null
        fallbackStyleReady = false
        usingFallback = false
    }

    private fun headingOf(state: NavigationState): Double {
        val raw = state.rawLocation
        val snapped = state.snappedLocation
        return if (raw != null && snapped != null) raw.bearingTo(snapped) else 0.0
    }

    private inner class CarBridge {
        @JavascriptInterface
        fun token(): String = NavHub.session.token.value.orEmpty()

        @JavascriptInterface
        fun onMapReady() { webReady = true }

        @JavascriptInterface
        fun onMapError(message: String) {
            webView?.post { switchToMapLibreFallback(message) }
        }
    }

    private companion object {
        const val TAG = "CarSurfaceRenderer"
        const val ROUTE_SRC = "car-route-src"
        const val ROUTE_LAYER = "car-route-layer"
        const val PUCK_SRC = "car-puck-src"
        const val PUCK_LAYER = "car-puck-layer"
    }
}
