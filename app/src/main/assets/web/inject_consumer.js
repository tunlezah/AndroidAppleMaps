// Injected at document-start into https://maps.apple.com/ (the consumer Apple Maps site).
//
// Purpose:
//   1. Capture the MapKit JS access token the page mints for itself, so native code can reuse it.
//   2. Expose window.__mapsdroid — route(), lookAround(), setCamera() — that run against the page's
//      own initialized `mapkit`, i.e. inside Apple's origin where the token is valid.
//
// Everything here is best-effort and defensive: Apple can change the consumer site at any time, and
// when a capture path stops working the app still has a fully functional Apple Maps UI in the WebView.
(function () {
  "use strict";

  function log(m) { try { AndroidBridge.log("[inject] " + m); } catch (e) {} }
  var tokenSent = false;
  function sendToken(t) {
    if (tokenSent || !t || typeof t !== "string" || t.indexOf("eyJ") !== 0) return;
    tokenSent = true;
    try { AndroidBridge.onToken(t); } catch (e) {}
    log("token captured");
  }

  // --- Capture path A: wrap mapkit.init's authorizationCallback ---
  function patchInit(mk) {
    if (!mk || mk.__mdPatched) return;
    if (typeof mk.init !== "function") return;
    mk.__mdPatched = true;
    var origInit = mk.init.bind(mk);
    mk.init = function (opts) {
      try {
        if (opts && typeof opts.authorizationCallback === "function") {
          var orig = opts.authorizationCallback;
          opts.authorizationCallback = function (done) {
            return orig(function (token) {
              sendToken(token);
              return done(token);
            });
          };
        }
      } catch (e) { log("init wrap failed: " + e); }
      return origInit(opts);
    };
    log("mapkit.init patched");
  }

  // mapkit is assigned by the loader after our script runs; intercept the assignment.
  try {
    var _mk = window.mapkit;
    Object.defineProperty(window, "mapkit", {
      configurable: true,
      get: function () { return _mk; },
      set: function (v) { _mk = v; try { patchInit(v); } catch (e) {} },
    });
    if (_mk) patchInit(_mk);
  } catch (e) { log("defineProperty failed: " + e); }

  // --- Capture path B: sniff network responses for a JWT (belt-and-suspenders) ---
  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function () {
        return origFetch.apply(this, arguments).then(function (resp) {
          try {
            var clone = resp.clone();
            var ct = clone.headers.get("content-type") || "";
            if (ct.indexOf("text") >= 0 || ct.indexOf("json") >= 0) {
              clone.text().then(function (body) {
                var m = body && body.match(/eyJ[\w-]+\.[\w-]+\.[\w-]+/);
                if (m) sendToken(m[0]);
              }).catch(function () {});
            }
          } catch (e) {}
          return resp;
        });
      };
    }
  } catch (e) { log("fetch hook failed: " + e); }

  // --- Poll for readiness and late-defined init ---
  var tries = 0;
  var poll = setInterval(function () {
    tries++;
    if (window.mapkit) {
      patchInit(window.mapkit);
      if (window.mapkit.Directions) {
        try { AndroidBridge.onMapReady(); } catch (e) {}
        clearInterval(poll);
      }
    }
    if (tries > 500) clearInterval(poll); // ~10s
  }, 20);

  // --- window.__mapsdroid: commands runnable from native code ---
  function coord(lat, lng) { return new window.mapkit.Coordinate(lat, lng); }

  window.__mapsdroid = {
    route: function (oLat, oLng, dLat, dLng, transport, requestId) {
      try {
        var mk = window.mapkit;
        var directions = new mk.Directions();
        var transportEnum = mk.Directions.Transport[transport] || mk.Directions.Transport.Automobile;
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
                    return {
                      instructions: s.instructions || "",
                      distance: s.distance || 0,
                      duration: 0,
                      path: path,
                    };
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
      // Deep-link the consumer site's own Look Around; this drives the page's binoculars UI.
      try { window.location.href = "https://maps.apple.com/look-around?coordinate=" + lat + "," + lng; } catch (e) {}
    },

    setCamera: function (lat, lng, distance, heading) {
      try {
        var maps = window.mapkit && window.mapkit.maps;
        var map = maps && maps.length ? maps[0] : null;
        if (!map) return;
        map.setCenterAnimated(coord(lat, lng), false);
        if (distance != null && map.cameraDistance != null) map.cameraDistance = distance;
        if (heading != null && map.rotation != null) map.rotation = heading;
      } catch (e) { log("setCamera failed: " + e); }
    },
  };

  log("injected");
})();
