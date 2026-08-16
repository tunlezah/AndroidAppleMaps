// Chromeless MapKit JS map for the Android Auto surface. Initialized with the token captured by the
// phone session (AndroidCar.token()). Exposes window.__car for the native CarSurfaceRenderer to drive.
//
// If MapKit JS rejects the token (it is origin-locked to apple.com — see CarSurfaceRenderer docs),
// AndroidCar.onMapError is called so native code can fall back to a MapLibre renderer.
(function () {
  "use strict";

  var map = null;
  var routeOverlay = null;
  var puckAnnotation = null;

  function boot() {
    if (!window.mapkit) { setTimeout(boot, 30); return; }
    try {
      mapkit.init({
        authorizationCallback: function (done) {
          var t = "";
          try { t = window.AndroidCar.token(); } catch (e) {}
          done(t);
        },
      });
      map = new mapkit.Map("map", {
        showsCompass: mapkit.FeatureVisibility.Hidden,
        showsScale: mapkit.FeatureVisibility.Hidden,
        showsUserLocation: false,
        colorScheme: mapkit.Map.ColorSchemes.Dark,
      });
      try { window.AndroidCar.onMapReady(); } catch (e) {}
    } catch (e) {
      try { window.AndroidCar.onMapError(String(e)); } catch (_) {}
    }
  }

  function coord(lat, lng) { return new mapkit.Coordinate(lat, lng); }

  window.__car = {
    follow: function (lat, lng, heading) {
      if (!map) return;
      map.setCenterAnimated(coord(lat, lng), false);
      if (map.rotation != null && heading != null) map.rotation = heading;
      if (map.cameraDistance != null && map.cameraDistance > 1200) map.cameraDistance = 800;
      if (!puckAnnotation) {
        puckAnnotation = new mapkit.MarkerAnnotation(coord(lat, lng), { color: "#1D6FF2" });
        map.addAnnotation(puckAnnotation);
      } else {
        puckAnnotation.coordinate = coord(lat, lng);
      }
    },
    setRoute: function (coords) {
      if (!map || !coords || !coords.length) return;
      if (routeOverlay) map.removeOverlay(routeOverlay);
      var points = coords.map(function (c) { return coord(c.latitude, c.longitude); });
      var style = new mapkit.Style({ lineWidth: 6, strokeColor: "#1D6FF2", lineJoin: "round" });
      routeOverlay = new mapkit.PolylineOverlay(points, { style: style });
      map.addOverlay(routeOverlay);
    },
    pan: function (dx, dy) {
      if (!map) return;
      var c = map.center;
      var span = map.region.span;
      map.setCenterAnimated(
        coord(c.latitude + span.latitudeDelta * (dy / 800), c.longitude - span.longitudeDelta * (dx / 800)),
        false
      );
    },
    zoom: function (factor) {
      if (!map || map.cameraDistance == null) return;
      map.cameraDistance = Math.max(150, map.cameraDistance / factor);
    },
  };

  boot();
})();
