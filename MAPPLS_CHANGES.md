# Mappls map-provider fork — what changed vs upstream NorthStar

Everything else is untouched. All wiring is DONE — just build and run.

## New files
- app/src/main/java/com/example/northstar/dash/map/TileSource.kt
- app/src/main/java/com/example/northstar/data/MapPrefs.kt
- app/src/main/java/com/example/northstar/ui/screens/MapProviderSection.kt

## Modified files
- dash/map/TileProvider.kt — source-pluggable, per-source disk caches, self-wires to
  Settings via a SharedPreferences listener (switch takes effect on the next frame)
- dash/map/MapRenderer.kt — draws provider attribution bottom-right (Mappls T&C)
- ui/screens/SettingsScreen.kt — new "Dash map" section (Google / Mappls + key entry)

## Using Mappls
1. Free key: https://apis.mappls.com/console/ → dashboard → REST API key
2. App → Settings → Dash map → Mappls → paste key
3. Until a key is saved, the app silently keeps using Google so the dash never blanks.

## Mappls endpoint (UPDATED after 404 on the old XYZ URL)
Mappls tiles now come from their DOCUMENTED Still Image API — each 256×256 request
centred on a tile's centre is exactly that XYZ tile:
  https://apis.mapmyindia.com/advancedmaps/v1/<KEY>/still_image?center=<lat>,<lng>&zoom=<z>&size=256x256&ssf=0

- Zoom is capped at 18 by Mappls; the app synthesizes z19–20 by upscaling the z18
  parent, so nav zoom still works (slightly softer than Google at max zoom).
- Browser test (should show a small Bengaluru map image):
  https://apis.mapmyindia.com/advancedmaps/v1/b21986d499090bb09cec83078eb22f29/still_image?center=12.971600,77.594600&zoom=15&size=256x256&ssf=0
- If THIS also errors, the key may lack Still-Image access on the new console —
  check the key's enabled APIs in the Mappls dashboard, or send the error text back.

## Speed: 2×2 block fetching (added)
Mappls tiles are fetched 4-at-a-time: one 512×512 still image centred where four tiles
meet, sliced into four 256 px tiles, all cached. 4× fewer API hits + up to 3 requests in
parallel. If the API ever refuses size=512x512, the code automatically falls back to
single-tile fetches — nothing breaks.
Browser check for 512 support:
https://apis.mapmyindia.com/advancedmaps/v1/b21986d499090bb09cec83078eb22f29/still_image?center=12.971600,77.594600&zoom=15&size=512x512&ssf=0
