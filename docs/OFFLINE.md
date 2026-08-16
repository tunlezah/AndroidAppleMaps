# Offline mode (Phase 6)

## Why Apple's own tiles can't go offline

The research (`RESEARCH.md` §2.3) established three independent blockers, each fatal on its own:

1. **Expiring signed tile URLs.** Every Apple tile URL carries a short-lived `accessKey` minted online
   via a `geo_manifest` bootstrap; replayed URLs return HTTP 410. `WebView.shouldInterceptRequest`
   can't survive this (and can't read the POST token bootstrap either).
2. **Proprietary format.** Apple's 2D tiles are VMP4, decodable only by Apple's own client.
3. **Contract.** Apple's terms forbid caching/pre-fetching/storing map data beyond transient
   performance caching.

So offline is an **open-data substitute**, styled to feel like Apple Maps — not Apple Maps offline.

## The stack

| Concern | Choice |
|---|---|
| Base map data | **Protomaps PMTiles** regional extracts (single-file, OSM-derived) |
| Rendering | **MapLibre GL Native** (already a dependency) with an Apple-look style |
| Style | Fork `dddevid/MapLibre-GL-JS-AppleMaps-Style` (MIT) for dark; author a light sibling |
| Routing | **Valhalla** compiled via the NDK; its `odin` maneuvers are richer than Apple's text |
| Search | Offline geocoder over the same extract (Pelias/Nominatim extract or the PMTiles index) |

## What's already scaffolded

- `offline/OfflineRegion.kt` — region model (bbox + local pack paths).
- `offline/OfflineMapManager.kt` — region metadata + storage; `download()` is the remaining pipeline.
- `offline/ValhallaRouter.kt` — `RouteProvider` stub; returns empty until the JNI core is wired.
- `offline/ConnectivityRouteProvider.kt` — **already functional**: picks Apple routing when online,
  the offline router when not. Wire it into `NavHub` as the engine's `RouteProvider` to activate the
  fallback the moment Valhalla returns real routes.

Because `GuidanceEngine` consumes any `RouteProvider`, turn-by-turn, off-route detection, and
rerouting work identically offline once `ValhallaRouter` is implemented — no engine changes.

## Remaining work

1. **PMTiles download**: fetch a bbox extract (Protomaps extract service or a bundled planet slice)
   into `filesDir/offline/`; show size before download (like iOS region download UX).
2. **MapLibre map view**: a Compose `MapView` reading the PMTiles pack with the Apple-look style;
   swap it in for the WebView when connectivity is lost.
3. **Valhalla NDK**: build `libvalhalla`, load the region's routing tiles, call `route`, and map
   `trip.legs[].maneuvers[]` into `core.Route` (lane + maneuver-type data included).
4. **Reuse in the car**: the same MapLibre renderer is the Android Auto surface fallback
   (`CarSurfaceRenderer.renderWithMapLibreFallback`).

Attribution: OSM data requires `© OpenStreetMap contributors` shown wherever the offline map renders.
