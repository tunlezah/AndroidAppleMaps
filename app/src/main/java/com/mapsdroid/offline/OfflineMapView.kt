package com.mapsdroid.offline

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mapsdroid.location.AppLocation
import com.mapsdroid.nav.NavigationState
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
 * The offline map: MapLibre GL Native rendering a downloaded Protomaps PMTiles pack with the
 * Apple-look style, plus a route line and location puck driven by the shared guidance state. Shown in
 * place of the Apple WebView when the device is offline.
 */
@Composable
fun OfflineMapView(
    region: OfflineRegion?,
    navState: NavigationState,
    location: AppLocation?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = isSystemInDarkTheme()
    val holder = remember { MapHolder() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.mapView?.onStart()
                Lifecycle.Event.ON_RESUME -> holder.mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.mapView?.onPause()
                Lifecycle.Event.ON_STOP -> holder.mapView?.onStop()
                Lifecycle.Event.ON_DESTROY -> holder.mapView?.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.mapView?.onStop()
            holder.mapView?.onDestroy()
            holder.mapView = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapLibre.getInstance(ctx)
            MapView(ctx).apply {
                holder.mapView = this
                onCreate(null)
                getMapAsync { map ->
                    holder.map = map
                    val styleJson = StyleProvider.styleJson(ctx, region, dark)
                    map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                        holder.onStyleReady(style)
                    }
                }
            }
        },
        update = { holder.update(navState, location) },
    )
}

/** Retains the MapLibre view/map/sources and applies guidance updates once the style is loaded. */
private class MapHolder {
    var mapView: MapView? = null
    var map: MapLibreMap? = null
    private var routeSource: GeoJsonSource? = null
    private var puckSource: GeoJsonSource? = null
    private var styleReady = false

    fun onStyleReady(style: Style) {
        routeSource = GeoJsonSource(ROUTE_SOURCE)
        puckSource = GeoJsonSource(PUCK_SOURCE)
        style.addSource(routeSource!!)
        style.addSource(puckSource!!)
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#1D6FF2"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        style.addLayer(
            CircleLayer(PUCK_LAYER, PUCK_SOURCE).withProperties(
                PropertyFactory.circleColor("#1D6FF2"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        styleReady = true
    }

    fun update(navState: NavigationState, location: AppLocation?) {
        if (!styleReady) return
        val map = map ?: return

        navState.route?.polyline?.takeIf { it.size >= 2 }?.let { pts ->
            val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })
            routeSource?.setGeoJson(Feature.fromGeometry(line))
        }

        val puck = navState.snappedLocation ?: location?.point
        if (puck != null) {
            puckSource?.setGeoJson(
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(puck.longitude, puck.latitude)),
                ),
            )
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(puck.latitude, puck.longitude), 16.0),
            )
        }
    }

    private companion object {
        const val ROUTE_SOURCE = "route-src"
        const val ROUTE_LAYER = "route-layer"
        const val PUCK_SOURCE = "puck-src"
        const val PUCK_LAYER = "puck-layer"
    }
}
