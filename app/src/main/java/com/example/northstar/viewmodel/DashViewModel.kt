package com.example.northstar.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.dash.DashKeepAliveService
import com.example.northstar.dash.DashSession
import com.example.northstar.dash.DashState
import com.example.northstar.dash.DashWifiManager
import com.example.northstar.dash.WifiConnStatus
import com.example.northstar.dash.map.LocationTracker
import com.example.northstar.dash.map.MapRenderer
import com.example.northstar.dash.map.Mercator
import com.example.northstar.dash.map.TileProvider
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.Route
import com.example.northstar.dash.nav.Router
import com.example.northstar.dash.protocol.DashCommands
import com.example.northstar.dash.video.DashEncoder
import com.example.northstar.dash.video.NalProcessor
import com.example.northstar.dash.video.RtpPacketizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }

/**
 * GPS health shown to the rider so a bad signal degrades *visibly* instead of the marker just
 * freezing. GOOD = fresh, accurate. WEAK = fresh but imprecise (or fixes coming slowly) → the
 * map eases gently toward truth, no jitter. LOST = no fresh fix for a few seconds → marker holds
 * its last known spot and the dash says so.
 */
enum class GpsStatus { GOOD, WEAK, LOST }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "12345678",  // RE Tripper factory passphrase; rider-overridable
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val retryAttempt: Int = 0,        // >0 while auto-retrying the flaky auth handshake
    val mapZoom: Int = 19,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val maneuver: String? = null,
    val hasGps: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.LOST,
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false, // off-route long enough / actively rerouting → ETA frozen, pill shows "Recalculating"
    val headingUp: Boolean = true,
    val tiltedView: Boolean = true,
    val followMode: Boolean = true,
    val thermal: String = "OK",
    val needsWifiOn: Boolean = false,   // Wi‑Fi radio is off → UI prompts to enable it
    // For the in-app Google Map view
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderBearing: Float = 0f,
    val destLatLng: Pair<Double, Double>? = null,
    val routePoints: List<GeoPoint> = emptyList(),
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(
        DashUiState(
            mapZoom = com.example.northstar.data.DashPrefs.zoom(app),
            tiltedView = com.example.northstar.data.DashPrefs.tiltedView(app),
        )
    )
    val ui = _ui.asStateFlow()

    // The EXACT frame being H.264-encoded to the dash, published for the in-app "Dash view"
    // so the phone preview is pixel-identical to the bike screen (same renderer, not a second
    // map engine). A fresh immutable snapshot per redraw — the encoder's own bitmap is mutated
    // on a background thread and recycled on teardown, so handing that out would tear/crash.
    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame = _previewFrame.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = com.example.northstar.dash.DashConfig.get(app)
    private val voice        = com.example.northstar.dash.nav.VoiceManager.get(app)
    private val repo         = com.example.northstar.data.SyncRepository.get(app)
    private val recorder     = com.example.northstar.data.RideRecorder()
    private var recordJob: Job? = null
    private val tiles       = TileProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)
    // EXPERIMENTAL: off-screen MapLibre 3D-buildings renderer, created on first use of the
    // MapLibre 3D dash-map source. Null until then; destroyed in teardown.
    @Volatile private var building3d: com.example.northstar.dash.map.Building3DRenderer? = null
    private fun ensure3dRenderer(w: Int, h: Int) {
        if (building3d == null) {
            building3d = com.example.northstar.dash.map.Building3DRenderer(getApplication(), w, h)
        }
    }
    private val mediaInfo   = com.example.northstar.media.MediaInfoProvider(app)
    private var mediaObserveJob: Job? = null
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null
    // Even presentation clock (ms) feeding the RTP 90 kHz timestamp — advances by exactly one
    // frame interval per encoded frame so the dash gets an evenly-spaced timeline (NOT wall clock,
    // which inherited the loop's jitter). Paired with absolute-grid send pacing below.
    @Volatile private var videoPtsMs = 0L
    private var sendPaceMs = 0.0    // EMA of the real frame-send interval — exposes cadence jitter
    private var sendPaceMaxMs = 0L  // worst interval since the last diag line
    // Backstop so a dropped/never-reached connection can't drain the battery forever: if we don't
    // reach STREAMING within this window, give up completely (stop the Wi‑Fi reconnect loop, the
    // foreground service, GPS and the encoder). Cancelled the moment streaming starts.
    private var giveupJob: Job? = null
    private var connLostAlertJob: Job? = null
    private var connLostAlerted = false

    private var userWantsConnection = false
    private var authAttempts = 0       // bounded auto-retry of the (flaky) auth handshake
    @Volatile private var everStreamed = false
    @Volatile private var isBackgroundAttempt = false

    // ── Navigation/map state read by the 4 fps frame loop ──
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var route: Route? = null
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var zoom = com.example.northstar.data.DashPrefs.zoom(app)
    // Next-maneuver + route-progress snapshot for the dash frame, updated by trackProgress.
    @Volatile private var lastNextManeuver: com.example.northstar.dash.nav.Maneuver? = null
    @Volatile private var lastNextTurnM: Double = Double.MAX_VALUE
    @Volatile private var lastRouteSplitIdx: Int = 0
    @Volatile private var lastRouteSnapPt: GeoPoint? = null
    @Volatile private var headingUp = true
    @Volatile private var tiltedView = com.example.northstar.data.DashPrefs.tiltedView(app)
    @Volatile private var followMode = true
    @Volatile private var lastManualPanAt = 0L

    // Smoothed camera — eased toward the latest GPS target every frame so the map
    // glides at 8 fps instead of jumping once per 1 Hz fix (the "cheap"/laggy feel).
    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false
    private var lastTickNs = 0L          // for framerate-independent smoothing

    // Dead-reckoning: last GPS fix + its velocity, so the camera keeps gliding forward
    // between the 1 Hz fixes instead of easing to a stop (the "laggy" feel at speed).
    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixBearing = 0f
    private var fixSpeed = 0f             // m/s
    private var fixWallMs = 0L
    private var lastFixTime = 0L
    private var gpsFixIntervalMs = 0.0   // EMA of the gap between fixes — exposes screen-off GPS throttling

    // GPS health surfaced to the dash + phone (computed each tick from accuracy + fix age).
    @Volatile private var gpsStatus = GpsStatus.LOST
    // Held marker position while stationary — deadbands sub-accuracy GPS wander so a parked bike's
    // dot doesn't drift/jitter. Updated to the live fix while moving; reset on teardown.
    private var heldRider: Pair<Double, Double>? = null

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null

    // Stable ETA (Google-like): the raw estimate jitters with instantaneous speed, so we
    // smooth it and only recompute the absolute arrival clock occasionally — no per-frame churn.
    private var smoothEtaSec = 0.0
    @Volatile private var etaArrivalMs = 0L
    private var lastArrivalCalcMs = 0L
    // The minutes-remaining shown to the rider, refreshed on the same 5 s cadence as the arrival
    // clock so it stops ticking/bouncing every second ("tacky ETA"). null = no active ETA.
    @Volatile private var displayedEtaMin: Int? = null

    // Off-route → reroute, debounced. Now feasible because the app keeps cellular
    // internet while bound to the dash (per-socket binding), so Router can run mid-ride.
    @Volatile private var offRouteSince = 0L
    @Volatile private var lastRerouteAt = 0L
    @Volatile private var rerouting = false
    // Debounce for the "Recalculating" state (ignore 1-frame off-route GPS blips).
    @Volatile private var offRouteStreakStart = 0L
    // Keep "Recalculating" on screen a beat after it clears, so a fast reroute is still seen.
    @Volatile private var recalcHoldUntil = 0L
    // Last lateral distance from the route (m) — logged so flyover/off-route thresholds are tunable.
    @Volatile private var lastSnapDist = -1.0
    // Rolling average of actual moving speed (m/s), held through stops — drives a STEADY ETA
    // instead of one that swings with instantaneous speed at every brake/light.
    private var smoothSpeedMps = 0.0

    // Map-matched rider position: snapped onto the route while on it (kills the GPS
    // lane/road jitter), raw GPS when genuinely off-route. Drives the marker + camera.
    @Volatile private var matchedLat: Double? = null
    @Volatile private var matchedLng: Double? = null

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    private var lastRedrawAt = 0L

    // Ride diagnostics: one-shot "first frame" marker + a throttle for the periodic ride line.
    @Volatile private var loggedFirstFrame = false
    private var lastDiagMs = 0L

    companion object {
        private const val MANUAL_IDLE_MS = 8_000L
        private const val FORCE_REDRAW_MS = 2_000L
        // How long to keep trying to (re)connect before giving up and freeing all background
        // resources. Covers the flaky initial handshake AND a mid-ride drop; if the dash is truly
        // gone (parked / powered off / out of range) we stop draining instead of retrying forever.
        // 2 min (was 45 s): if the dash AP hangs mid-ride it only comes back after a bike power-cycle,
        // and 45 s wasn't enough to safely pull over + restart before Northstar gave up. With the
        // WiFi layer now retrying through an AP-missing window, this keeps trying long enough that a
        // power-cycle auto-rejoins with no manual step. Still bounded so a truly-gone dash frees power.
        private const val RECONNECT_GIVEUP_MS = 120_000L
        // Shorter give-up window (42 s) when triggered headlessly from background so a brief pass near
        // the bike never holds resources for 2 minutes while leaving room for Tripper boot + DHCP + auth.
        private const val BG_RECONNECT_GIVEUP_MS = 42_000L
        // Alert the rider after this long of sustained (non-transient) link loss — long enough to skip
        // a brief blip that auto-recovers, short enough that they're not riding blind for minutes.
        private const val CONN_LOST_ALERT_MS = 12_000L
        private const val SMOOTH_TAU = 0.20      // camera smoothing time constant (s) — tighter so the map trails the bike less
        // GPS bearing is only meaningful while genuinely moving; below this it's noise that
        // would spin the heading-up map (the "turns to the opposite direction when I stop" bug).
        private const val BEARING_MIN_SPEED = 2.0f   // m/s (~7 km/h)
        // Lead the displayed position by the pipeline latency (camera ease + H.264 encode + RTP +
        // dash decode/display ≈ 0.5 s) so the dash shows where the bike IS, not where it was.
        private const val DISPLAY_LEAD_S = 0.4
        // Cap on how far ahead the constant-velocity predictor projects (s of travel). Bounds the
        // over-shoot if a fix is missed across a real stop / a long dropout; below this it tracks
        // the rider's actual motion so the camera never freezes between sparse (screen-off) fixes.
        private const val MAX_PREDICT_S = 3.0
        // Motion-adaptive frame cadence — the heart of the RE-style power/stability model.
        // Measuringthe dash showed it projects the map at just 2–4 fps with two
        // profiles (q2g: 4 fps/200 kbps "frameToSend"=250 ms, and 2 fps/100 kbps =500 ms) and switches
        // between them. RE gates on battery; we gate on MOTION instead (smarter for our screen-off
        // power goal, and the user explicitly didn't want a battery gate): stream the fast profile
        // while the map is actually changing (driving / panning / zooming), drop to the slow profile
        // when it's static. A few fps is plenty for a map AND never overruns the dash decoder — which
        // is what caused the blink/stutter at our old fixed 24 fps. ACTIVE is the knob to raise if
        // joystick panning feels choppy (at the cost of more decoder load).
        // ACTIVE was 250 ms (4 fps, matched) but that 250 ms quantum IS the ~0.25 s position lag
        // ("catch-up") felt while moving. Raised to 100 ms (10 fps) — comfortably under the 12 fps the
        // field log proved stable on this dash, so position tracks ~2.5× tighter with no overrun risk.
        // IDLE stays low: a static map has no lag to fix and 2 fps saves power.
        // Back to 4 fps to MATCH the dash exactly (measured at ~3.7 fps). The
        // 12 fps experiment was smoother but ~3× the 2.4 GHz WiFi load, which stresses the dash AP and
        // its BT coexistence; 4 fps is the proven-stable envelope the dash decoder never overruns.
        private const val FRAME_MS_ACTIVE = 250L   // 4 fps while moving — matched
        private const val FRAME_MS_IDLE   = 500L   // 2 fps when static — RE low profile
        // Stay on the fast profile this long after the last visible change, so brief pauses (e.g.
        // between joystick nudges, or a GPS fix gap) don't flap the cadence back and forth.
        private const val MOTION_HOLD_MS  = 1_500L
        private const val MAX_AUTH_ATTEMPTS = 4   // the fw 11.63 handshake often needs a couple of tries
        // MAP-mode joystick codes (the dash forwards these directly while projecting, no media-control
        // mode needed) — confirmed in the ride log: 0x13/0x14 with callRinging=false during the map.
        // These give DIRECT zoom like the dash. In/out guess; swap if the ride feels wrong.
        private const val BTN_MAP_ZOOM_IN  = 0x14
        private const val BTN_MAP_ZOOM_OUT = 0x13
        // MEDIA-mode codes (after pressing IN to enter the dash's audio controls): L/R skip tracks on
        // the phone's player, NOT map zoom. Map zoom stays on the direct map-mode codes above.
        private const val BTN_MEDIA_NEXT = 0x09   // media-mode RIGHT → next track
        private const val BTN_MEDIA_PREV = 0x0A   // media-mode LEFT  → previous track

        @Volatile private var instance: DashViewModel? = null
        fun getInstance(context: android.content.Context): DashViewModel =
            instance ?: synchronized(this) {
                instance ?: DashViewModel(context.applicationContext as Application).also { instance = it }
            }
    }

    /** Project a lat/lng forward [distM] metres along [bearingDeg] (great-circle). */
    private fun project(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val br = Math.toRadians(bearingDeg)
        val dr = distM / r
        val lat1 = Math.toRadians(lat); val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(br))
        val lng2 = lng1 + Math.atan2(
            Math.sin(br) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }

    /**
     * Distance (m) to dead-reckon ahead of the last GPS fix, given the time since it arrived.
     *
     * CONSTANT velocity (distance = speed × time), capped at [MAX_PREDICT_S]. The old version
     * tapered off after ~1 s, so the camera DECELERATED to a near-stop between fixes and then
     * jumped when the next one landed — the "stuck then teleport" feel, especially with the screen
     * off when GPS callbacks slow to multi-second gaps. Projecting at the true speed keeps the
     * camera (and the constant [DISPLAY_LEAD_S] lead) moving smoothly through the gap, so each new
     * fix lands where the camera already is — no jump. The cap bounds over-shoot if the rider
     * stops/turns mid-gap; the next fix (with speed ~0 → predictor off) then eases it back.
     */
    private fun predictedDistance(speedMps: Float, elapsedSec: Double): Double =
        speedMps * elapsedSec.coerceIn(0.0, MAX_PREDICT_S)

    init {
        if (instance == null) instance = this
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.update { it.copy(ssid = dashConfig.ssid, wifiPassword = dashConfig.password) }

        // When we connect to a previously-unknown dash by prefix, learn + persist its exact
        // SSID so subsequent connects target it directly (no system picker again).
        wifiManager.onSsidResolved = { learned ->
            val changed = learned != dashConfig.ssid
            dashConfig.ssid = learned
            session.updateSsid(learned)
            _ui.update { it.copy(ssid = learned) }
            if (changed) {
                com.example.northstar.dash.FastReconnectManager.sync(getApplication())
            }
        }

        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                com.example.northstar.data.RideDiagnostics.log(
                    "wifi",
                    ws.status.toString() +
                        (ws.ssid.takeIf { it.isNotBlank() }?.let { " ssid=$it" } ?: "") +
                        (ws.error?.let { " err=$it" } ?: ""),
                )
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        val targetSsid = ws.ssid.takeIf { it.isNotBlank() && !it.endsWith("_") }
                            ?: dashConfig.ssid.takeIf { it.isNotBlank() }
                            ?: _ui.value.ssid
                        if (targetSsid.isNotBlank() && targetSsid != _ui.value.ssid) {
                            _ui.update { it.copy(ssid = targetSsid) }
                        }
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(if (isBackgroundAttempt) 600L else 1_000L)
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) {
                                val finalSsid = _ui.value.ssid.ifBlank { dashConfig.ssid }
                                session.connect(finalSsid, wifiManager.network)
                            }
                        }
                    }
                    WifiConnStatus.ERROR -> {
                        val hadStreamed = everStreamed
                        // Dash is gone for good (bike powered off / out of range) → the ride is
                        // over. Save it even though the user never tapped Disconnect.
                        saveParkedBikeIfStreamed("Auto-pinned when bike powered off")
                        stopRecording()
                        // Only impose the post-ride session cooldown if we actually streamed a ride;
                        // a pre-stream failure while the dash AP is still booting should stay retryable!
                        if (hadStreamed) {
                            com.example.northstar.dash.FastReconnectManager.notifySessionEnded()
                        } else {
                            com.example.northstar.dash.FastReconnectManager.releasePocketWakeLock()
                        }
                        // …and release everything connect() acquired (wake/wifi locks, GPS,
                        // encoder loop). These used to leak when a connection ENDED on failure
                        // rather than via the Disconnect button, draining the battery in the
                        // background until the app was force-closed.
                        userWantsConnection = false
                        releaseBackgroundResources()
                        session.disconnect()   // wifi status stays ERROR → stage remains ERROR
                        _ui.update { it.copy(errorMessage = ws.error) }; refreshStage()
                        com.example.northstar.data.RideDiagnostics.stop("wifi error / dash gone")
                    }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                com.example.northstar.data.RideDiagnostics.log("session", "→ $state")
                refreshStage()
                // Battery backstop: count down to a full give-up unless/until we're actually
                // streaming. Reaching STREAMING cancels it; any non-streaming state (re)arms it.
                if (state == DashState.STREAMING) { cancelReconnectGiveup(); cancelConnLostAlert() }
                else if (userWantsConnection) { armReconnectGiveup(); armConnLostAlert() }
                when (state) {
                    DashState.READY -> {
                        authAttempts = 0
                        _ui.update { it.copy(retryAttempt = 0) }
                        // If we connected headlessly in the background, we deferred starting GPS
                        // and RideRecorder to save battery until the Dash handshake actually succeeded.
                        location.start()
                        startRecording()
                        startStream()
                    }
                    DashState.STREAMING -> {
                        everStreamed = true
                        isBackgroundAttempt = false   // promoted to full active ride session
                        authAttempts = 0; _ui.update { it.copy(retryAttempt = 0, errorMessage = null) }
                        com.example.northstar.dash.FastReconnectManager.notifySessionStarted()
                        DashKeepAliveService.updateStatus(
                            getApplication(),
                            "WayDash — live on Tripper Dash ✓",
                            "Streaming map · Phone screen can stay off",
                        )
                        // Usage: the dash actually started showing the map — the core feature working.
                        com.example.northstar.util.Telemetry.logEvent("dash_streaming")
                    }
                    // The handshake to fw 11.63 is flaky — a single failed attempt is normal.
                    // Auto-retry (WiFi is already up) instead of making the rider re-tap Connect.
                    DashState.ERROR -> {
                        if (userWantsConnection &&
                            wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                            authAttempts < MAX_AUTH_ATTEMPTS
                        ) {
                            authAttempts++
                            _ui.update { it.copy(retryAttempt = authAttempts, errorMessage = null) }
                            delay(1_500)
                            if (userWantsConnection && wifiManager.state.value.status == WifiConnStatus.CONNECTED) {
                                val finalSsid = _ui.value.ssid.ifBlank { dashConfig.ssid }
                                session.connect(finalSsid, wifiManager.network)
                            }
                        } else {
                            _ui.update { it.copy(retryAttempt = 0) }
                            // Retries exhausted while the WiFi link is still up → give up fully now
                            // (don't wait out the timer). giveUp() also stops the Wi‑Fi reconnect loop.
                            if (userWantsConnection && authAttempts >= MAX_AUTH_ATTEMPTS &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) {
                                giveUp("auth exhausted after $authAttempts attempts")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        session.onError = { msg ->
            com.example.northstar.data.RideDiagnostics.log("error", msg)
            _ui.update { it.copy(errorMessage = msg) }; refreshStage()
        }
        // Joystick handling for map zoom and media skip (calls are display-only on the dash).
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val label = when (code) {
                BTN_MAP_ZOOM_IN  -> { zoomIn();  "Zoom in (map)" }
                BTN_MAP_ZOOM_OUT -> { zoomOut(); "Zoom out (map)" }
                BTN_MEDIA_NEXT   -> { mediaInfo.skipNext(); "Next track" }
                BTN_MEDIA_PREV   -> { mediaInfo.skipPrevious(); "Previous track" }
                else -> "code 0x${code.toString(16).uppercase()}"
            }
            com.example.northstar.data.RideDiagnostics.log("button",
                "0x%02X → %s".format(code, label))
            _ui.update { it.copy(lastButton = label) }
        }
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING -> ConnStage.STREAMING
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING || dash == DashState.READY -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING || wifi == WifiConnStatus.CONNECTED -> ConnStage.WIFI
            else -> ConnStage.OFFLINE
        }
        _ui.update { it.copy(stage = stage) }
    }

    // ── Connection ─────────────────────────────────────────────────────────

    /**
     * Nudges [DashWifiManager] when an external signal (`POST_CONNECTION` broadcast or
     * [com.example.northstar.dash.FastReconnectManager] callback) sees the bike's `RE_*` Wi-Fi
     * while we are already waiting in [ConnStage.WIFI].
     */
    fun nudgeBackgroundWifiCheck() {
        if (userWantsConnection && _ui.value.stage == ConnStage.WIFI) {
            wifiManager.recheckActiveNetworks()
        }
    }

    /**
     * Headless auto-connect entry point called by [com.example.northstar.dash.FastReconnectManager]
     * or [com.example.northstar.dash.DashAutoConnectReceiver] when the bike's RE_* Wi-Fi hotspot
     * appears in range or connects automatically (Wireless Android Auto style).
     */
    fun autoConnectFromBackground() {
        val app = getApplication<Application>()
        if (!com.example.northstar.data.DashPrefs.wirelessAutoConnect(app)) return
        val stage = _ui.value.stage
        if (stage == ConnStage.WIFI) {
            wifiManager.recheckActiveNetworks()
            return
        }
        if (userWantsConnection || stage == ConnStage.STREAMING || stage == ConnStage.AUTH) return
        if (!wifiManager.isWifiEnabled()) return
        val hasFineLoc = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarseLoc = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasNearbyWifi = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFineLoc && !hasCoarseLoc && !hasNearbyWifi && dashConfig.ssid.isBlank()) return
        android.util.Log.i("DashViewModel", "autoConnectFromBackground → starting headless dash connection")
        viewModelScope.launch {
            if (!userWantsConnection) {
                connect(fromBackground = true)
            } else if (_ui.value.stage == ConnStage.WIFI) {
                wifiManager.recheckActiveNetworks()
            }
        }
    }

    private fun saveParkedBikeIfStreamed(note: String = "Auto-pinned on Dash disconnect") {
        if (!everStreamed) return
        everStreamed = false
        val loc = location.lastKnown() ?: return
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        com.example.northstar.data.BikeTrackerManager.get(getApplication())
            .recordParking(loc.latitude, loc.longitude, note)
    }

    fun connect(fromBackground: Boolean = false) {
        userWantsConnection = true
        isBackgroundAttempt = fromBackground
        authAttempts = 0
        everStreamed = false
        _ui.update { it.copy(errorMessage = null, retryAttempt = 0, needsWifiOn = false) }

        // The dash link needs the Wi‑Fi radio on (WifiNetworkSpecifier joins it as a separate
        // per-app network — the phone's current Wi‑Fi/cellular is untouched). Android 10+ won't
        // let us enable Wi‑Fi for the user, so if it's off, surface it immediately and let the UI
        // open the Wi‑Fi panel — far better than a silent 30s timeout.
        if (!wifiManager.isWifiEnabled()) {
            userWantsConnection = false
            _ui.update { it.copy(stage = ConnStage.ERROR, needsWifiOn = true,
                errorMessage = "Turn on Wi‑Fi to connect to the dash") }
            return
        }
        com.example.northstar.data.RideDiagnostics.init(getApplication())
        com.example.northstar.data.RideDiagnostics.start(if (fromBackground) "bg-connect" else "connect")
        // Stamp the running build's identity into the log so a pulled ride is matched 1:1 to the
        // pushed APK — never again analyse a ride and not know which build produced it.
        com.example.northstar.data.RideDiagnostics.log(
            "build", "apk=${com.example.northstar.util.BuildId.sha12(getApplication())}" +
                " vc=${com.example.northstar.BuildConfig.VERSION_CODE} name=${com.example.northstar.BuildConfig.VERSION_NAME}",
        )
        com.example.northstar.data.RideDiagnostics.log(
            "connect", "ssid='${dashConfig.ssid}' bg=$fromBackground needsDiscovery=${dashConfig.needsDiscovery} dest=${_ui.value.destinationName}",
        )
        loggedFirstFrame = false
        DashKeepAliveService.start(getApplication(), fromBackground = fromBackground)
        // Battery optimization: when triggered headlessly from background, do NOT start high-frequency
        // GPS or RideRecorder until the Dash auth handshake actually succeeds (DashState.READY).
        if (!fromBackground) {
            location.start()
            startRecording()
        }
        armReconnectGiveup()

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix, triggerActiveScan = !fromBackground)?.let { found ->
                dashConfig.ssid = found
                _ui.update { it.copy(ssid = found) }
                com.example.northstar.dash.FastReconnectManager.sync(getApplication())
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                session.connect(_ui.value.ssid.ifBlank { dashConfig.ssid }, wifiManager.network)
            // Known SSID (stored or just found by scan) → exact connect + correct auth.
            dashConfig.ssid.isNotBlank() ->
                wifiManager.connect(dashConfig.ssid, dashConfig.password, fromBackground = fromBackground)
            // Couldn't find it in scan results — fall back to prefix discovery so we at
            // least associate (auth may still need the SSID; a rescan usually fixes it).
            else ->
                wifiManager.connect(
                    dashConfig.ssidPrefix,
                    dashConfig.password,
                    prefixMatch = true,
                    fromBackground = fromBackground,
                )
        }
    }

    fun disconnect() {
        userWantsConnection = false
        isBackgroundAttempt = false
        cancelReconnectGiveup()
        com.example.northstar.dash.FastReconnectManager.notifyManualDisconnect()
        com.example.northstar.dash.FastReconnectManager.notifySessionEnded()
        com.example.northstar.data.RideDiagnostics.log("connect", "user disconnect")
        saveParkedBikeIfStreamed("Auto-pinned on Dash disconnect")
        stopRecording()        // a connect→disconnect session = one saved ride
        session.disconnect()
        wifiManager.disconnect()
        releaseBackgroundResources()
        refreshStage()
        com.example.northstar.data.RideDiagnostics.stop("disconnect")
        // Ride just ended and internet is back (dash WiFi released) — flush the log to Firestore
        // now so it's retrievable even if the app isn't reopened. Test-channel + Firebase-gated.
        com.example.northstar.data.DiagnosticsUploader.uploadPending(getApplication())
    }

    /**
     * Release everything that keeps the CPU/GPS/WiFi awake: the foreground service
     * (PARTIAL_WAKE_LOCK + WifiLock + MulticastLock), GPS updates, and the encoder/stream loop. Called
     * whenever the connection has ended so the app stops draining the battery in the
     * background — on an explicit disconnect, on a terminal connection failure (dash
     * unreachable / handshake exhausted), and when the ViewModel is cleared.
     */
    private fun releaseBackgroundResources() {
        teardown()                                   // stream loop + encoder + frame bitmap
        location.stop()                              // stop GPS updates
        DashKeepAliveService.stop(getApplication())  // release wake + wifi + multicast locks
    }

    /**
     * Complete stop of all background work — including the Wi‑Fi manager's reconnect loop, which
     * [releaseBackgroundResources] alone does NOT stop (that loop re-joins the dash AP every few
     * seconds and was the main source of drain after the dash went away). The session stays in
     * ERROR so the Dash screen still shows "Couldn't connect" + Try again.
     */
    private fun giveUp(reason: String) {
        val hadStreamed = everStreamed
        cancelReconnectGiveup()
        userWantsConnection = false
        isBackgroundAttempt = false
        if (hadStreamed) {
            com.example.northstar.dash.FastReconnectManager.notifySessionEnded()
        } else {
            com.example.northstar.dash.FastReconnectManager.releasePocketWakeLock()
        }
        saveParkedBikeIfStreamed("Auto-pinned when Dash went out of range")
        stopRecording()
        session.disconnect()
        wifiManager.disconnect()       // stops the indefinite AP reconnect loop
        releaseBackgroundResources()
        refreshStage()
        com.example.northstar.data.RideDiagnostics.log("error", "gave up: $reason — background released")
        com.example.northstar.data.RideDiagnostics.stop(reason)
    }

    /** Start the give-up countdown if it isn't already running (idempotent). */
    private fun armReconnectGiveup() {
        if (giveupJob?.isActive == true) return
        val timeoutMs = if (isBackgroundAttempt) BG_RECONNECT_GIVEUP_MS else RECONNECT_GIVEUP_MS
        giveupJob = viewModelScope.launch {
            delay(timeoutMs)
            if (session.state.value != DashState.STREAMING) giveUp("reconnect window elapsed (${timeoutMs / 1000}s)")
        }
    }

    private fun cancelReconnectGiveup() { giveupJob?.cancel(); giveupJob = null }

    // Non-silent disconnect alert: this dash-AP-hang failure only recovers after the rider
    // power-cycles the bike, so they must KNOW the link dropped. Fire once after a sustained loss
    // (not a transient blip) via vibration + a BT-routed voice cue, so it carries through the helmet
    // with the phone screen off. Re-armed each non-streaming state; cancelled (and reset) on recovery.
    private fun armConnLostAlert() {
        if (connLostAlertJob?.isActive == true || connLostAlerted) return
        connLostAlertJob = viewModelScope.launch {
            delay(CONN_LOST_ALERT_MS)
            if (session.state.value != DashState.STREAMING && userWantsConnection) {
                connLostAlerted = true
                com.example.northstar.data.RideDiagnostics.log("connect", "sustained link loss → rider alert (vibrate + voice)")
                runCatching {
                    val v = getApplication<Application>().getSystemService(android.os.Vibrator::class.java)
                    v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1))
                }
                voice.alert("Dash disconnected. Restart the dash to reconnect.")
            }
        }
    }

    private fun cancelConnLostAlert() {
        connLostAlertJob?.cancel(); connLostAlertJob = null
        if (connLostAlerted) {
            connLostAlerted = false
            voice.alert("Dash reconnected")
        }
    }

    // ── Ride recording (the connected session) ───────────────────────────────
    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start()
        recordJob = viewModelScope.launch {
            location.location.collect { loc ->
                if (loc != null) recorder.add(loc.latitude, loc.longitude, loc.speed, loc.accuracy, loc.time)
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        if (!recorder.isRecording) return
        val ride = recorder.stop() ?: return   // null = trivial session, don't save
        repo.addRide(ride)   // self-scoped on the repo (survives ViewModel teardown)
        android.util.Log.i("DashViewModel", "Ride saved: ${"%.1f".format(ride.distanceKm)} km, ${ride.durationSec}s")
    }

    // ── Dash WiFi config (Settings) ──────────────────────────────────────────
    fun setSsid(s: String) {
        dashConfig.ssid = s.trim()
        _ui.update { it.copy(ssid = s.trim()) }
        com.example.northstar.dash.FastReconnectManager.sync(getApplication(), forceReset = true)
    }
    fun setWifiPassword(p: String) {
        dashConfig.password = p
        _ui.update { it.copy(wifiPassword = p) }
        com.example.northstar.dash.FastReconnectManager.sync(getApplication(), forceReset = true)
    }
    /** Forget the paired dash so the next connect rediscovers any RE_* dash by prefix. */
    fun forgetDash() {
        dashConfig.forgetDash()
        _ui.update { it.copy(ssid = "") }
        com.example.northstar.dash.FastReconnectManager.sync(getApplication(), forceReset = true)
    }

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestination(name: String, lat: Double?, lng: Double?) {
        _ui.update { it.copy(
            destinationName = name, hasRoute = false,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = emptyList(),
        ) }
        destLat = lat
        destLng = lng
        route = null
        progressM = 0.0
        lastRouteSplitIdx = 0; lastRouteSnapPt = null; lastNextManeuver = null
        smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null   // fresh ETA for the new route
        voice.resetTrip()   // fresh announcements for the new route
        session.updateRouteCard(name)
        if (lat != null && lng != null) {
            val loc = location.lastKnown()
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
            fetchRoute(lat, lng)
        }
    }

    /** Drop the destination/route → free roam. The map keeps streaming and follows the rider. */
    fun exitNavigation() {
        destLat = null
        destLng = null
        route = null
        progressM = 0.0
        lastRouteSplitIdx = 0; lastRouteSnapPt = null; lastNextManeuver = null
        offRouteSince = 0L
        panX = 0f; panY = 0f; followMode = true
        smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null
        voice.resetTrip()
        lastSignature = ""   // force a redraw with no route line
        _ui.update { it.copy(
            destinationName = null,
            hasRoute = false,
            destLatLng = null,
            routePoints = emptyList(),
            remainingKm = null,
            etaMinutes = null,
            maneuver = null,
            offRoute = false,
            followMode = true,
        ) }
        session.updateRouteCard("WayDash")   // dash card → name + 0.0 km, nav off
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double) {
        viewModelScope.launch {
            // Prefer the alternative the rider picked on the Route/Explore screen, if it matches
            // this destination; otherwise fetch fresh from current GPS.
            val precomputed = com.example.northstar.dash.nav.RoutePlanHolder.takeIfMatches(destLatV, destLngV)
            val r = if (precomputed != null) {
                precomputed
            } else {
                var loc = location.lastKnown()
                if (loc == null) {
                    // Wait up to 4s for the first live GPS fix if location just started.
                    repeat(8) {
                        if (loc == null) {
                            delay(500)
                            loc = location.lastKnown()
                        }
                    }
                }
                val origin = loc
                if (origin == null) {
                    android.util.Log.w("DashViewModel", "fetchRoute: no origin location yet")
                    null
                } else {
                    com.example.northstar.dash.nav.RoutingHub.route(
                        getApplication(), GeoPoint(origin.latitude, origin.longitude), GeoPoint(destLatV, destLngV)
                    )
                }
            }
            if (r != null) {
                route = r
                tiles.prefetchRoute(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry) }
                android.util.Log.i("DashViewModel", "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
            } else {
                android.util.Log.w("DashViewModel", "Router returned null")
            }
        }
    }

    // ── Map controls ────────────────────────────────────────────────────────

    fun zoomIn()  = setZoom(zoom + 1)
    fun zoomOut() = setZoom(zoom - 1)
    private fun setZoom(z: Int) {
        val clamped = z.coerceIn(11, 20)
        if (clamped == zoom) return
        zoom = clamped
        com.example.northstar.data.DashPrefs.setZoom(getApplication(), clamped)
        _ui.update { it.copy(mapZoom = zoom) }
        // Make zoom feel instant: force a redraw on the very next tick (the renderer bridges the
        // gap with scaled neighbouring-zoom tiles), and warm the real tiles for the new level so
        // the scaled view sharpens in quickly.
        lastSignature = ""
        if (camInit) tiles.prefetchZoom(camLat, camLng, zoom)
    }
    fun panBy(dx: Float, dy: Float) = manualPan(dx, dy)
    fun recenter() {
        panX = 0f; panY = 0f; followMode = true
        _ui.update { it.copy(followMode = true) }
    }
    fun toggleHeadingUp() {
        headingUp = !headingUp
        _ui.update { it.copy(headingUp = headingUp) }
    }
    fun toggleTiltedView() {
        tiltedView = !tiltedView
        com.example.northstar.data.DashPrefs.setTiltedView(getApplication(), tiltedView)
        _ui.update { it.copy(tiltedView = tiltedView) }
        lastSignature = ""
    }

    private fun manualPan(dx: Float, dy: Float) {
        panX += dx; panY += dy
        followMode = false
        lastManualPanAt = System.currentTimeMillis()
        _ui.update { it.copy(followMode = false) }
    }

    // ── Video + nav loop ────────────────────────────────────────────────────

    private fun startStream() {
        com.example.northstar.data.RideDiagnostics.log("stream", "startStream — encoder up, RTP→dash beginning")
        videoPtsMs = 0L
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc    = NalProcessor { nal, _ ->
            // Even, monotonic presentation timestamp (set per frame by the send loop) instead of
            // wall clock — so the dash sees a clean, evenly-spaced timeline regardless of tiny
            // send-time wobble. All NALs of one frame share the same value.
            packetizer.packetize(nal, endOfAU = true, wallClockMs = videoPtsMs)
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, isKey ->
            nalProc.process(annexB)
            // First encoded frame leaving the phone — its delay after READY is the prime suspect
            // for the dash's "Timeout!" (the dash waits for video and gives up if it's late).
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                com.example.northstar.data.RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
            }
            // Atomic update: this runs on the encoder's callback thread, concurrent with the
            // frame loop's _ui writes — a plain copy() read-modify-write would drop updates.
            _ui.update { it.copy(frameCount = it.frameCount + 1) }
        }
        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        // Fresh camera so it snaps to the first fix instead of gliding from a stale spot.
        camInit = false; lastTickNs = 0L; lastFixTime = 0L; heldRider = null

        session.startStreaming()
        startMediaForwarding()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPrefetch = 0L
            var failures = 0
            // Motion-adaptive, absolute-grid pacing (RE-style). Each iteration tick() tells us whether
            // the map actually changed; we hold the fast profile (FRAME_MS_ACTIVE) for MOTION_HOLD_MS
            // after the last change, then settle to the slow profile (FRAME_MS_IDLE). The grid sleeps
            // only the REMAINDER to the next frame so render+encode work doesn't make the cadence
            // wobble — uneven arrival is what makes the dash decoder jitter/teleport. videoPtsMs
            // advances by the interval actually used so RTP timestamps stay proportional to real time.
            var lastChangeMs = System.currentTimeMillis()
            var idleBitrate = false   // track the live profile so we only poke the encoder on a switch
            var frameMs = FRAME_MS_ACTIVE
            var nextFrameMs = System.currentTimeMillis()
            var lastSendMs = System.currentTimeMillis()
            // The loop must NEVER die silently: the session's heartbeats keep the dash
            // connected, so a dead frame loop = frozen map with the connection "up".
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    val changed = tick()
                    val nowMs = System.currentTimeMillis()
                    if (changed) lastChangeMs = nowMs
                    // Active while the map changed recently; idle once it's been still for MOTION_HOLD_MS.
                    val active = nowMs - lastChangeMs < MOTION_HOLD_MS
                    frameMs = if (active) FRAME_MS_ACTIVE else FRAME_MS_IDLE
                    val bmp = frameBitmap
                    val enc = encoder
                    if (bmp != null && enc != null) {
                        // Switch the encoder bitrate to match the profile, but only on a transition.
                        if (active && idleBitrate) { enc.requestBitrate(DashEncoder.BITRATE); idleBitrate = false }
                        else if (!active && !idleBitrate) { enc.requestBitrate(DashEncoder.BITRATE_IDLE); idleBitrate = true }
                        enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                        // Advance the presentation clock by THIS frame's interval BEFORE draining, so the
                        // frame(s) pulled this iteration carry an evenly-spaced RTP timestamp.
                        videoPtsMs += frameMs
                        enc.drain()
                    }
                    failures = 0
                    // Track the real send cadence (EMA + worst) so the ride log proves it's even.
                    val sendNow = System.currentTimeMillis()
                    val gap = sendNow - lastSendMs
                    lastSendMs = sendNow
                    sendPaceMs = if (sendPaceMs <= 0.0) gap.toDouble() else sendPaceMs + (gap - sendPaceMs) * 0.1
                    if (gap > sendPaceMaxMs) sendPaceMaxMs = gap
                    // Warm the tile cache ahead of the rider every ~20 s.
                    if (sendNow - lastPrefetch > 20_000) {
                        lastPrefetch = sendNow
                        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    android.util.Log.e("DashViewModel", "Frame loop error #$failures", e)
                    if (failures >= 3) {
                        // MediaCodec is in an error state — rebuild the encoder so the
                        // stream recovers. The fresh encoder re-emits SPS/PPS, which the
                        // NAL processor bundles into the next IDR for the dash decoder.
                        runCatching { encoder?.release() }
                        encoder = runCatching { DashEncoder(onEncoded).also { it.prepare() } }
                            .onFailure { android.util.Log.e("DashViewModel", "Encoder rebuild failed", it) }
                            .getOrNull()
                        lastSignature = "" // force a full redraw on the next tick
                        failures = 0
                    }
                }
                // Sleep only the remaining time to the next grid point.
                nextFrameMs += frameMs
                val sleepMs = nextFrameMs - System.currentTimeMillis()
                if (sleepMs > 1) {
                    delay(sleepMs)
                } else if (sleepMs < -frameMs) {
                    // Fell more than a frame behind (render spike) — re-anchor so we don't fire a
                    // burst of catch-up frames (which would itself look like a jump).
                    nextFrameMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Compute nav state, push nav-info to the dash, and redraw the frame only if it changed.
     * @return true if the visible map content changed this tick (drives the motion-adaptive cadence).
     */
    private fun tick(): Boolean {
        // Revert to follow mode after the rider stops nudging the joystick.
        if (!followMode && System.currentTimeMillis() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true
            _ui.update { it.copy(followMode = true) }
        }

        val loc = location.location.value
        val r = route
        val dLat = destLat; val dLng = destLng

        // Default to raw GPS; map-matching below snaps it onto the route when on it.
        matchedLat = loc?.latitude
        matchedLng = loc?.longitude

        var remainingM: Double? = null
        var etaSec: Double? = null
        // Heading-up direction. GPS bearing is trustworthy ONLY while genuinely moving — at a
        // standstill (or a near-zero crawl) it's noise and would rotate the map to a random or
        // opposite direction the instant you stop. So adopt it only above BEARING_MIN_SPEED (and
        // when the fix actually carries a bearing); otherwise HOLD the last heading shown while
        // moving. Also keeps the last heading through a GPS dropout (tunnels).
        var heading = when {
            loc != null && loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED -> loc.bearing
            camInit -> camHdg
            else -> 0f
        }
        var offRoute = false
        var recalculating = false

        if (r != null && loc != null) {
            val ns = trackProgress(r, GeoPoint(loc.latitude, loc.longitude))
            remainingM = ns.remainingM
            lastNextManeuver = ns.nextManeuver
            lastNextTurnM = ns.nextTurnM
            lastRouteSplitIdx = ns.splitIndex
            lastRouteSnapPt = ns.snapped
            // Heading-aware off-route. A flyover (or its service road) runs PARALLEL to and almost
            // on top of the route, so flat GPS can sit well past the lateral threshold while the
            // rider is still effectively on the line. Distance alone then cries "off-route" and
            // triggers a sudden reroute. So in the ambiguous band we require the heading to ALSO
            // disagree (a genuine divergence / wrong turn); a clearly different road (very far) is
            // off-route regardless. This is the main flyover-confusion mitigation.
            lastSnapDist = ns.snapDist
            val headingKnown = loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED
            val headingOff = headingKnown && angleDelta(loc.bearing, ns.heading) > 50f
            offRoute = when {
                ns.snapDist > 150.0 -> true       // unmistakably a different road → off regardless
                ns.snapDist > 70.0  -> headingOff // parallel/flyover band: off ONLY if heading diverges
                else -> false                     // (stopped here → not off-route; decide once moving)
            }
            // NOTE: no marker snapping. Raw GPS is accurate; snapping to the route
            // polyline pinned the rider onto a parallel road in dense areas (showed
            // "Indira Enclave" when actually at "Isha"). trackProgress is still used
            // for nav distances + off-route/reroute detection, just not to move the marker.
            // (Heading is handled above — held steady at low speed, not snapped to the route
            // segment, so a stop doesn't rotate the map.)
            val nowMs = System.currentTimeMillis()

            // "Recalculating" = off the line for >0.8 s (ignores a 1-frame GPS blip), OR a reroute
            // request is in flight. While true the distance is measured against a route we've left,
            // so the ETA is meaningless — we FREEZE it (and the pill says "Recalculating") rather
            // than let the number lurch until the fresh route lands. recalcHoldUntil keeps the pill
            // up a beat after the new route lands so a fast reroute is still visible to the rider.
            if (offRoute) { if (offRouteStreakStart == 0L) offRouteStreakStart = nowMs }
            else offRouteStreakStart = 0L
            val rawRecalc = rerouting || (offRouteStreakStart != 0L && nowMs - offRouteStreakStart > 800)
            if (rawRecalc) recalcHoldUntil = nowMs + 1_500
            recalculating = rawRecalc || nowMs < recalcHoldUntil

            if (!recalculating) {
                // Steady ETA: divide remaining distance by a ROLLING AVERAGE speed (held through
                // stops), not the instantaneous value — so it tracks a typical pace instead of
                // swinging every time you brake, accelerate or sit at a light.
                if (loc.speed > 0.5f) {
                    smoothSpeedMps = if (smoothSpeedMps <= 0.0) loc.speed.toDouble()
                                     else smoothSpeedMps + (loc.speed - smoothSpeedMps) * 0.05
                }
                val speed = smoothSpeedMps.coerceAtLeast(3.0)  // floor: no absurd ETA at a crawl
                val rawEta = ns.remainingM / speed
                smoothEtaSec = if (smoothEtaSec <= 0.0) rawEta else smoothEtaSec + (rawEta - smoothEtaSec) * 0.08
                // Refresh the displayed minutes + arrival clock only every 5 s so they read steady
                // (like Google Maps) instead of flickering every second.
                if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 5_000) {
                    etaArrivalMs = nowMs + (smoothEtaSec * 1000).toLong()
                    displayedEtaMin = Math.round(smoothEtaSec / 60.0).toInt()
                    lastArrivalCalcMs = nowMs
                }
            }
            etaSec = if (smoothEtaSec > 0.0) smoothEtaSec else null
            // Feed the dash's own turn-by-turn widget with CORRECT distances (next-turn
            // + total remaining) and the (held) arrival time. Glyph stays CONTINUE until
            // other codes are verified.
            val (pv, pu) = toDashDistance(ns.nextTurnM)
            val (tv, tu) = toDashDistance(ns.remainingM)
            val etaHHMM = etaSec?.let {
                val arrival = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, it.toInt()) }
                "%02d%02d".format(arrival.get(java.util.Calendar.HOUR_OF_DAY), arrival.get(java.util.Calendar.MINUTE))
            }
            session.updateNavInfo(DashCommands.NAV_MANEUVER_CONTINUE, pv, pu, tv, tu, etaHHMM)
            // Spoken/chime turn guidance (no-op when voice mode is OFF).
            voice.maybeAnnounce(ns.nextManeuver, ns.nextTurnM, ns.remainingM)
        } else if (loc != null && dLat != null && dLng != null) {
            remainingM = GeoPoint.distMeters(
                GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng)
            )
        }

        // Recompute the route if the rider has clearly left it for a few seconds.
        maybeReroute(offRoute, loc)

        // GPS health: LOST if no fresh fix for a few seconds (or none at all), WEAK if the fix is
        // imprecise or coming in slowly, else GOOD. Drives the on-dash indicator and marker colour.
        val fixAgeMs = if (fixWallMs > 0L) System.currentTimeMillis() - fixWallMs else Long.MAX_VALUE
        gpsStatus = when {
            loc == null || fixAgeMs > 4_000 -> GpsStatus.LOST
            loc.accuracy > 25f || gpsFixIntervalMs > 2_500 -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }

        // Publish nav figures to the phone UI.
        _ui.update { it.copy(
            hasGps = loc != null,
            gpsStatus = gpsStatus,
            riderLat = matchedLat,
            riderLng = matchedLng,
            riderBearing = heading,
            remainingKm = remainingM?.let { d -> d / 1000.0 },
            etaMinutes = if (etaSec != null) displayedEtaMin else null,
            maneuver = if (r != null) lastNextManeuver?.let { m -> "${m.instruction} · ${fmtDist(lastNextTurnM)}" } else null,
            offRoute = offRoute,
            recalculating = recalculating,
        ) }

        updateThermal()

        // Periodic ride snapshot (~15 s) — GPS quality, motion, frame throughput and thermal,
        // so a post-ride read shows where the map stuttered (weak GPS) or the dash struggled.
        val nowD = System.currentTimeMillis()
        if (nowD - lastDiagMs > 15_000) {
            lastDiagMs = nowD
            com.example.northstar.data.RideDiagnostics.log(
                "ride",
                "gps=" + (loc?.let { "acc=%.0fm spd=%.1fm/s".format(it.accuracy, it.speed) } ?: "none") +
                    " fixGap=%.0fms".format(gpsFixIntervalMs) +
                    " frames=${_ui.value.frameCount} thermal=${_ui.value.thermal}" +
                    " pace=%.0fms/max=%dms".format(sendPaceMs, sendPaceMaxMs) +  // send cadence (≈250ms active / 500ms idle)
                    " remaining=" + (remainingM?.let { "%.1fkm".format(it / 1000.0) } ?: "-") +
                    " offRoute=$offRoute" +
                    (if (lastSnapDist >= 0) " snap=%.0fm".format(lastSnapDist) else ""),
            )
            sendPaceMaxMs = 0L  // reset the worst-case window each diag line
        }

        // ── Predictive, framerate-independent camera (CarPlay-smooth) ──
        // Capture each fresh GPS fix + its velocity for dead-reckoning.
        if (loc != null && loc.time != lastFixTime) {
            lastFixTime = loc.time
            // Track the GAP between fixes (EMA): if this balloons past ~1 s with the screen off,
            // GPS callbacks are being throttled — that's what the predictor has to bridge and the
            // root of any residual "stuck then teleport". Logged in the periodic ride line.
            val nowWall = System.currentTimeMillis()
            if (fixWallMs > 0L) {
                val gap = (nowWall - fixWallMs).toDouble()
                gpsFixIntervalMs = if (gpsFixIntervalMs <= 0.0) gap else gpsFixIntervalMs + (gap - gpsFixIntervalMs) * 0.3
            }
            fixLat = loc.latitude; fixLng = loc.longitude
            fixBearing = loc.bearing; fixSpeed = loc.speed
            fixWallMs = nowWall
        }
        // Extrapolate where the rider IS NOW (last fix + velocity·elapsed), so the camera
        // doesn't trail the bike between 1 Hz fixes. Only predict while genuinely moving.
        val rider: Pair<Double, Double>? = when {
            loc == null -> null
            fixSpeed > 0.5f -> {
                // Dead-reckon forward from the last fix so the camera keeps GLIDING through GPS
                // gaps instead of freezing then snapping ("stuck then jump"). predictedDistance
                // runs at full speed for the first ~1 s (the normal 1 Hz fix gap) then tapers, so
                // a long dropout — or a brake/stop the predictor hasn't heard about yet — can't
                // fling the camera far ahead; the next fix then needs only a tiny eased correction.
                // elapsed = time since the fix arrived; + DISPLAY_LEAD_S leads the marker past
                // "now" to cancel the camera-ease + encode + RTP + dash-decode/display latency, so
                // the dash tracks the bike instead of trailing ~0.5 s behind. The predictor's taper
                // still caps how far a long dropout / unannounced brake can fling it.
                val elapsed = (System.currentTimeMillis() - fixWallMs) / 1000.0
                // Weak-GPS guard: a noisy fix has an unreliable position AND bearing/speed, so
                // extrapolating from it flings the view ("teleport"). Scale the prediction down as
                // accuracy worsens — but only for GENUINELY poor fixes: full up to 25 m, none above
                // 50 m. The old 12 m cutoff scaled prediction DOWN at the moderate accuracy that's
                // normal at speed, so the predictor undershot → the camera lagged → each new fix
                // snap-corrected forward = the "jumping when moving fast". Widening it keeps the
                // dead-reckon gliding through the 1 Hz gaps at speed while still guarding bad fixes.
                val conf = ((50f - (loc.accuracy)) / 25f).toDouble().coerceIn(0.0, 1.0)
                val dist = predictedDistance(fixSpeed, elapsed + DISPLAY_LEAD_S) * conf
                // Remember where the bike actually is, so that when it stops the marker holds the
                // real fix (not the lead-projected point ahead of it).
                heldRider = fixLat to fixLng
                project(fixLat, fixLng, fixBearing.toDouble(), dist)
            }
            else -> {
                // Stopped: hold position and IGNORE sub-accuracy GPS wander, so a parked bike's dot
                // doesn't drift or jitter. Only move the held point if the fix steps clearly outside
                // its own error radius (i.e. the bike really moved a little / GPS re-converged).
                val cand = matchedLat!! to matchedLng!!
                val h = heldRider
                val band = loc.accuracy.coerceIn(8f, 30f).toDouble()
                if (h == null || GeoPoint.distMeters(
                        GeoPoint(h.first, h.second), GeoPoint(cand.first, cand.second)
                    ) > band
                ) { heldRider = cand; cand } else h
            }
        }

        val haveTarget = rider != null || (dLat != null && dLng != null)
        val targetLat = rider?.first ?: dLat ?: camLat
        val targetLng = rider?.second ?: dLng ?: camLng

        // Time-based smoothing: alpha derived from the real frame interval + a time
        // constant, so motion is equally smooth at any (dynamic) frame rate.
        val nowNs = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.042 else ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        // Adaptive smoothing: ease HARDER (bigger time constant) when the fix is noisy so weak-GPS
        // jitter doesn't reach the camera, and stay snappy when the signal is clean. This is the
        // other half of the "stuck then jump" fix — the predictor bridges gaps, this absorbs the
        // lateral noise that makes a poor signal jitter.
        val acc = loc?.accuracy ?: 12f
        val tau = when {
            acc > 25f -> SMOOTH_TAU * 2.0   // poor signal → heavy smoothing
            acc > 12f -> SMOOTH_TAU * 1.5   // fair
            else      -> SMOOTH_TAU         // good → responsive
        }
        val a = if (camInit) (1.0 - Math.exp(-dt / tau)) else 1.0

        if (haveTarget) {
            if (!camInit) { camLat = targetLat; camLng = targetLng; camHdg = heading; camInit = true }
            else {
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f  // shortest arc
                camHdg += dh * a.toFloat()
            }
        }
        // Lock the dash marker to the smoothed centre (map slides under it).
        frameRiderLat = if (rider != null) camLat else null
        frameRiderLng = if (rider != null) camLng else null

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val sig = buildString {
            // High resolution (6 dp ≈ 0.1 m, 0.1° heading) so every smoothed step redraws
            // for buttery motion. Safe from standstill jitter because the camera is fed the
            // SMOOTHED position (which settles and stops), not raw GPS.
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append((camHeading * 10).toInt())
            append(if (headingUp) "H" else "N")
            append(if (tiltedView && headingUp) "T" else "F")
            append(remainingM?.let { (it / 25).toInt() } ?: -1)
            append(displayedEtaMin ?: -1); append(if (recalculating) "R" else "_")
            append(gpsStatus.ordinal)
            append(if (r != null) r.geometry.size else 0)
            append(com.example.northstar.dash.DashAlertBus.active()?.title ?: "")
            append(com.example.northstar.dash.DashAlertBus.badgeCount())
            append(lastNextManeuver?.instruction ?: "")
            // Include the incoming-call caller + source so cellular & WhatsApp calls redraw immediately.
            val activeCall = com.example.northstar.media.CallInfoProvider.incomingCall.value?.takeIf { it.incoming }
            append(activeCall?.caller ?: ""); append(activeCall?.source ?: "")
        }
        val now = System.currentTimeMillis()
        // A genuine content change (not the periodic forced refresh) is what marks the map as
        // "moving" for the adaptive cadence — a forced redraw of identical content stays "idle".
        val contentChanged = sig != lastSignature
        if (contentChanged || now - lastRedrawAt > FORCE_REDRAW_MS) {
            lastSignature = sig
            lastRedrawAt = now
            redrawFrame(centerLat, centerLng, camHeading, remainingM)
        }
        return contentChanged
    }

    /**
     * Reroute when off the line for >5 s (12 s cooldown between attempts). Routes from
     * the live GPS position to the saved destination and swaps the polyline in. Needs
     * internet — available now because only the dash sockets are bound to the dash WiFi.
     */
    private fun maybeReroute(offRoute: Boolean, loc: android.location.Location?) {
        val dLat = destLat; val dLng = destLng
        if (!offRoute || loc == null || dLat == null || dLng == null) { offRouteSince = 0L; return }
        val now = System.currentTimeMillis()
        if (offRouteSince == 0L) offRouteSince = now
        // Reroute faster: 2 s off-line confirms a real miss (not a GPS wobble), 6 s cooldown
        // between attempts. Was 4 s / 12 s, which felt sluggish after a missed turn.
        if (now - offRouteSince < 2_000 || now - lastRerouteAt < 6_000 || rerouting) return
        lastRerouteAt = now
        rerouting = true
        val offSec = (now - offRouteSince) / 1000
        android.util.Log.i("DashViewModel", "Off-route ${offSec}s → rerouting")
        com.example.northstar.data.RideDiagnostics.log("reroute", "off-route ${offSec}s → requesting new route")
        val startMs = System.currentTimeMillis()
        viewModelScope.launch {
            val r = com.example.northstar.dash.nav.RoutingHub.route(
                getApplication(), GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng))
            val took = System.currentTimeMillis() - startMs
            if (r != null) {
                route = r
                progressM = 0.0
                lastRouteSplitIdx = 0
                lastRouteSnapPt = null
                offRouteSince = 0L
                tiles.prefetchRoute(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry) }
                android.util.Log.i("DashViewModel", "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
                com.example.northstar.data.RideDiagnostics.log("reroute", "new route in ${took}ms (${r.geometry.size} pts, ${r.totalMeters.toInt()} m)")
            } else {
                android.util.Log.w("DashViewModel", "Reroute failed (no internet?)")
                com.example.northstar.data.RideDiagnostics.log("reroute", "FAILED after ${took}ms (no internet?)")
            }
            rerouting = false
        }
    }

    private fun redrawFrame(
        centerLat: Double, centerLng: Double, heading: Float, remainingM: Double?,
    ) {
        val bmp = frameBitmap ?: return
        val loc = location.location.value
        // Glanceable ETA — minutes remaining + a stable 12-hour arrival clock. Both come
        // from the smoothed estimate so they don't flicker every second.
        val mins = _ui.value.etaMinutes
        val arriving = mins != null && mins <= 0
        val recalculating = _ui.value.recalculating
        // Off-route / rerouting: tell the rider the ETA is being recomputed instead of showing a
        // frozen or wildly-swinging number.
        val etaPrimary = when {
            recalculating && route != null -> "Recalculating"
            mins != null && etaArrivalMs > 0L -> when {
                arriving   -> "Arriving"
                mins >= 60 -> "${mins / 60}h ${mins % 60}m"
                else       -> "$mins min"
            }
            else -> null
        }
        // 12-hour arrival clock; hidden once arriving or while recalculating.
        val etaSecondary = if (etaPrimary != null && !arriving && !recalculating)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(etaArrivalMs))
        else null
        val dashAlert = com.example.northstar.dash.DashAlertBus.active()
        val callBottom = activeIncomingCallBottom(bmp.width)
        val nextMan = if (route != null && ! _ui.value.offRoute) lastNextManeuver else null
        val (dashManText, dashManType, dashManFlyover) = buildDashManeuverChip(nextMan, lastNextTurnM)

        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            panX = panX,
            panY = panY,
            headingUp = headingUp && (loc != null),
            heading = heading,
            riderLat = frameRiderLat,
            riderLng = frameRiderLng,
            destLat = destLat,
            destLng = destLng,
            destName = _ui.value.destinationName,
            route = route?.geometry ?: emptyList(),
            routeSplitIndex = lastRouteSplitIdx,
            routeSplitPoint = if (_ui.value.offRoute) null else lastRouteSnapPt,
            maneuverText = dashManText,
            maneuverType = dashManType,
            maneuverFlyover = dashManFlyover,
            alertTitle = dashAlert?.title,
            alertLine = dashAlert?.line,
            alertBadge = com.example.northstar.dash.DashAlertBus.badgeCount(),
            remainingText = remainingM?.let { fmtDist(it) },
            // Tilted perspective view for heading-up navigation (shows oncoming road ahead).
            tilt3d = tiltedView && headingUp && (loc != null),
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
            gpsWeak = gpsStatus == GpsStatus.WEAK,
            gpsLost = gpsStatus == GpsStatus.LOST,
            reservedTopCenterBottom = callBottom,
        )
        val canvas = Canvas(bmp)
        // EXPERIMENTAL MapLibre 3D base layer. Only when the rider selected it AND a snapshot
        // is ready; otherwise fall through to the flat tile compositor. This guarantees the
        // stream never breaks because of the 3D path.
        val use3d = com.example.northstar.data.MapPrefs.tileSource(getApplication()) ==
            com.example.northstar.dash.map.TileSource.MAPLIBRE_3D
        var drew3d = false
        if (use3d) {
            ensure3dRenderer(bmp.width, bmp.height)
            building3d?.requestFor(centerLat, centerLng, zoom, if (headingUp) heading else 0f, route?.geometry ?: emptyList())
            building3d?.latestBitmap?.let { snap ->
                canvas.drawBitmap(snap, null, android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), null)
                // Route + rider + ETA + attribution still drawn on top by MapRenderer's overlay
                // pass; call draw() with a flag so it skips its own tile fill.
                mapRenderer.draw(canvas, frame, skipBaseMap = true)
                drew3d = true
            }
        }
        if (!drew3d) mapRenderer.draw(canvas, frame)
        // The dash shows ONLY our video frame while projecting — it won't overlay its native
        // now-playing/call cards (confirmed: 05 0d/05 22 are sent fine but never displayed mid-map).
        // So we draw them INTO the frame ourselves, as the CLAUDE.md design always intended.
        drawDashOverlays(canvas, bmp.width, bmp.height)
        // Publish a snapshot for the in-app preview (immutable copy — see _previewFrame).
        _previewFrame.value = bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Concise upcoming turn / flyover guidance for the top-centre dome chip:
     * - Flyover / bridge / underpass / service-road hints appear within 550 m (amber chip),
     *   displaying "Now" inside 25 m so guidance holds smoothly onto the entry ramp.
     * - Real turns / roundabouts / U-turns appear within 350 m (dark chip + vector arrow).
     */
    private fun buildDashManeuverChip(
        m: com.example.northstar.dash.nav.Maneuver?,
        distM: Double,
    ): Triple<String?, com.example.northstar.dash.nav.ManeuverType?, Boolean> {
        if (m == null) return Triple(null, null, false)
        if (m.flyover && distM <= 550.0) {
            val distLabel = if (distM < 25.0) "Now" else fmtDist(distM)
            return Triple("${m.instruction} · $distLabel", m.type, true)
        }
        val t = m.type
        if (t == com.example.northstar.dash.nav.ManeuverType.CONTINUE ||
            t == com.example.northstar.dash.nav.ManeuverType.DEPART
        ) return Triple(null, null, false)

        val threshold = if (t == com.example.northstar.dash.nav.ManeuverType.ARRIVE) 150.0 else 350.0
        if (distM > threshold) return Triple(null, null, false)

        val shortAction = when (t) {
            com.example.northstar.dash.nav.ManeuverType.TURN_LEFT    -> "Left"
            com.example.northstar.dash.nav.ManeuverType.TURN_RIGHT   -> "Right"
            com.example.northstar.dash.nav.ManeuverType.SLIGHT_LEFT  -> "Keep left"
            com.example.northstar.dash.nav.ManeuverType.SLIGHT_RIGHT -> "Keep right"
            com.example.northstar.dash.nav.ManeuverType.SHARP_LEFT   -> "Sharp left"
            com.example.northstar.dash.nav.ManeuverType.SHARP_RIGHT  -> "Sharp right"
            com.example.northstar.dash.nav.ManeuverType.UTURN        -> "U-turn"
            com.example.northstar.dash.nav.ManeuverType.ROUNDABOUT   -> "Roundabout"
            com.example.northstar.dash.nav.ManeuverType.ARRIVE       -> "Arrive"
            else -> return Triple(null, null, false)
        }
        return Triple("$shortAction · ${fmtDist(distM)}", t, false)
    }

    // Overlay paints — created once (never per-frame).
    private val ovBg by lazy { android.graphics.Paint().apply { color = 0xEC0B0E14.toInt(); isAntiAlias = true } }
    private val ovRing by lazy { android.graphics.Paint().apply {
        color = 0xFF2E7CF6.toInt(); isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.5f
    } }
    private val ovTitle by lazy { android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE; isAntiAlias = true; textSize = 16.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER } }
    private val ovSub by lazy { android.graphics.Paint().apply {
        color = 0xFF93C5FD.toInt(); isAntiAlias = true; textSize = 10.5f; isFakeBoldText = true
        textAlign = android.graphics.Paint.Align.CENTER } }

    /** Bottom Y of the incoming-call banner when active (0f when idle), so HUD pills stack cleanly below it. */
    private fun activeIncomingCallBottom(w: Int): Float {
        val call = com.example.northstar.media.CallInfoProvider.incomingCall.value
        if (call == null || !call.incoming) return 0f
        val now = System.currentTimeMillis()
        if (callOverlaySinceMs != 0L && now - callOverlaySinceMs > 60_000) return 0f
        val fmName = ovTitle.fontMetrics
        val nameH = fmName.descent - fmName.ascent
        val cardH = 18f + 13f + 4f + nameH
        return 36f + cardH
    }

    /**
     * Draw the INCOMING-call alert banner onto the projected frame (cellular + WhatsApp/VoIP).
     * Clean 2-line display-only card (source eyebrow + caller name) — no UP/DN button prompts.
     */
    private fun drawDashOverlays(canvas: Canvas, w: Int, h: Int) {
        val call = com.example.northstar.media.CallInfoProvider.incomingCall.value
        if (call == null || !call.incoming) { callOverlaySinceMs = 0L; return }
        // Failsafe TTL: if the dialer/VoIP notification-removed event never reaches us,
        // stop drawing after 60s of continuous ringing.
        val now = System.currentTimeMillis()
        if (callOverlaySinceMs == 0L) callOverlaySinceMs = now
        if (now - callOverlaySinceMs > 60_000) return

        val isWhatsApp = call.source.startsWith("WhatsApp", ignoreCase = true)
        val header = when {
            call.source.isNotBlank() && call.source.endsWith("call", ignoreCase = true) ->
                call.source.uppercase()
            call.source.isNotBlank() -> "${call.source} call".uppercase()
            else -> "INCOMING CALL"
        }
        val name = ellipsizeOverlay(call.caller, 18)

        // Emerald accent for WhatsApp calls; Brava blue for cellular & other calls.
        ovRing.color = if (isWhatsApp) 0xFF25D366.toInt() else 0xFF2E7CF6.toInt()
        ovSub.color  = if (isWhatsApp) 0xFF4ADE80.toInt() else 0xFF93C5FD.toInt()

        val padH = 18f; val padV = 9f
        val fmName = ovTitle.fontMetrics
        val nameH = fmName.descent - fmName.ascent
        val subH = 13f
        val top = 36f // safely inside the 526×526 circular bezel arc
        val rSafe = (w / 2f) - 16f
        val dyCorner = (top + 16f) - (w / 2f)
        val maxHalfW = kotlin.math.sqrt((rSafe * rSafe - dyCorner * dyCorner).coerceAtLeast(0f))
        val maxCardW = (maxHalfW * 2f).coerceAtLeast(150f)
        val cardW = (maxOf(ovTitle.measureText(name), ovSub.measureText(header) + 14f) + padH * 2)
            .coerceAtMost(maxCardW)
        val cardH = padV * 2 + subH + 4f + nameH
        val cxp = w / 2f
        callRect.set(cxp - cardW / 2f, top, cxp + cardW / 2f, top + cardH)
        canvas.drawRoundRect(callRect, 16f, 16f, ovBg)
        canvas.drawRoundRect(callRect, 16f, 16f, ovRing)
        canvas.drawText(header, cxp, top + padV - ovSub.fontMetrics.ascent, ovSub)
        canvas.drawText(name, cxp, top + padV + subH + 4f - fmName.ascent, ovTitle)
    }
    private val callRect = android.graphics.RectF()
    @Volatile private var callOverlaySinceMs = 0L

    private fun ellipsizeOverlay(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

    // ── Monotonic route-progress tracker ────────────────────────────────────
    // Snapping to the GLOBALLY nearest segment makes the remaining distance flicker
    // (a winding route can pass near the start again, so it jumps to ~straight-line).
    // Instead we advance progress along the route, searching a window AHEAD of where
    // we already are, only re-acquiring globally if we're clearly off-route.
    @Volatile private var progressM = 0.0

    private data class NavState(
        val remainingM: Double, val nextTurnM: Double, val heading: Float, val offRoute: Boolean,
        val snapped: GeoPoint, val snapDist: Double,
        val nextManeuver: com.example.northstar.dash.nav.Maneuver?,
        val splitIndex: Int,
    )

    private data class Match(
        val cum: Double, val dist: Double, val bearing: Float, val proj: GeoPoint, val segIndex: Int,
    )

    private fun trackProgress(r: Route, pos: GeoPoint): NavState {
        val geom = r.geometry
        val cum = r.cumulative

        fun search(lo: Double, hi: Double): Match {
            var bestDist = Double.MAX_VALUE; var bestCum = progressM; var bestBearing = 0f
            var bestProj = pos; var bestIdx = 0
            for (i in 0 until geom.size - 1) {
                if (cum[i + 1] < lo || cum[i] > hi) continue
                val (proj, t) = GeoPoint.projectOnSegment(pos, geom[i], geom[i + 1])
                val d = GeoPoint.distMeters(pos, proj)
                if (d < bestDist) {
                    bestDist = d
                    bestCum = cum[i] + GeoPoint.distMeters(geom[i], geom[i + 1]) * t
                    bestBearing = GeoPoint.bearing(geom[i], geom[i + 1]).toFloat()
                    bestProj = proj
                    bestIdx = i
                }
            }
            return Match(bestCum, bestDist, bestBearing, bestProj, bestIdx)
        }

        var m = search(progressM - 60.0, progressM + 1000.0)
        if (m.dist > 80.0) {
            val g = search(0.0, r.totalMeters) // off-window → re-acquire globally
            if (g.dist < m.dist) m = g
        }
        progressM = maxOf(progressM - 25.0, m.cum) // mostly forward, tolerate small GPS slide

        val remaining = (r.totalMeters - progressM).coerceAtLeast(0.0)

        // Prioritize an imminent flyover/underpass/service-road step within [-35 m, +550 m]
        // unless a hard turn lies strictly ahead of us before that flyover. This prevents
        // intermediate "Continue straight" nodes from masking upcoming flyover guidance, and
        // holds the chip briefly onto the entry ramp so it never blinks off at the divider.
        val upcomingRealTurn = r.maneuvers.firstOrNull {
            it.cumulativeMeters > progressM + 1.0 &&
                !it.flyover &&
                it.type != com.example.northstar.dash.nav.ManeuverType.DEPART &&
                it.type != com.example.northstar.dash.nav.ManeuverType.CONTINUE
        }
        val upcomingFlyover = r.maneuvers.firstOrNull {
            it.flyover && (it.cumulativeMeters - progressM) in -35.0..550.0
        }
        val nextMan = when {
            upcomingFlyover != null &&
                (upcomingRealTurn == null || upcomingFlyover.cumulativeMeters <= upcomingRealTurn.cumulativeMeters + 30.0) ->
                upcomingFlyover
            upcomingRealTurn != null -> upcomingRealTurn
            else -> r.maneuvers.firstOrNull {
                it.cumulativeMeters > progressM + 1.0 && it.type != com.example.northstar.dash.nav.ManeuverType.DEPART
            }
        }
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        return NavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan, m.segIndex)
    }

    private fun updateThermal() {
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> "Hot"
            else -> "Throttling"
        }
        if (label != _ui.value.thermal) _ui.update { it.copy(thermal = label) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** metres → (value, dash unit). <1 km → metres (0x30); else km×10 (0x10). */
    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_METERS
        else (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_KM_TENTHS

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    /** Smallest absolute angle (0..180°) between two compass bearings. */
    private fun angleDelta(a: Float, b: Float): Float {
        val d = (((a - b) % 360f) + 540f) % 360f - 180f
        return kotlin.math.abs(d)
    }

    private fun teardown() {
        streamJob?.cancel(); streamJob = null
        stopMediaForwarding()
        encoder?.release(); encoder = null
        frameBitmap?.recycle(); frameBitmap = null
        building3d?.destroy(); building3d = null
        _previewFrame.value = null   // drop the stale dash frame so the preview falls back to the live map
    }

    /**
     * Bridge the phone's now-playing session + incoming-call notification onto the dash (TLV 05 0d /
     * 05 22), matching the dash. Needs notification access; without it MediaInfoProvider
     * stays silent and the dash simply shows no media card. Album art (05 40) is captured in the
     * protocol but not auto-sent yet (fragmentation unverified — see DashCommands.albumArtFrames).
     */
    private fun startMediaForwarding() {
        val granted = com.example.northstar.media.MediaInfoProvider.isAccessGranted(getApplication())
        com.example.northstar.data.RideDiagnostics.log("media", "forwarding start — notification access=$granted")
        mediaInfo.start()
        mediaObserveJob?.cancel()
        mediaObserveJob = viewModelScope.launch {
            launch {
                mediaInfo.nowPlaying.collect { np ->
                    com.example.northstar.data.RideDiagnostics.log("media", "now-playing observed: ${np?.title ?: "(none)"}")
                    session.updateNowPlaying(np?.title, np?.album.orEmpty(), np?.artist.orEmpty())
                }
            }
            launch {
                com.example.northstar.media.CallInfoProvider.incomingCall.collect { call ->
                    val ringingCaller = call?.takeIf { it.incoming }?.caller
                    com.example.northstar.data.RideDiagnostics.log("media", "call observed: ${ringingCaller ?: "(none)"} src=${call?.source.orEmpty()}")
                    session.updateCall(ringingCaller)
                }
            }
        }
    }

    private fun stopMediaForwarding() {
        mediaObserveJob?.cancel(); mediaObserveJob = null
        mediaInfo.stop()
        session.updateNowPlaying(null, "", "")
        session.updateCall(null)
    }

    override fun onCleared() {
        super.onCleared()
        if (this === instance && userWantsConnection) {
            // Keep headless background session alive when Activity is recreated/closed.
            return
        }
        saveParkedBikeIfStreamed("Auto-pinned on app close")
        stopRecording()        // save the in-progress ride if the app is closed mid-session
        session.disconnect()
        wifiManager.disconnect()
        // Streaming can't continue once the ViewModel is gone, so make sure the foreground
        // service + wake/wifi locks + GPS don't outlive it (otherwise they'd hold the CPU/WiFi
        // awake with nothing producing frames).
        releaseBackgroundResources()
        voice.shutdown()
        com.example.northstar.data.RideDiagnostics.stop("app closed")
    }
}
