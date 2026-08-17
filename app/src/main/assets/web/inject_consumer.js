// Injected at document-start into https://maps.apple.com/ (the consumer Apple Maps site).
//
// IMPORTANT: this must never interfere with the consumer site's own rendering. Earlier versions
// redefined window.mapkit and wrapped window.fetch to capture the access token; that risked breaking
// MapKit's initialization and leaving a blank map. This version is deliberately passive: it only
// reads the page's already-initialized `mapkit` to expose helpers, and never redefines page globals.
//
// The phone does not need the token at all: __mapsdroid.route() runs mapkit.Directions inside the
// page's own (already authenticated) mapkit instance. Token capture for the separate Android Auto
// map is intentionally dropped here; the car uses its MapLibre fallback when no token is available.
(function () {
  "use strict";

  function log(m) { try { AndroidBridge.log("[inject] " + m); } catch (e) {} }
  function coord(lat, lng) { return new window.mapkit.Coordinate(lat, lng); }

  // Passive error reporting: surfaces the site's own failures (which is what a blank page looks like)
  // without altering any page behaviour.
  window.addEventListener("error", function (e) {
    try {
      if (e && e.target && e.target.tagName && (e.target.src || e.target.href)) {
        log("resource failed: " + (e.target.src || e.target.href));
      } else if (e) {
        log("JS error: " + (e.message || "?") + " @" + (e.filename || "?") + ":" + (e.lineno || 0));
      }
    } catch (_) {}
  }, true);
  window.addEventListener("unhandledrejection", function (e) {
    try { log("JS rejection: " + String(e && e.reason)); } catch (_) {}
  });

  window.__mapsdroid = {
    route: function (oLat, oLng, dLat, dLng, transport, requestId) {
      try {
        var mk = window.mapkit;
        if (!mk || !mk.Directions) {
          AndroidBridge.onDirectionsResult(requestId, JSON.stringify({ routes: [], error: "mapkit not ready" }));
          return;
        }
        var directions = new mk.Directions();
        var transportEnum = (mk.Directions.Transport && mk.Directions.Transport[transport]) || undefined;
        directions.route(
          {
            origin: coord(oLat, oLng),
            destination: coord(dLat, dLng),
            transportType: transportEnum,
            requestsAlternateRoutes: true,
          },
          function (error, data) {
            var out = { routes: [] };
            if (error || !data || !data.routes) {
              out.error = error ? String(error) : "no routes";
            } else {
              out.routes = data.routes.map(function (r) {
                return {
                  name: r.name || "",
                  distance: r.distance || 0,
                  expectedTravelTime: r.expectedTravelTime || 0,
                  transportType: transport,
                  hasTolls: !!r.hasTolls,
                  steps: (r.steps || []).map(function (s) {
                    var path = (s.path || []).map(function (c) {
                      return { latitude: c.latitude, longitude: c.longitude };
                    });
                    return { instructions: s.instructions || "", distance: s.distance || 0, duration: 0, path: path };
                  }),
                };
              });
            }
            try { AndroidBridge.onDirectionsResult(requestId, JSON.stringify(out)); } catch (e) {}
          }
        );
      } catch (e) {
        try { AndroidBridge.onDirectionsResult(requestId, JSON.stringify({ routes: [], error: String(e) })); } catch (_) {}
      }
    },

    lookAround: function (lat, lng) {
      try { window.location.href = "https://maps.apple.com/look-around?coordinate=" + lat + "," + lng; } catch (e) {}
    },

    setCamera: function (lat, lng) {
      try {
        var maps = window.mapkit && window.mapkit.maps;
        var map = maps && maps.length ? maps[0] : null;
        if (map) map.setCenterAnimated(coord(lat, lng), false);
      } catch (e) {}
    },
  };

  // Readiness poll. NOTE: reading mapkit.Directions before the 'services' library has loaded makes
  // MapKit *throw* ("mapkit.Directions is available after loading the following library: services"),
  // so the probe must be inside try/catch or it surfaces as an uncaught page error.
  function directionsAvailable() {
    try {
      return typeof window.mapkit.Directions === "function";
    } catch (e) {
      return false;
    }
  }

  var tries = 0;
  var poll = setInterval(function () {
    tries++;
    if (window.mapkit && directionsAvailable()) {
      try { AndroidBridge.onMapReady(); } catch (e) {}
      clearInterval(poll);
    }
    if (tries > 600) clearInterval(poll); // ~30s
  }, 50);

  log("injected (passive)");
})();
