# NEWS-dash

**Custom navigation for the Royal Enfield Tripper Dash with selectable map providers — Google Maps or Mappls (MapmyIndia).**

This is a personal fork of the open-source [NorthStar](https://github.com/prashkr24/NorthStar) project, adapted for a **Guerrilla 450** (Tripper firmware 3.9) and extended with an India-first map option. The long-term goal is an Android-Auto-like experience on the dash (card UI, message alerts) built on this same foundation.

> ⚠️ Independent, community project — **not affiliated with Royal Enfield**. The app only streams video to the Tripper **display** over Wi-Fi; it never touches the ECU, engine or brakes. Worst case: the dash doesn't show the stream and needs a power-cycle.

---

## What it does

The phone renders the map **off-screen**, hardware-encodes it to **H.264**, and streams it over the bike's own Wi-Fi (RE_* network) to the round Tripper TFT — phone screen stays **off** the whole ride. Turn-by-turn nav, rerouting, ETA, voice guidance, ride history and a maintenance garage all come from upstream NorthStar.

## What this fork adds

**Selectable dash map provider** — Settings → *Dash map*:

| | Google Maps (default) | Mappls (MapmyIndia) |
|---|---|---|
| Source | Google tile endpoint (keyless) | Mappls **Still Image API** (documented), used as a tile server |
| Strength | Best POI detail | Excellent Indian road data |
| Key | none | free REST key from [apis.mappls.com/console](https://apis.mappls.com/console/) |
| Max zoom | 20 | 18 (19–20 synthesized by upscaling) |

How the Mappls integration works:

- Every 256 px XYZ tile maps 1:1 to a `still_image` request centred on the tile's centre.
- **4-tiles-per-request batching:** one 512×512 image centred where four tiles meet is sliced into the 2×2 block — 4× fewer API hits, 4× faster coverage. Up to 3 requests in parallel. Automatic fallback to single-tile fetches if the API ever refuses 512 px.
- Per-provider disk caches (`tiles_gmaps_v1` / `tiles_mappls_v2`) — switching never mixes styles, and a tile is downloaded **once ever**. Set your destination on home Wi-Fi and the route corridor prefetches before you ride.
- Provider switch applies on the next rendered frame (TileProvider self-wires to Settings via a preference listener — no restart, no reconnect).
- Attribution for the active provider is drawn bottom-right of the dash frame (a Mappls T&C requirement).

Full change list: [`MAPPLS_CHANGES.md`](MAPPLS_CHANGES.md). Key files: `dash/map/TileSource.kt`, `dash/map/TileProvider.kt`, `data/MapPrefs.kt`, `ui/screens/MapProviderSection.kt`.

## Build & run

1. `git clone https://github.com/prashkr24/NEWS-dash.git`
2. Open the folder in **Android Studio**, let Gradle sync finish.
3. Phone: enable Developer options → **USB debugging**, plug in, press ▶.
4. In the app: **Settings → Dash map → Mappls** (a REST key is pre-filled for this private build; replace it via *Change REST key* if regenerated).
5. Connect to the bike's `RE_*` Wi-Fi and ride.

> 🔑 This repo is **private** because a personal Mappls key is baked into `data/MapPrefs.kt`. Strip `DEFAULT_MAPPLS_KEY` before making anything public.

## Roadmap

- **Joystick discovery mode** — Guerrilla firmware 3.9 doesn't respond to NorthStar's Himalayan-firmware button codes; a debug screen will capture raw packets on UDP :2002 and learn the mapping.
- **Card-based dash UI** — nav / media / message cards, AA-style, joystick-driven.
- **Message alerts** — notification cards in the video zone + short text in the dash's native gold banner (route-card title field).

## Credits

Built on [NorthStar](https://github.com/prashkr24/NorthStar) (dash protocol reverse-engineering lineage: [better-dash](https://github.com/prashkr24/better-dash) → NorthStar → [open-dash](https://github.com/prashkr24/open-dash)). Map data © Google / © Mappls | MapmyIndia per active provider.
