package com.example.northstar.dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WifiConnStatus { IDLE, REQUESTING, CONNECTED, ERROR }

data class WifiState(
    val status: WifiConnStatus = WifiConnStatus.IDLE,
    val ssid: String = "",
    val error: String? = null,
)

/**
 * Programmatically connects to the Tripper Dash WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * bindProcessToNetwork() routes all UDP/TCP from this process through
 * the Tripper network so packets reach 192.168.1.1 even if the phone
 * has another network (cellular) available.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 30_000  // ms — Android shows system dialog within this
        private const val RECONNECT_DELAY  = 4_000L
        // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
        private const val WifiManagerUnknownSsid = "<unknown ssid>"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Is the phone's Wi‑Fi radio on? WifiNetworkSpecifier can only associate to the dash when
     * Wi‑Fi is enabled, and on Android 10+ the app can't enable it itself — so we check up front
     * and have the UI send the rider to the Wi‑Fi settings panel rather than silently timing out.
     * (Doesn't matter which network Wi‑Fi is currently joined to — the specifier connects the dash
     * as a separate per-app network.)
     */
    fun isWifiEnabled(): Boolean = wifi.isWifiEnabled

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork:
     * that routed the WHOLE app through the dash's no-internet WiFi, so routing,
     * geocoding and shared-link resolution all failed while connected ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    // True once we've successfully connected this session. Lets us tell a MID-RIDE drop (keep
    // retrying — the dash AP may have hung and will return after a power-cycle) apart from a failed
    // INITIAL connect / user-declined dialog (give up, don't spam).
    private var everConnected = false
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog.
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes Northstar work on
     *   any rider's Tripper without hardcoding their SSID. When false, exact-match.
     */
    fun connect(ssid: String, password: String = "", prefixMatch: Boolean = false) {
        wantConnected    = true
        everConnected    = false   // fresh user-initiated attempt
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        requestNetwork()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    @SuppressLint("MissingPermission")
    fun findDashSsid(prefix: String): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Trigger a fresh scan in case cached scanResults are stale
        runCatching {
            @Suppress("DEPRECATION")
            wifi.startScan()
        }
        val fromScan = wifi.scanResults
            .mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } }
            .firstOrNull { it.startsWith(prefix) }
        val fromConn = runCatching {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.startsWith(prefix) && it != WifiManagerUnknownSsid }
        }.getOrNull()
        (fromScan ?: fromConn).also { Log.i(TAG, "Scan lookup for '$prefix*' → ${it ?: "not found"}") }
    } catch (e: Exception) {
        Log.w(TAG, "Scan lookup failed: ${e.message}"); null
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun requestNetwork() {
        release()
        // If we are still on a prefix, check scan results one more time right before requesting
        if (pendingPrefix) {
            findDashSsid(pendingSsid)?.let { exact ->
                Log.i(TAG, "Upgraded prefix '$pendingSsid' to exact SSID '$exact' from scan")
                pendingSsid = exact
                pendingPrefix = false
                onSsidResolved?.invoke(exact)
            }
        }

        Log.i(TAG, "Requesting WiFi: '$pendingSsid' " +
            "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"})")
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCaps(network, caps)
                override fun onUnavailable() = handleUnavailable()
                override fun onLost(network: Network) = handleLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCaps(network, caps)
                override fun onUnavailable() = handleUnavailable()
                override fun onLost(network: Network) = handleLost(network)
            }
        }

        networkCallback = cb
        try {
            cm.requestNetwork(request, cb, CONNECT_TIMEOUT)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork threw: ${e.message}", e)
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun handleAvailable(network: Network) {
        this@DashWifiManager.network = network
        everConnected = true
        reconnectJob?.cancel()
        val resolved = resolveSsid(network).takeIf { it.isNotBlank() }
            ?: findDashSsid(DashConfig.DEFAULT_PREFIX)
            ?: pendingSsid
        Log.i(TAG, "WiFi connected ✓  ssid='$resolved' network=$network")
        if (resolved.isNotBlank() && !resolved.endsWith("_")) {
            resolvedSsid = resolved
            onSsidResolved?.invoke(resolved)
        }
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
    }

    private fun handleCaps(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo
        val rawSsid = info?.ssid.orEmpty().trim('"')
        val ssid = if (rawSsid.isBlank() || rawSsid == WifiManagerUnknownSsid) {
            findDashSsid(DashConfig.DEFAULT_PREFIX).orEmpty()
        } else rawSsid
        if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) return
        if (ssid == resolvedSsid) return
        resolvedSsid = ssid
        this@DashWifiManager.network = network
        Log.i(TAG, "Resolved dash SSID: '$ssid'")
        onSsidResolved?.invoke(ssid)
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
    }

    private fun handleUnavailable() {
        this@DashWifiManager.network = null
        if (everConnected && wantConnected) {
            Log.w(TAG, "WiFi unavailable mid-session — dash AP missing, retrying in ${RECONNECT_DELAY}ms")
            _state.value = WifiState(
                status = WifiConnStatus.REQUESTING,
                ssid   = pendingSsid,
                error  = "Dash WiFi lost — reconnecting…",
            )
            scheduleReconnect()
            return
        }
        Log.w(TAG, "WiFi unavailable — SSID not found or user declined")
        _state.value = WifiState(
            status = WifiConnStatus.ERROR,
            ssid   = pendingSsid,
            error  = "Could not connect to '$pendingSsid' — make sure the bike ignition is ON",
        )
        wantConnected = false
    }

    private fun handleLost(network: Network) {
        Log.w(TAG, "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms")
        this@DashWifiManager.network = null
        _state.value = WifiState(
            status = WifiConnStatus.REQUESTING,
            ssid   = pendingSsid,
            error  = "Link lost — reconnecting…",
        )
        if (wantConnected) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (wantConnected) requestNetwork()
        }
    }

    /** Read the connected network's SSID (strips the surrounding quotes Android adds). */
    private fun resolveSsid(network: Network): String {
        val caps = cm.getNetworkCapabilities(network)
        val info = caps?.transportInfo as? WifiInfo
        val fromCaps = info?.ssid.orEmpty().trim('"').let { if (it == WifiManagerUnknownSsid) "" else it }
        if (fromCaps.isNotBlank()) return fromCaps
        return findDashSsid(DashConfig.DEFAULT_PREFIX).orEmpty()
    }

    private fun release() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }
}
