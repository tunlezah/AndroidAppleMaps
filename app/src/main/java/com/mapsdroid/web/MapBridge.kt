package com.mapsdroid.web

import android.webkit.JavascriptInterface

/**
 * The `@JavascriptInterface` surface exposed to the page as `AndroidBridge`. All methods are invoked
 * on the WebView's JS worker thread, so implementations must be thread-safe and must hop to the main
 * thread before touching the WebView. [AppleMapsSession] provides the implementation.
 */
class MapBridge(private val callbacks: Callbacks) {

    interface Callbacks {
        /** The MapKit JS access token captured from the consumer page's own init handshake. */
        fun onToken(token: String)

        /** mapkit is initialized and usable in the page. */
        fun onMapReady()

        /** A directions request completed; [json] conforms to DirectionsResultDto. */
        fun onDirectionsResult(requestId: String, json: String)

        /** A place was selected/opened on the map (json: {name, latitude, longitude, placeId?}). */
        fun onPlaceSelected(json: String)

        /** Diagnostic logging from the injected scripts. */
        fun onLog(message: String)
    }

    @JavascriptInterface
    fun onToken(token: String) = callbacks.onToken(token)

    @JavascriptInterface
    fun onMapReady() = callbacks.onMapReady()

    @JavascriptInterface
    fun onDirectionsResult(requestId: String, json: String) = callbacks.onDirectionsResult(requestId, json)

    @JavascriptInterface
    fun onPlaceSelected(json: String) = callbacks.onPlaceSelected(json)

    @JavascriptInterface
    fun log(message: String) = callbacks.onLog(message)
}
