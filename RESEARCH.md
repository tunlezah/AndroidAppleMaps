# Apple Maps for Android — Feasibility Research

**Date:** 2026-08-16 · **Status:** Research complete, awaiting go/no-go decision before implementation.

This document synthesizes the findings of seven parallel research investigations (web architecture & licensing, offline feasibility, navigation, Look Around, iOS feature inventory, Android Auto, prior art) plus direct probing of the live `maps.apple.com` service.

---

## 1. Executive summary — the questions, answered

| Question | Verdict |
|---|---|
| Live Apple tiles on Android via maps.apple.com passthrough? | **Yes — works today.** maps.apple.com officially supports Android browsers (since April 2025) and serves its full client to Android user agents (verified empirically). A WebView wrapper is technically trivial. Licensing is the constraint, not technology. |
| Offline usage of the site? | **No — not possible with Apple tiles**, for three independent reasons (expiring signed tile URLs, proprietary VMP4 format, explicit ToS prohibition on caching). A hybrid offline mode built on open data (Protomaps + MapLibre + Valhalla) styled to look Apple-like **is** possible. |
| Turn-by-turn navigation on Android? | **Yes — feasible and, unusually, permitted by Apple's terms.** Apple's Directions API returns per-step instructions + full geometry; we build the guidance engine (map-matching, voice, rerouting) ourselves — well-trodden ground with strong open-source prior art. Constraint: Apple route data must be displayed on an Apple (MapKit JS) map. |
| The "binoculars" (Look Around)? | **Yes — via official channels.** Look Around shipped in MapKit JS at WWDC25 (`mapkit.LookAround`, `mapkit.LookAroundPreview`) and works in Android browsers. The reverse-engineered imagery pipeline exists but violates Apple's ToS and is rejected. |
| Convert iOS Apple Maps features? | **Partially.** Portable: map layers (standard/satellite/hybrid), search & autocomplete, rich place cards, Look Around, directions (drive/walk/cycle), geocoding, traffic-aware ETAs, static snapshots. iOS-only moat: offline maps, transit routing, live traffic layer, 3D/Flyover, EV routing, indoor maps, topo/trails, Share ETA, contributions. |
| Android Auto? | **Yes — but only with a native renderer and a non-Apple data path on the head unit.** A WebView cannot be made compliant on the car screen (map-only surface rule NF-2, no touch forwarding, manual car review). MapLibre-on-surface via VirtualDisplay+Presentation (~35 fps) fed by an open routing engine is the proven pattern. |

**Bottom line:** a credible "Apple Maps for Android" is buildable **today**: genuine Apple cartography, search, place cards, Look Around, and self-built turn-by-turn on the phone via a MapKit JS web app in a WebView; open-data fallback for offline; native MapLibre + Valhalla for Android Auto. The three hard boundaries are: no Apple tiles offline, no Apple data on non-Apple maps, and no WebView on the car screen.

---

## 2. Key facts established

### 2.1 maps.apple.com and MapKit JS on Android

- **Timeline:** MapKit JS beta June 2018 → `beta.maps.apple.com` July 2024 (Android explicitly unsupported) → **exited beta April 2025 with official Android mobile browser support** (Chrome, Firefox, Edge). Look Around added to the web app Dec 2024.
- **Verified empirically in this session:** `https://maps.apple.com/` returns HTTP 200 and its full web client (v1.7.378, "maps-app-web-client") to an Android 14 Chrome user agent. The config flag `showComingSoonMobileView:false` confirms the mobile gate is off. The unsupported-browser check in the shipped `shell.js` is a blocklist of ancient browsers only (IE, Firefox ≤36, Android ≤4.3) — a modern Android WebView passes.
- **Architecture:** the consumer site is a first-party client on **MapKit JS v6** (`mapkit.core.js`, build 26.31-66), WebGL vector rendering, tiles/CDN via `cdn.apple-mapkit.com` and the `*.ls.apple.com` backend family. The site self-issues its MapKit tokens — a WebView pointed at it needs no developer credentials.
- **No service worker, no PWA manifest** (verified) — the web product ships zero offline capability.
- **Web app gaps vs iOS:** no turn-by-turn navigation mode, no Apple Account sign-in (no favorites/sync), no transit map layer, no 3D/Flyover, portrait-constrained mobile layout.
- **MapKit JS (developer product):** map types, annotations/overlays, GeoJSON, search + autocomplete, geocoding, directions (drive/walk/cycling), ETAs, Place IDs + `PlaceDetail` rich place cards (photos/ratings/hours — Apple-rendered), **Look Around** (WWDC25), snapshots. Auth: ES256 JWT from an Apple Developer Program key ($99/yr). Free tier: **250,000 map views/day + 25,000 service calls/day** per team.
- **Unified Maps URLs (2025):** `https://maps.apple.com/{frame|search|place|look-around|directions|guides}?…` — deep-linkable from any platform.

