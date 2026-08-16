# Maps for Android

A personal Android client that brings the **Apple Maps** experience to Android: the live Apple map,
search, place cards, and Look Around ("binoculars") via the Apple Maps web stack, plus **native
turn-by-turn navigation**, **Android Auto**, and a planned **offline mode** built on top of it.

This is a personal-use project (sideloaded / internal-test-track). It is **not** affiliated with or
endorsed by Apple, is not branded "Apple Maps", and depends on Apple services that Apple can change or
withdraw at any time. See [Legal & fragility](#legal--fragility).

The research that shaped every decision here is in [`RESEARCH.md`](RESEARCH.md).

---

## What works and how

| Capability | Approach |
|---|---|
| **Live Apple map, search, place cards, Look Around** | `maps.apple.com` loaded directly in a WebView ("consumer wrapping"). Running in Apple's own origin is what makes the self-issued MapKit JS token valid — no Apple Developer Program key needed. |
| **Route data for navigation** | An injected script runs `mapkit.Directions` *inside the Apple page's origin* and returns steps + geometry to native code (`AppleMapsSession` + `assets/web/inject_consumer.js`). |
| **Turn-by-turn guidance** | A self-built engine (`nav/`): snap-to-route, speed-scaled voice prompts via Android TTS, off-route detection, and rerouting. Apple exposes route *data* but no guidance *session*, even on iOS — so we build the session. |
| **The "binoculars"** | Look Around via the consumer site's own UI / `look-around` deep links. |
| **Android Auto** | A `CarAppService` drawing the map to the car surface and driving `NavigationTemplate` from the shared guidance state (`car/`). |
| **Offline map** | Real: MapLibre GL renders a downloaded **Protomaps PMTiles** pack with an Apple-look style (`offline/OfflineMapView.kt`), shown automatically when the device goes offline. Region download is real (`PmTilesDownloader`). Apple tiles **cannot** be cached (expiring signed URLs, proprietary VMP4, ToS ban) — see `docs/OFFLINE.md`. |
| **Offline routing** | Connectivity-aware provider swaps Apple routing for on-device **Valhalla** when offline (`offline/`). The Valhalla response mapper + polyline decoder are implemented and unit-tested; the routing graph itself is native (`libvalhalla_jni`) — the one piece needing the NDK, see `docs/OFFLINE.md`. |
| **Apple Maps link handling** | Native handling of `maps.apple.com` links shared from iPhones (`links/AppleMapsLink.kt`) instead of bouncing them to Google Maps. |

### Architecture

One guidance stream feeds both the phone and the car:

```
maps.apple.com (WebView, Apple origin)
   │  inject_consumer.js  ── captures MapKit JS token, runs mapkit.Directions
   ▼
AppleMapsSession ──► DirectionsRepository ─┐
                                           ▼
LocationService (foreground, FusedLocation) ──► NavHub ──► GuidanceEngine ──► NavigationState
                                                                                  │
                                   ┌──────────────────────────────────────────────┤
                                   ▼                                              ▼
                          Phone: NavOverlay (Compose)                  Android Auto: NavigationScreen
                          over the Apple WebView                       (NavigationTemplate + car surface)
```

- `core/` — geometry (`GeoPoint`, local projection) and the `Route`/`RouteStep`/`ManeuverType` model.
- `web/` — the consumer-site WebView session, JS bridge, and token capture.
- `directions/` — bridge DTOs, maneuver inference from Apple's text-only instructions, route mapping.
- `location/` — foreground location service and the shared location repository.
- `nav/` — `GuidanceEngine`, `RouteMatcher` (map-matching), `Announcer` (TTS), and `NavHub` (the
  process-wide singleton shared by phone and car).
- `car/` — Android Auto service, session, navigation screen, and the surface renderer.
- `offline/` — Phase 6 scaffold (connectivity-aware provider, Valhalla stub, region manager).
- `links/` — Apple Maps URL parsing.

---

## Getting the APK (no local build needed)

Every push builds a **self-signed, sideloadable APK** via GitHub Actions
(`.github/workflows/android.yml`):

- Open the **Actions** tab → latest "Android CI" run → download the `maps-for-android-apk` artifact.
- Or push a `v*` tag (e.g. `git tag v0.1.0 && git push --tags`) to attach the APK to a GitHub Release.
- Install: `adb install maps-for-android.apk`, or open the file on the phone (enable "install unknown apps").

The APK is signed with the committed self-signed key in `keystore/sideload.jks` (password `sideload`).
This is deliberate for a personal project: a stable signature means updates install over each other. The
key is **not** for anything published to Google Play. `release` builds have minification off so the
sideload APK runs reliably.

## Building locally

Requirements: Android Studio (Ladybug+), JDK 17, Android SDK 35.

```bash
# CLI build (an Android SDK must be installed; point local.properties at it)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug        # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # runs the guidance/geometry/link unit tests
```

Install on a device: `adb install app/build/outputs/apk/debug/app-debug.apk`.

> This repository was scaffolded and compiled headless (Gradle 8.14.3, AGP 8.7.3, Kotlin 2.0.21):
> `:app:assembleDebug` and the unit suite pass. Everything involving a live device — the WebView
> token capture against the real `maps.apple.com`, GPS-driven guidance, and the Android Auto surface —
> must be validated on hardware / the Desktop Head Unit, since those depend on Apple's live service
> and real sensors.

---

## Android Auto

Sideloading a normal APK is **not** enough for a navigation car app — Android Auto's "unknown sources"
toggle does not apply to Car App Library apps. Use the Desktop Head Unit for development and a Play
**Internal Test Track** build for a real head unit (both bypass car review). Full instructions and the
WebView-on-surface caveats are in [`docs/ANDROID_AUTO.md`](docs/ANDROID_AUTO.md).

---

## Legal & fragility

- **Consumer wrapping is unlicensed and revocable.** We load Apple's consumer site and reuse the token
  it mints for itself. Apple can change the site, the token handshake, or block the client at any time;
  when that happens the app still shows Apple Maps in the WebView but the token-dependent extras
  (native routing data, the car page) may stop working. The offline stack is the deliberate fallback.
- **No caching of Apple map data.** We never store tiles or route data offline; offline mode uses
  open data (OpenStreetMap via Protomaps/Valhalla), which requires `© OpenStreetMap contributors`
  attribution.
- **Real-time guidance notice.** Apple's terms require route-guidance apps to display:
  *"YOUR USE OF THIS REAL TIME ROUTE GUIDANCE APPLICATION IS AT YOUR SOLE RISK. LOCATION DATA MAY NOT
  BE ACCURATE."* (`R.string.realtime_guidance_notice`).
- **Not "Apple Maps".** No Apple trademark or branding is used for the app identity.
