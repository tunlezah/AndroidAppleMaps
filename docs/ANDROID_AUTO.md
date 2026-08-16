# Android Auto

This app implements the **navigation** category of the Android for Cars App Library
(`androidx.car.app`). The car UI is not the phone UI projected — the host renders templates, and
navigation apps additionally draw *map content only* onto a host-owned surface.

## How this project draws the car map

`car/CarSurfaceRenderer.kt` composites a **chromeless MapKit JS WebView** onto the car surface via a
`VirtualDisplay` + `Presentation` (the pattern Google documents for putting Views on the car surface).
The page (`assets/web/car.html` + `car.js`) initializes MapKit JS with the token captured by the phone
session and draws the map, the route polyline, and a location puck. Turn cards, ETA, and lane info come
from `NavigationTemplate` / `NavigationManager.updateTrip()` in `car/NavigationScreen.kt` — never from
the drawn surface (quality rule **NF-2**).

### Why WebView on the car is viable *here* but not in general

For a **public** release, streaming any web content to the car surface fails car review (NF-2, the
input model, and manual review). This project is **personal / internal-test-track**, which removes the
review gate, so the WebView approach is on the table. Two real risks remain, both handled in code:

1. **Token origin lock.** MapKit JS tokens are origin-locked to `apple.com`. The captured token may be
   rejected from the car page's `file://` origin. `car.js` reports this via `AndroidCar.onMapError`,
   and `CarSurfaceRenderer.renderWithMapLibreFallback()` is the stub where the native MapLibre renderer
   takes over (shared with the Phase 6 offline map).
2. **Performance.** WebGL-in-a-WebView-on-a-VirtualDisplay is unproven on head units. The MapLibre
   fallback (benchmarked at ~35 fps in the research) is the escape hatch if frame rate is poor.

If either bites, switch the renderer to MapLibre drawing the same route/puck onto the surface; the
guidance data path (`NavHub` → `NavigationScreen`) is unchanged.

## Running it

### Desktop Head Unit (development)
1. Install the debug APK on the phone.
2. Android Auto → tap "Version" 10× to enable Developer settings → enable **Unknown sources** and
   **Start head unit server**.
3. On your computer: `~/Android/Sdk/extras/google/auto/desktop-head-unit` (install the DHU via SDK
   Manager first). Connect the phone over USB.

### Real head unit (personal use)
Android Auto's "Unknown sources" toggle **does not** apply to Car App Library apps, so a plain sideload
will not appear in the car. Publish the app to a Play **Internal Test Track** (or use Internal App
Sharing) and add yourself as a tester — Google documents both as distributing without car review. A
one-time $25 Play developer account is required.

## Requirements implemented / still to do

Implemented: `NAVIGATION_TEMPLATES` + `ACCESS_SURFACE` permissions, `automotive_app_desc.xml`,
`CarAppService` with navigation category, `NavigationTemplate`, `NavigationManager` trip updates,
`onAutoDriveEnabled` hook, surface pan/zoom callbacks, dark map.

To do before a public release (not needed for personal use): full host allow-list (currently
allow-all in debug), cluster-display session (`FEATURE_CLUSTER`), navigation-intent handling
(`NF-6`), and hardening against the full NF-1..NF-9 checklist.