### 2.2 Licensing (the load-bearing clauses)

From the Apple Maps Terms of Use (web) and the Apple Developer Program License Agreement, Attachment 6 (verbatim text verified from the current PDF):

1. **Maps ToU §1.1** grants use of the service "in any webpage or application… owned or controlled by You" — revocable at will; §1.3 forbids modification/injection, caching, scraping, reselling, or building a competing product; §2.3 grants **no trademark rights** (the app cannot be branded "Apple Maps").
2. **ADPLA Attachment 6 §1.2 explicitly contemplates MapKit JS "running on non-Apple hardware"**, prohibiting only four commercial verticals there (fleet management, asset tracking, enterprise route optimization, insurance-risk assessment). Apple's own marketing: MapKit JS embeds maps "across platforms and operating systems, including iOS and Android." → **MapKit JS in an Android WebView hosting our own web app is the licensed lane.**
3. **§2.4 display coupling:** Apple Map Data "will be displayed only on an Apple map provided through the Apple Maps Service." → **No Apple routes/search results rendered on MapLibre/Google/OSM tiles. Ever.**
4. **§2.2/§2.5:** no bulk download, scraping, or derived databases; no caching "other than on a temporary and limited basis" (performance-only). → **No offline Apple data.**
5. **Turn-by-turn is permitted.** Unlike Google, Apple's Program Requirements explicitly contemplate third-party "real-time navigation (including… turn-by-turn route guidance)" and require only a EULA notice: *"YOUR USE OF THIS REAL TIME ROUTE GUIDANCE APPLICATION IS AT YOUR SOLE RISK. LOCATION DATA MAY NOT BE ACCURATE."* Overlaying our own route content on an Apple map is also expressly allowed.
6. **Kill switch:** Apple may review any MapKit JS implementation and revoke keys "at any time in its sole discretion." All architecture choices below assume this risk.
7. **Google Play policy (independent):** wrapping a website you don't own without permission violates Play's WebView/minimum-functionality policy → a raw maps.apple.com wrapper is unpublishable on Play. A WebView hosting **our own** MapKit JS web app (our domain, our token) does not hit this rule.

### 2.3 Offline — why Apple-tile offline is impossible

Three independent blockers, each fatal alone:

