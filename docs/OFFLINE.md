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

## What's implemented now

- `offline/OfflineRegion.kt` — region model (bbox + local pack paths), persisted as JSON.
- `offline/OfflineMapManager.kt` — **real** region download + metadata persistence + delete.
- `offline/PmTilesDownloader.kt` — **real** streaming download of a `.pmtiles` pack with progress.
- `offline/StyleProvider.kt` — inlines the region's PMTiles path into the Apple-look style
  (`assets/style/apple_like_{dark,light}.json`), with a blank-background fallback when no region exists.
- `offline/OfflineMapView.kt` — **real** MapLibre GL map view (route line + location puck), shown by
  `MapScreen` automatically when the device goes offline (`Connectivity` drives the switch).
- `offline/ConnectivityRouteProvider.kt` — wired into `NavHub`: Apple routing online, Valhalla offline.
- `offline/ValhallaResponseMapper.kt` + polyline6 decoder — **real and unit-tested**: maps a Valhalla
  `/route` JSON (maneuver types, lanes, shape) into `core.Route`.
- `offline/ValhallaNative.kt` — JNI bridge that loads `libvalhalla_jni` defensively and degrades to
  "no offline route" when the `.so` is absent.

Because `GuidanceEngine` consumes any `RouteProvider`, turn-by-turn, off-route detection, and
rerouting already work offline the moment the native library returns routes — no engine changes.

## Remaining work

1. **Valhalla native library** (`native/valhalla/`): build `libvalhalla_jni.so` via the NDK exposing
   `nativeRoute(tilesPath, fromLat, fromLng, toLat, toLng, mode) -> JSON`, and a way to fetch/attach a
   region's routing-tile pack (`OfflineMapManager.attachRoutingTiles`). This is the one piece that
   needs the NDK toolchain; everything consuming its output is done and tested.
2. **Region-download UI**: a screen to pick a bounding box and a PMTiles URL and show progress
   (the manager/downloader APIs are ready).
3. **Labels offline** (optional): the shipped styles are geometry-only so they render without glyph
   packs; add local glyph PBFs + symbol layers for place labels offline.

Attribution: OSM data requires `© OpenStreetMap contributors` shown wherever the offline map renders
(the style sources already carry the attribution string).
