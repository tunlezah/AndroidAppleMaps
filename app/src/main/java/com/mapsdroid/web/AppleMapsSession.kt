package com.mapsdroid.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.Route
import com.mapsdroid.core.TransportType
import com.mapsdroid.directions.DirectionsResultDto
import com.mapsdroid.directions.RouteMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns a WebView that wraps the Apple Maps consumer site and mediates all interaction with it.
 *
 * Strategy ("consumer wrapping", per the project decision):
 *  1. Load `https://maps.apple.com/` directly. Running in Apple's own origin is what makes the
 *     self-issued MapKit JS token valid — no Apple Developer Program key required.
 *  2. Inject a document-start script that (a) captures that token as it is minted, and (b) exposes a
 *     small `__mapsdroid` API that runs `mapkit.Directions` *inside the page's origin*, so route data
 *     comes back to native code with the token/origin constraints already satisfied.
 *  3. If the token is never captured (Apple changed the handshake), we still have a fully working
 *     Apple Maps UI in the WebView — the documented fallback.
 */
class AppleMapsSession(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : MapBridge.Callbacks {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<List<Route>>>()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _selectedPlace = MutableStateFlow<GeoPoint?>(null)
    val selectedPlace: StateFlow<GeoPoint?> = _selectedPlace.asStateFlow()

    /** Load state of the Apple Maps page, surfaced in the UI so a blank page is never silent. */
    sealed interface PageStatus {
        data object Idle : PageStatus
        data class Loading(val url: String) : PageStatus
        data class Finished(val url: String) : PageStatus
        data class Error(val code: Int, val description: String) : PageStatus
    }

    private val _pageStatus = MutableStateFlow<PageStatus>(PageStatus.Idle)
    val pageStatus: StateFlow<PageStatus> = _pageStatus.asStateFlow()

    var webView: WebView? = null
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        webView = view
        val bridge = MapBridge(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            // Present as a modern mobile Chrome so maps.apple.com serves its full mobile client.
            userAgentString = MOBILE_CHROME_UA
        }
        view.addJavascriptInterface(bridge, "AndroidBridge")
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                _pageStatus.value = PageStatus.Loading(url.orEmpty())
                onDiagnostic("page started: $url")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _pageStatus.value = PageStatus.Finished(url.orEmpty())
                onDiagnostic("page finished: $url")
                // Fallback injection path for devices without DOCUMENT_START_SCRIPT support.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(injectedScript(), null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    _pageStatus.value = PageStatus.Error(error.errorCode, error.description.toString())
                    onDiagnostic("WebView error ${error.errorCode} on ${request.url}: ${error.description}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    _pageStatus.value = PageStatus.Error(errorResponse.statusCode, "HTTP error")
                    onDiagnostic("HTTP ${errorResponse.statusCode} on ${request.url}")
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                // The host Activity gates ACCESS_FINE_LOCATION; within the page we grant Apple's origin.
                callback.invoke(origin, true, false)
            }
        }

        // Guarded: an unsupported allowed-origin rule must not crash the WebView factory (which would
        // blank the screen). We fall back to the onPageFinished injection path if this fails.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, injectedScript(), setOf("https://*.apple.com"))
            }.onFailure { onDiagnostic("addDocumentStartJavaScript failed: ${it.message}") }
        }
    }

    fun load() {
        val view = webView ?: return
        _pageStatus.value = PageStatus.Loading(CONSUMER_URL)
        main.post { view.loadUrl(CONSUMER_URL) }
    }

    /** Re-loads the Apple Maps page from scratch (used by the retry action). */
    fun reload() = load()

    /**
     * Requests a route via mapkit.Directions running in the page. Returns the parsed routes, or an
     * empty list on timeout/failure (the guidance engine treats empty as "keep the current route").
     */
    suspend fun route(from: GeoPoint, to: GeoPoint, transport: TransportType): List<Route> {
        val view = webView ?: return emptyList()
        if (_token.value == null && !_ready.value) return emptyList()
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<List<Route>>()
        pending[requestId] = deferred
        val transportName = when (transport) {
            TransportType.WALKING -> "Walking"
            TransportType.CYCLING -> "Cycling"
            TransportType.AUTOMOBILE -> "Automobile"
        }
        val js = "window.__mapsdroid && window.__mapsdroid.route(" +
            "${from.latitude},${from.longitude},${to.latitude},${to.longitude}," +
            "'$transportName','$requestId');"
        main.post { view.evaluateJavascript(js, null) }
        return withTimeoutOrNull(ROUTE_TIMEOUT_MS) { deferred.await() } ?: run {
            pending.remove(requestId)
            emptyList()
        }
    }

    /** Opens Look Around at a coordinate using the page's own binoculars UI. */
    fun openLookAround(point: GeoPoint) {
        val view = webView ?: return
        val js = "window.__mapsdroid && window.__mapsdroid.lookAround(${point.latitude},${point.longitude});"
        main.post { view.evaluateJavascript(js, null) }
    }

    /** Moves the map camera (used to mirror guidance and to drive the car surface). */
    fun setCamera(center: GeoPoint, distanceMetres: Double? = null, headingDegrees: Double? = null) {
        val view = webView ?: return
        val js = "window.__mapsdroid && window.__mapsdroid.setCamera(${center.latitude},${center.longitude}," +
            "${distanceMetres ?: "null"},${headingDegrees ?: "null"});"
        main.post { view.evaluateJavascript(js, null) }
    }

    // --- MapBridge.Callbacks (invoked on the JS worker thread) ---

    override fun onToken(token: String) {
        _token.value = token
        onDiagnostic("Captured MapKit JS token (${token.length} chars)")
    }

    override fun onMapReady() {
        _ready.value = true
        onDiagnostic("mapkit ready")
    }

    override fun onDirectionsResult(requestId: String, json: String) {
        val deferred = pending.remove(requestId) ?: return
        val routes = runCatching {
            RouteMapper.map(this.json.decodeFromString(DirectionsResultDto.serializer(), json))
        }.getOrDefault(emptyList())
        deferred.complete(routes)
    }

    override fun onPlaceSelected(json: String) {
        runCatching {
            val obj = this.json.parseToJsonElement(json)
            // Minimal, defensive parse; the injected script emits {latitude, longitude, name}.
            val dto = this.json.decodeFromString(GeoPoint.serializer(), json)
            _selectedPlace.value = dto
        }
    }

    override fun onLog(message: String) {
        Log.d("AppleMapsSession", message)
        onDiagnostic(message)
    }

    /** The document-start script, read from assets and inlined so it runs before Apple's scripts. */
    private fun injectedScript(): String = cachedScript ?: appContext.assets
        .open("web/inject_consumer.js")
        .bufferedReader()
        .use { it.readText() }
        .also { cachedScript = it }

    private var cachedScript: String? = null

    companion object {
        const val CONSUMER_URL = "https://maps.apple.com/"
        private const val ROUTE_TIMEOUT_MS = 12_000L
        private const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