1. **Auth design:** every tile URL carries an expiring signed `accessKey` + session `sid` + dataset version `v` that churns via a `geo_manifest` bootstrap. Keys can only be minted online; replayed URLs die (HTTP 410). `shouldInterceptRequest` caching cannot survive this (and can't see POST bodies anyway).
2. **Format:** tiles are Apple-proprietary **VMP4**, decodable only by Apple's own clients. No public decoder for the 2D basemap exists.
3. **Contract:** ADPLA §2.5 / ToU §1.3(xiii) prohibit caching/pre-fetching/storing map data beyond transient performance caching.

iOS 17+ native offline maps (region downloads incl. routing and search) runs through private GeoServices/VectorKit frameworks — no public surface, unreachable from Android.

**Viable offline strategy:** a separate offline mode on open data — **Protomaps PMTiles** regional packs rendered by **MapLibre GL** with a custom Apple-look style (a working Apple-dark-mode MapLibre style already exists as MIT-licensed prior art: `dddevid/MapLibre-GL-JS-AppleMaps-Style`), plus **Valhalla** (or GraphHopper) compiled via NDK for offline turn-by-turn, plus an offline geocoder. Honest fidelity note: Apple-ish look, not Apple data — different POI density, no satellite/Look Around offline. (Fastest alternative: embed the Organic Maps engine.)

### 2.4 Navigation — building the guidance engine

- **Apple Maps Server API** (`maps-api.apple.com`): `/v1/directions` returns routes (name, distance, duration, tolls flag, alternates) + `steps[]` (localized text instructions with road names, per-step distance/duration) + `stepPaths[]` (full per-step coordinate geometry). Transport: Automobile/Walking/Cycling (no transit routing). `/v1/etas` adds traffic-aware ETAs (incl. Transit). MapKit JS `mapkit.Directions` returns the same data plus a ready-made polyline overlay.
- **Not provided by anyone (even on iOS):** guidance sessions — voice, maneuver-type enums, lane guidance, speed limits, auto-reroute. We build that engine: GPS map-matching to `stepPaths`, distance-to-maneuver announcement bands via Android TTS with audio-focus ducking, off-route detection (perpendicular distance threshold), re-request on reroute, Android foreground service (`foregroundServiceType="location"`) pushing fixes into the WebView over a JS bridge.
- **Prior art to reuse:** **Ferrostar** (Rust core, Kotlin bindings, pluggable route provider — write an Apple adapter), **maplibre-navigation-android** (complete FOSS snap-to-route/off-route/voice-timing logic). Their guidance logic is data-source-agnostic; the on-screen map stays MapKit JS to satisfy §2.4.
- **Quota math:** a 45-min commute ≈ 30 service calls (1 directions + ~3 reroutes + ETA refresh every 2 min + search). 25,000 calls/day supports ~400 heavy daily users; a personal app uses <1% of quota. Design for graceful HTTP 429 degradation (keep guiding on the stale route).

### 2.5 Look Around ("binoculars")

- **Official path exists since WWDC25:** `mapkit.LookAround` (interactive, with dialog controls and event lifecycle) and `mapkit.LookAroundPreview` (static thumbnail → full-screen), driven by a `Place` object from Search/Geocoding/PlaceLookup. Works in Android browsers/WebView. Coverage ~82 regions (US, UK, JP, most of Western Europe, AU/NZ, …).
- **Reverse-engineered pipeline** (sk-zk's `streetlevel`/`lookaround-map`: coverage tiles at z17, 6 HEIC faces per pano in a non-standard projection, AES-signed requests using tokens extracted from Apple clients) is technically real but violates ToU §1.3 (scraping, RE, extracted credentials) and is operationally fragile. **Rejected for this project.**
- **Fallbacks where coverage is missing:** deep link `maps.apple.com/look-around?coordinate=…`, or open-licensed Mapillary imagery as a supplementary layer.

### 2.6 iOS feature conversion matrix (condensed)

**Portable (real Apple data/UX):** standard/satellite/hybrid layers · search + autocomplete · geocoding/reverse · Place IDs · rich place cards (via MapKit JS `PlaceDetail` — the only legal route to photos/ratings/hours) · Look Around · directions drive/walk/cycle with alternates & avoids · traffic-aware ETAs · static map snapshots (widgets/notifications).

**Partially portable (rebuild around Apple data):** turn-by-turn guidance (self-built engine; no lane guidance/speed limits) · multi-stop (chained legs) · favorites/library (local or own-cloud — no Apple sync exists to tap) · Guides (WebView browsing only) · weather/AQI chip (WeatherKit REST is a separate public Apple API) · natural-language search (own LLM in front of `/v1/search`) · Share ETA / parked car (own implementations).

**iOS-only (no public surface — out of scope):** offline Apple maps · transit routing & live departures (ETA only) · traffic layer & incident reporting · 3D cities/Flyover · EV routing · indoor maps · topo/hiking maps & custom routes · AR walking · ratings/photos submission · Visited Places/Preferred Routes · CarPlay-equivalent Apple data in car (blocked by §2.4).

### 2.7 Android Auto

- Car apps are **template-based** (`androidx.car.app`, category `NAVIGATION`): the host renders templates; nav apps additionally draw **map content only** onto a host-owned `Surface` (quality rule NF-2). Raw touch is never forwarded — only abstract pan/zoom/tap callbacks.
- **WebView verdict:** technically compositable via VirtualDisplay+Presentation, but non-compliant (web chrome on the surface violates NF-2; no usable input; manual Play car review would reject it; no WebView nav app has ever shipped). **Ruled out.**
- **Proven pattern:** MapLibre `MapView` in a VirtualDisplay+Presentation (~35 fps, public APIs — MapLibre AA sample + benchmarks), guidance data through `NavigationManager.updateTrip()` (Trip/Step/Maneuver/TravelEstimate), TBT notifications, voice via `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` audio focus, cluster session, `onAutoDriveEnabled` test mode.
- **Data path on the car must be non-Apple** (§2.4 forbids Apple data on the MapLibre map): Valhalla/GraphHopper routing over OSM, Apple-look MapLibre style. Handoff seam: destinations chosen in the phone's Apple UI pass as coordinates (user's own data), then re-routed by the open engine for the car.
- **Distribution:** Android Auto's "unknown sources" toggle does **not** apply to Car App Library apps; the sanctioned personal-use path is a **Play Internal Test Track / Internal App Sharing** build (bypasses car review, works on real head units). Public release requires passing the car-quality review (NF-1..9), which this architecture is compatible with.

### 2.8 Prior art & precedents

- **No sustained "Apple Maps for Android" has ever shipped.** Closest prior art: `IvanChanPing/applemaps-consumer-nav` (Aug 2026 — consumer-site WebView + JS bridge + Ferrostar guidance; unlicensed, tokenless, brand new).
- **DuckDuckGo** is the positive precedent: MapKit JS at web scale on all platforms since 2019, negotiated with Apple — proof Apple licenses its maps for third-party consumer products via the browser context.
- **Cautionary tales:** Google blocked maps.google.com for Windows Phone via UA check (a consumer-site wrapper lives one UA check from death); Google's C&D against Microsoft's YouTube client (altering a service's experience gets shut down). → Preserve Apple attribution/links untouched; prefer the licensed MapKit JS lane over the consumer-site wrapper.
- **Validated wedge feature:** a whole genre of Android apps exists just to redirect `maps.apple.com` links to Google Maps — native handling of Apple Maps links shared by iPhone users is real, unmet demand.
- Apple's official answer to "MapKit JS outside a browser" is "use native MapKit" (which doesn't exist on Android); DTS refuses to interpret ToS questions. Beyond hobby scale, the durable path is a negotiated arrangement (the DDG model).

---

## 3. Recommended architecture

**Two rendering stacks, three modes, one app** ("Maps for Android" working title — not "Apple Maps"):

```
┌──────────────────────────── Android app (Kotlin) ────────────────────────────┐
│                                                                              │
│  PHONE · ONLINE (primary)          PHONE · OFFLINE (fallback)                │
│  ┌──────────────────────────┐      ┌──────────────────────────┐              │
│  │ WebView (own domain)     │      │ MapLibre GL Native       │              │
│  │  └ Our MapKit JS web app │      │  └ Protomaps PMTiles     │              │
│  │     · Apple tiles (std/  │      │    regional packs        │              │
│  │       satellite/hybrid)  │      │  └ Apple-look style      │              │
│  │     · Search/Autocomplete│      │    (light + dark)        │              │
│  │     · PlaceDetail cards  │      │  └ Valhalla (NDK)        │              │
│  │     · Look Around        │      │    offline routing       │              │
│  │     · Directions + route │      │  └ Offline geocoder      │              │
│  │       overlay            │      └──────────────────────────┘              │
│  └──────────▲───────────────┘                                                │
│             │ JS bridge (evaluateJavascript / @JavascriptInterface)          │
│  ┌──────────┴───────────────────────────────────────────────┐                │
│  │ Native guidance engine (shared, Ferrostar-style)         │                │
│  │  · Foreground service + FusedLocationProvider @1Hz       │                │
│  │  · Snap-to-route on stepPaths · maneuver announcement    │                │
│  │    bands → Android TTS (audio-focus ducking)             │                │
│  │  · Off-route detect → re-request directions              │                │
│  │  · Online: Apple /v1/directions · Offline: Valhalla      │                │
│  └──────────┬───────────────────────────────────────────────┘                │
│  ┌──────────▼───────────────┐                                                │
│  │ ANDROID AUTO (CarAppService, category NAVIGATION)        │                │
│  │  · MapLibre on Surface via VirtualDisplay+Presentation   │                │
│  │  · Non-Apple data path only (Valhalla routes, OSM tiles) │                │
│  │  · NavigationTemplate + Trip/Step/TravelEstimate,        │                │
│  │    TBT notifications, cluster session, voice             │                │
│  └──────────────────────────┘                                                │
│                                                                              │
│  Backend (tiny): JWT signer (.p8 stays server-side) → /v1/token relay        │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Compliance rails baked in:** Apple data only ever on Apple (MapKit JS) maps; no Apple tile/data caching; Apple attribution/logo untouched; "sole risk" navigation EULA notice; no "Apple Maps" branding; 429-graceful quota handling.

## 4. Proposed roadmap

| Phase | Scope | Estimate |
|---|---|---|
| **1. Foundation** | Kotlin shell + hardened WebView; our MapKit JS web app (map, search/autocomplete, PlaceDetail cards, layers); token backend; `maps.apple.com` link-handling intent filters (the wedge feature); deep links via Unified Maps URLs | 2–3 weeks |
| **2. Binoculars** | `LookAroundPreview` on place cards + full-screen `LookAround`; coverage-aware UI; Mapillary/link-out fallback | ~1 week |
| **3. Navigation** | Guidance engine (Ferrostar-derived): foreground service, snap-to-route, TTS prompts, off-route reroute, ETA refresh; drive/walk/cycle | 3–5 weeks |
| **4. Offline mode** | MapLibre + PMTiles region downloads; Apple-look style (fork the MIT dark style, author light); Valhalla NDK routing; offline search; auto-switch on connectivity loss | 4–6 weeks |
| **5. Android Auto** | CarAppService + MapLibre-on-surface + templates/cluster/voice; internal-test-track distribution | 5–7 weeks |
| **6. Polish** | Favorites (local), snapshots widgets, WeatherKit chip, natural-language search layer | ongoing |

## 5. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Apple revokes MapKit JS keys / changes terms (sole-discretion kill switch) | High | Offline/open stack doubles as full fallback; keep the open-data path first-class |
| Play Store rejection (WebView policy, impersonation) | Medium | Own web app on own domain; no Apple branding; personal-use tiers via internal test track/sideload |
| MapKit JS-in-WebView bugs (historical pinch-zoom-to-(0,0) class) | Medium | Test on real Android System WebView early (Phase 1 gate); gesture workarounds |
| Quota ceiling at scale (25k service calls/day) | Low (personal) / High (public) | Quota-increase request; DDG-style negotiation if the project grows |
| Look Around/PlaceDetail are Apple-rendered UI (not restylable data) | Low | Accept Apple's UI inside those surfaces |
| Web app is portrait-constrained / no sign-in sync | Low | Native chrome around the WebView; local favorites |

## 6. Open decisions

1. **Distribution intent:** personal/sideload use, or aim for Google Play? (Changes branding caution, review hardening, and whether a quota-increase/BD conversation with Apple is needed.)
2. **Apple Developer Program:** the licensed MapKit JS lane requires a $99/yr membership + a small token-signing backend. Confirm willingness before Phase 1.
3. **Consumer-site wrapper vs own MapKit JS app:** research strongly favors building our own MapKit JS app (licensed, Play-compatible, stable). The consumer-site wrapper is quicker but unlicensed and UA-block-fragile — acceptable only as a personal prototype.
4. **Offline priority:** full Phase 4 stack, or defer offline to post-v1 (it's the largest single work item)?

---

## Appendix: primary sources

- Apple: [MapKit JS](https://developer.apple.com/documentation/mapkitjs/) · [Maps Server API](https://developer.apple.com/documentation/applemapsserverapi) · [/v1/directions](https://developer.apple.com/documentation/applemapsserverapi/-v1-directions) · [Maps on the Web](https://developer.apple.com/maps/web/) · [Unified Maps URLs](https://developer.apple.com/documentation/mapkit/unified-map-urls) · [WWDC25 "Go further with MapKit"](https://developer.apple.com/videos/play/wwdc2025/204/) · [WWDC22 "Meet Apple Maps Server APIs"](https://developer.apple.com/videos/play/wwdc2022/10006/) · [ADPLA (Attachment 6)](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/) · [Maps Terms of Use](https://www.apple.com/legal/internet-services/maps/terms-en.html) · [Supported browsers KB](https://support.apple.com/120585)
- Android: [Build a navigation app](https://developer.android.com/training/cars/apps/navigation) · [Draw maps](https://developer.android.com/training/cars/apps/library/draw-maps) · [Car app quality](https://developer.android.com/docs/quality-guidelines/car-app-quality) · [Testing/distribution](https://developer.android.com/training/cars/distribute)
- Open stack: [Ferrostar](https://github.com/stadiamaps/ferrostar) · [maplibre-navigation-android](https://github.com/maplibre/maplibre-navigation-android) · [MapLibre AA sample](https://github.com/maplibre/MapLibre-Android-Auto-Sample) + [benchmarks](https://helw.net/2025/11/16/maplibre-on-android-auto/) · [Protomaps](https://docs.protomaps.com/pmtiles/) · [Valhalla](https://github.com/valhalla/valhalla) · [Apple-look MapLibre style](https://github.com/dddevid/MapLibre-GL-JS-AppleMaps-Style)
- Prior art / precedents: [applemaps-consumer-nav](https://github.com/IvanChanPing/applemaps-consumer-nav) · [DuckDuckGo + MapKit JS](https://spreadprivacy.com/duckduckgo-apple-mapkit-js/) · [streetlevel (Look Around RE — rejected path)](https://github.com/sk-zk/streetlevel) · [flyover-reverse-engineering (archived)](https://github.com/retroplasma/flyover-reverse-engineering)
