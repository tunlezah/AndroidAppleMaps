package com.mapsdroid.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.mapsdroid.nav.NavHub
import com.mapsdroid.nav.NavigationState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Draws the map onto the Android Auto surface.
 *
 * Primary strategy (the project's choice): a chromeless MapKit JS page in a [WebView] composited onto
 * the car's [android.view.Surface] via a [VirtualDisplay] + [Presentation]. This keeps Apple's own map
 * on the car screen (satisfying the "display Apple data only on an Apple map" rule) and reuses the
 * MapKit JS token captured by the phone session.
 *
 * Honest caveats, verified in the research and surfaced here in code:
 *  - MapKit JS tokens are origin-locked to apple.com. If the captured token is rejected from our
 *    file://-origin car page, [CarBridge.onMapError] fires and we log it; the intended fallback is a
 *    native MapLibre renderer (Phase 6) drawn onto the same surface. That fallback is stubbed at
 *    [renderWithMapLibreFallback].
 *  - WebGL-in-a-WebView-on-a-VirtualDisplay performance is unproven on head units; measure before
 *    relying on it, and switch to the MapLibre path if frame rate is poor.
 *
 * Only map content is drawn here — all turn/ETA UI comes from the NavigationTemplate (quality rule NF-2).
 */
class CarSurfaceRenderer(private val carContext: CarContext) : SurfaceCallback {

    private val json = Json { ignoreUnknownKeys = true }
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var ready = false
    private var lastRouteRendered: String? = null

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
        val web = WebView(pres.context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(CarBridge(), "AndroidCar")
            loadUrl("file:///android_asset/web/car.html")
        }
        pres.setContentView(web)
        pres.window?.setFormat(PixelFormat.TRANSLUCENT)
        pres.show()
        webView = web
        presentation = pres
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        webView?.destroy()
        webView = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        ready = false
    }

    /** Called by the screen on each guidance update to follow the vehicle and draw the route. */
    fun render(state: NavigationState) {
        val web = webView ?: return
        if (!ready) return
        val puck = state.snappedLocation ?: state.rawLocation
        val route = state.route
        if (route != null) {
            val routeKey = "${route.name}:${route.polyline.size}"
            if (routeKey != lastRouteRendered) {
                val coords = json.encodeToString(route.polyline)
                web.post { web.evaluateJavascript("window.__car && __car.setRoute($coords);", null) }
                lastRouteRendered = routeKey
            }
        }
        if (puck != null) {
            val heading = state.rawLocation?.let { raw -> state.snappedLocation?.let { raw.bearingTo(it) } } ?: 0.0
            web.post {
                web.evaluateJavascript(
                    "window.__car && __car.follow(${puck.latitude},${puck.longitude},$heading);",
                    null,
                )
            }
        }
    }

    // --- SurfaceCallback pan/zoom: the host forwards only these abstract gestures, never raw touch. ---

    override fun onScroll(distanceX: Float, distanceY: Float) {
        webView?.post {
            webView?.evaluateJavascript("window.__car && __car.pan($distanceX,$distanceY);", null)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        webView?.post {
            webView?.evaluateJavascript("window.__car && __car.zoom($scaleFactor);", null)
        }
    }

    /** Stubbed native fallback for when MapKit JS cannot render in the car WebView (origin/perf). */
    private fun renderWithMapLibreFallback(state: NavigationState) {
        // Phase 6: draw a MapLibre map (Apple-look style over open tiles) onto the same surface.
        // See docs/ANDROID_AUTO.md for the integration plan.
        Log.w(TAG, "MapLibre car fallback not yet wired; state=${state.phase}")
    }

    private inner class CarBridge {
        @JavascriptInterface
        fun token(): String = NavHub.session.token.value.orEmpty()

        @JavascriptInterface
        fun onMapReady() { ready = true }

        @JavascriptInterface
        fun onMapError(message: String) {
            Log.w(TAG, "Car MapKit JS failed ($message); consider MapLibre fallback")
        }
    }

    private companion object {
        const val TAG = "CarSurfaceRenderer"
    }
}
