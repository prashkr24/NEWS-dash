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
    private var passiveBgCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    // True once we've successfully connected this session. Lets us tell a MID-RIDE drop (keep
    // retrying — the dash AP may have hung and will return after a power-cycle) apart from a failed
    // INITIAL connect / user-declined dialog (give up, don't spam).
    private var everConnected = false
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var pendingFromBackground = false
    private var resolvedSsid: String? = null

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog when in foreground,
     * or adopts the OS-joined [android.net.wifi.WifiNetworkSuggestion] network when triggered
     * headlessly in the background (even when the app is completely closed).
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes Northstar work on
     *   any rider's Tripper without hardcoding their SSID. When false, exact-match.
     * @param fromBackground true when invoked from [DashAutoConnectReceiver] or [FastReconnectManager]
     *   without a visible Activity.
     */
    fun connect(
        ssid: String,
        password: String = "",
        prefixMatch: Boolean = false,
        fromBackground: Boolean = false,
    ) {
        wantConnected         = true
        everConnected         = false
        pendingSsid           = ssid
        pendingPassword       = password
        pendingPrefix         = prefixMatch
        pendingFromBackground = fromBackground
        resolvedSsid          = null
        requestNetwork()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    @SuppressLint("MissingPermission")
    fun findDashSsid(prefix: String, triggerActiveScan: Boolean = false): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (triggerActiveScan) {
            runCatching {
                @Suppress("DEPRECATION")
                wifi.startScan()
            }
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

    /**
     * Checks if Android OS has ALREADY joined the bike's `RE_*` Wi-Fi network (for example,
     * automatically in the background via [android.net.wifi.WifiNetworkSuggestion] while the
     * app UI is completely closed).
     */
    @SuppressLint("MissingPermission")
    private fun findExistingDashNetwork(): Network? {
        return try {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val info = caps.transportInfo as? WifiInfo
                val ssid = info?.ssid.orEmpty().trim('"')
                if (ssid.isNotBlank() && ssid != WifiManagerUnknownSsid) {
                    if ((!pendingPrefix && ssid == pendingSsid) || ssid.startsWith(DashConfig.DEFAULT_PREFIX)) {
                        return net
                    }
                }
                // On Android 13+, WifiInfo.ssid on a suggestion network may be redacted unless
                // FLAG_INCLUDE_LOCATION_INFO is used. Check LinkProperties for the Tripper Dash
                // gateway/subnet (192.168.1.x or 192.168.43.x) on a non-validated Wi-Fi link.
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    val lp = cm.getLinkProperties(net)
                    val addresses = lp?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty()
                    val routes = lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty()
                    val isDashSubnet = (addresses + routes).any {
                        it.startsWith("192.168.1.") || it.startsWith("192.168.43.")
                    }
                    if (isDashSubnet) return net
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "findExistingDashNetwork failed: ${e.message}")
            null
        }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun buildCallback(): ConnectivityManager.NetworkCallback =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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

    private fun requestNetwork() {
        release()
        // If we are still on a prefix, check scan results one more time right before requesting
        if (pendingPrefix) {
            findDashSsid(pendingSsid, triggerActiveScan = !pendingFromBackground)?.let { exact ->
                Log.i(TAG, "Upgraded prefix '$pendingSsid' to exact SSID '$exact' from scan")
                pendingSsid = exact
                pendingPrefix = false
                onSsidResolved?.invoke(exact)
            }
        }

        Log.i(TAG, "Requesting WiFi: '$pendingSsid' " +
            "(${if (pendingPrefix) "prefix" else "exact"}, bg=$pendingFromBackground, " +
            "password=${if (pendingPassword.isBlank()) "none" else "set"})")
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        // 1. Fast path for closed-app / WifiNetworkSuggestion auto-connect:
        // If Android OS already joined the RE_* Wi-Fi in the background, adopt that Network
        // immediately and watch it with a specifier-free callback (works even with 0 open Activities!).
        val existingNet = findExistingDashNetwork()
        val passiveRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        if (existingNet != null) {
            Log.i(TAG, "Adopted already-connected OS suggestion network: $existingNet")
            val passiveCb = buildCallback()
            passiveBgCallback = passiveCb
            runCatching { cm.registerNetworkCallback(passiveRequest, passiveCb) }
            handleAvailable(existingNet)
            return
        }

        // 2. When connecting from background (app closed), register a specifier-free listener
        // for the OS-managed WifiNetworkSuggestion network first. Why? Because Android 10+ rejects
        // WifiNetworkSpecifier.requestNetwork() when an app has no visible foreground Activity,
        // whereas registerNetworkCallback() on the suggestion network succeeds 100% of the time!
        if (pendingFromBackground) {
            val passiveCb = buildCallback()
            passiveBgCallback = passiveCb
            runCatching { cm.registerNetworkCallback(passiveRequest, passiveCb) }
        }

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = buildCallback()
        networkCallback = cb
        try {
            cm.requestNetwork(request, cb, CONNECT_TIMEOUT)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork specifier rejected/threw (bg=$pendingFromBackground): ${e.message}")
            if (!pendingFromBackground || passiveBgCallback == null) {
                _state.value = WifiState(
                    status = WifiConnStatus.ERROR,
                    ssid   = pendingSsid,
                    error  = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    private fun isLikelyDashNetwork(network: Network): Boolean {
        if (!pendingFromBackground) return true
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val info = caps.transportInfo as? WifiInfo
        val ssid = info?.ssid.orEmpty().trim('"')
        if (ssid.isNotBlank() && ssid != WifiManagerUnknownSsid) {
            return (!pendingPrefix && ssid == pendingSsid) || ssid.startsWith(DashConfig.DEFAULT_PREFIX)
        }
        val lp = cm.getLinkProperties(network)
        val routes = (lp?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty() +
            lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty())
        if (routes.any { it.startsWith("192.168.1.") || it.startsWith("192.168.43.") }) return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false) != null
    }

    private fun handleAvailable(network: Network) {
        if (!isLikelyDashNetwork(network)) {
            Log.d(TAG, "Ignoring non-dash Wi-Fi network $network during background watch")
            return
        }
        this@DashWifiManager.network = network
        everConnected = true
        reconnectJob?.cancel()
        val resolved = resolveSsid(network).takeIf { it.isNotBlank() }
            ?: findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false)
            ?: pendingSsid
        Log.i(TAG, "WiFi connected ✓  ssid='$resolved' network=$network")
        if (resolved.isNotBlank() && !resolved.endsWith("_")) {
            resolvedSsid = resolved
            onSsidResolved?.invoke(resolved)
        }
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
    }

    private fun handleCaps(network: Network, caps: NetworkCapabilities) {
        if (!isLikelyDashNetwork(network)) return
        val info = caps.transportInfo as? WifiInfo
        val rawSsid = info?.ssid.orEmpty().trim('"')
        val ssid = if (rawSsid.isBlank() || rawSsid == WifiManagerUnknownSsid) {
            findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false).orEmpty()
        } else rawSsid
        if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) return
        if (ssid == resolvedSsid && this@DashWifiManager.network == network) return
        resolvedSsid = ssid
        this@DashWifiManager.network = network
        Log.i(TAG, "Resolved dash SSID: '$ssid'")
        onSsidResolved?.invoke(ssid)
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
    }

    private fun handleUnavailable() {
        // If we are already connected via the passive WifiNetworkSuggestion path, ignore
        // a failure from the parallel WifiNetworkSpecifier request!
        if (this@DashWifiManager.network != null && _state.value.status == WifiConnStatus.CONNECTED) {
            return
        }
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
        if (this@DashWifiManager.network != null && this@DashWifiManager.network != network) return
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
        return findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false).orEmpty()
    }

    private fun release() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        passiveBgCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        passiveBgCallback = null
        network = null
    }
}
