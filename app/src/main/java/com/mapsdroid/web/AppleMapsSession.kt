package com.mapsdroid.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
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

    /** Rolling diagnostics (page console output, JS errors, DOM probe) shown in the in-app panel. */
    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    val diagnostics: StateFlow<List<String>> = _diagnostics.asStateFlow()

    private fun addDiagnostic(line: String) {
        _diagnostics.value = (_diagnostics.value + line).takeLast(MAX_DIAGNOSTICS)
        Log.d("MapsDroid", line)
    }

    fun clearDiagnostics() {
        _diagnostics.value = emptyList()
    }

    var webView: WebView? = null
        private set

    private var pendingLoad = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var resizeNudged = false

    /** True once the page load has actually been issued (i.e. after the view had a real size). */
    var hasLoaded: Boolean = false
        private set

    /** Identity of the UI context that owns the current WebView, to detect a recreated Activity. */
    private var ownerContextId: Int = 0

    /**
     * Returns the shared WebView for [context], creating it on first use.
     *
     * Two constraints are balanced here:
     *  - It must be built with the **Activity (UI) context**. A WebView created from the application
     *    context can run and populate its DOM while drawing nothing at all, which looks exactly like a
     *    blank screen.
     *  - Only one may be alive at a time. Each instance holds its own WebGL context and the browser
     *    drops the oldest when too many are live ("Too many active WebGL contexts"), silently killing
     *    the rendered map. So a stale instance from a previous Activity is destroyed, not leaked.
     */
    fun obtainWebView(context: Context): WebView {
        val contextId = System.identityHashCode(context)
        val existing = webView
        if (existing != null && ownerContextId == contextId) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        existing?.let { stale ->
            (stale.parent as? ViewGroup)?.removeView(stale)
            runCatching { stale.destroy() }
            addDiagnostic("replaced stale WebView from a previous Activity")
        }
        webView = null
        hasLoaded = false
        pendingLoad = false
        ownerContextId = contextId
        return WebView(context).also(::attach)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        if (webView === view) return
        webView = view
        val bridge = MapBridge(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            // Honor the site's <meta viewport> the way Chrome does. Without this the WebView ignores
            // width=device-width, which can break a responsive SPA's layout maths.
            useWideViewPort = true
            loadWithOverviewMode = true
            // Present as a modern mobile Chrome so maps.apple.com serves its full mobile client.
            userAgentString = MOBILE_CHROME_UA
        }
        // WebView blocks third-party cookies by default (Chrome does not). Apple's map client talks to
        // cdn.apple-mapkit.com and *.ls.apple.com for its token/session bootstrap, which can fail
        // silently and leave an empty page.
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        }

        // Android WebView appends an "X-Requested-With: <package>" header that Chrome never sends.
        // Servers that do not list it in Access-Control-Allow-Headers fail the CORS preflight, which
        // surfaces in the page as "TypeError: Failed to fetch". An empty allow-list stops the header
        // being sent to any origin.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
                addDiagnostic("X-Requested-With header suppressed")
            }.onFailure { addDiagnostic("could not suppress X-Requested-With: ${it.message}") }
        } else {
            addDiagnostic("X-Requested-With control unsupported by this WebView")
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
                addDiagnostic("page finished: $url")
                // Fallback injection path for devices without DOCUMENT_START_SCRIPT support.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(injectedScript(), null)
                }
                // Insurance: if MapKit measured itself mid-layout, one resize makes it recompute.
                // Strictly once per load — the SPA fires onPageFinished repeatedly, and repeated
                // resizes make MapKit abort in-flight tile requests.
                if (!resizeNudged) {
                    resizeNudged = true
                    main.postDelayed({ dispatchResize() }, RESIZE_NUDGE_MS)
                }
                // Probe twice: immediately, and after the SPA has had time to boot. A blank page shows
                // up here as an empty body / missing mapkit / no WebGL.
                view.evaluateJavascript(probeScript(), null)
                main.postDelayed({ webView?.evaluateJavascript(probeScript(), null) }, PROBE_DELAY_MS)
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

            // The page's own console output is the most direct explanation of a blank render.
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                addDiagnostic(
                    "console/${message.messageLevel()}: ${message.message()} " +
                        "(${message.sourceId()?.takeLast(40)}:${message.lineNumber()})",
                )
                return true
            }
        }

        // Guarded: an unsupported allowed-origin rule must not crash the WebView factory (which would
        // blank the screen). We fall back to the onPageFinished injection path if this fails.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, injectedScript(), setOf("https://*.apple.com"))
            }.onFailure { onDiagnostic("addDocumentStartJavaScript failed: ${it.message}") }
        }

        // MapKit sizes its GL viewport from the container, so loading before layout produces
        // "glViewport: negative width/height" and a permanently blank map. Wait for a real size to
        // load, and tell the page whenever the size changes afterwards.
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width <= 0 || v.height <= 0) return@addOnLayoutChangeListener
            if (pendingLoad) {
                maybeLoad()
            } else if (v.width != lastWidth || v.height != lastHeight) {
                lastWidth = v.width
                lastHeight = v.height
                dispatchResize()
            }
        }
    }

    /** Requests the Apple Maps page. The actual load is deferred until the view has a non-zero size. */
    fun load() {
        pendingLoad = true
        maybeLoad()
    }

    /** Re-loads the Apple Maps page from scratch (used by the retry action). */
    fun reload() = load()

    private fun maybeLoad() {
        val view = webView ?: return
        if (!pendingLoad) return
        if (view.width <= 0 || view.height <= 0) {
            addDiagnostic("load deferred until layout (size ${view.width}x${view.height})")
            return
        }
        pendingLoad = false
        hasLoaded = true
        resizeNudged = false
        lastWidth = view.width
        lastHeight = view.height
        _pageStatus.value = PageStatus.Loading(CONSUMER_URL)
        addDiagnostic("loading at ${view.width}x${view.height}")
        main.post { view.loadUrl(CONSUMER_URL) }
    }

    /**
     * Loads a trivial local page instead of Apple Maps. Purely diagnostic: if this renders, the
     * WebView is drawing correctly and any blankness belongs to the Apple page; if this is also blank,
     * the WebView itself is not being composited.
     */
    fun loadRenderTest() {
        val view = webView ?: return
        pendingLoad = false
        _pageStatus.value = PageStatus.Loading("render-test")
        addDiagnostic("loading render test at ${view.width}x${view.height}")
        main.post {
            view.loadDataWithBaseURL("https://example.invalid/", RENDER_TEST_HTML, "text/html", "utf-8", null)
        }
    }

    /** Nudges the page to recompute its layout/GL viewport against the current view size. */
    fun dispatchResize() {
        val view = webView ?: return
        main.post {
            view.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
        }
    }

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
        addDiagnostic(message)
        onDiagnostic(message)
    }

    override fun onPageProbe(json: String) {
        addDiagnostic("probe: $json")
        onDiagnostic("probe: $json")
    }

    /**
     * Reports what the loaded document actually contains. An empty body with no canvas and no mapkit
     * is the signature of the SPA failing to boot; `webgl:false` would explain a missing map canvas.
     */
    private fun probeScript(): String = """
        (function () {
          try {
            var b = document.body;
            function rect(id) {
              var e = document.getElementById(id);
              if (!e) return null;
              var r = e.getBoundingClientRect();
              return [Math.round(r.width), Math.round(r.height)];
            }
            var mapRect = rect('shell-map');
            var degenerate = mapRect && (mapRect[0] <= 0 || mapRect[1] <= 0);
            var alreadyFixed = !!document.getElementById('__mapsdroid_fix');
            var repaired = false;
            // The collapse is permanent: with our override removed, the site lays #shell-map out at
            // height 0. So the repair is STICKY — applied once, never withdrawn. Reverting it made the
            // rect look healthy (it was healthy *because* of the override), so the old logic oscillated
            // applied/reverted, and each cycle's resize made MapKit abort its in-flight tile requests
            // ("AbortError: signal is aborted without reason"), so no tile ever finished loading.
            if (!alreadyFixed && degenerate && window.innerHeight > 0 && window.__mapsdroid) {
              repaired = window.__mapsdroid.repairLayout();
              mapRect = rect('shell-map');
            }

            // Canvas drawing-buffer vs CSS size: a zero-sized buffer renders nothing.
            var canvases = Array.prototype.slice.call(document.querySelectorAll('canvas')).map(function (c) {
              var r = c.getBoundingClientRect();
              return [c.width, c.height, Math.round(r.width), Math.round(r.height)];
            });

            // Are Apple's tile/token requests actually succeeding? responseStatus is exposed by
            // Chromium; transferSize 0 with no duration usually means blocked/failed.
            var res = {};
            var bad = [];
            try {
              var entries = performance.getEntriesByType('resource');
              for (var i = 0; i < entries.length; i++) {
                var e = entries[i];
                var host = '?';
                try { host = new URL(e.name).host; } catch (_) {}
                if (!res[host]) res[host] = 0;
                res[host]++;
                var status = e.responseStatus || 0;
                if (status >= 400 || (status === 0 && e.transferSize === 0 && e.duration === 0)) {
                  if (bad.length < 6) bad.push((status || 'blocked') + ' ' + e.name.slice(0, 110));
                }
              }
            } catch (_) {}

            AndroidBridge.onPageProbe(JSON.stringify({
              text: b ? (b.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 60) : '',
              hasMapkit: !!window.mapkit,
              mapkitMaps: (window.mapkit && window.mapkit.maps) ? window.mapkit.maps.length : -1,
              inner: [window.innerWidth, window.innerHeight],
              map: mapRect,
              canvases: canvases,
              repaired: repaired,
              fixed: alreadyFixed || repaired,
              hosts: res,
              failures: bad,
              readyState: document.readyState
            }));
          } catch (e) {
            try { AndroidBridge.onPageProbe(JSON.stringify({ error: String(e) })); } catch (_) {}
          }
        })();
    """.trimIndent()

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
        private const val PROBE_DELAY_MS = 6_000L
        private const val RESIZE_NUDGE_MS = 800L
        private val RENDER_TEST_HTML = """
            <!doctype html><html><head><meta name="viewport"
              content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;font-family:sans-serif">
              <div style="height:40vh;background:#1D6FF2;color:#fff;display:flex;
                          align-items:center;justify-content:center;font-size:6vw">
                WebView renders OK
              </div>
              <div style="height:30vh;background:#E0362C"></div>
              <canvas id="c" style="width:100%;height:30vh;display:block"></canvas>
              <script>
                var c = document.getElementById('c');
                var gl = c.getContext('webgl') || c.getContext('webgl2');
                if (gl) { gl.clearColor(0.1, 0.7, 0.3, 1); gl.clear(gl.COLOR_BUFFER_BIT); }
                else { c.style.background = '#999'; }
              </script>
            </body></html>
        """.trimIndent()
        private const val MAX_DIAGNOSTICS = 200
        private const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
