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
    // Active (non-specifier) REQUEST keeping Android ConnectivityService from tearing down the
    // bike's no-internet Wi-Fi as "unwanted" after captive-portal validation fails.
    private var holdNetworkCallback: ConnectivityManager.NetworkCallback? = null
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
     * automatically in the background via [android.net.wifi.WifiNetworkSuggestion] or Saved Wi-Fi
     * while the app UI is completely closed).
     */
    @SuppressLint("MissingPermission")
    fun findExistingDashNetwork(): Network? {
        return try {
            for (net in cm.allNetworks) {
                if (isLikelyDashNetwork(net, checkStrict = true)) {
                    return net
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

    /**
     * Nudges the Wi-Fi manager while waiting in [WifiConnStatus.REQUESTING] (for example when
     * `ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION` or [FastReconnectManager]'s callback fires)
     * so an OS-joined `RE_*` network is promoted to [WifiConnStatus.CONNECTED] in 0ms.
     */
    fun recheckActiveNetworks() {
        if (!wantConnected || _state.value.status == WifiConnStatus.CONNECTED) return
        findExistingDashNetwork()?.let { net ->
            Log.i(TAG, "recheckActiveNetworks adopted Dash Wi-Fi network: $net")
            promoteToConnected(net)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun buildCallback(): ConnectivityManager.NetworkCallback =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCaps(network, caps)
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                    handleLinkProperties(network, lp)
                override fun onUnavailable() = handleUnavailable()
                override fun onLost(network: Network) = handleLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCaps(network, caps)
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                    handleLinkProperties(network, lp)
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

        val passiveRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // 1. Check if the phone is ALREADY connected to the bike's Wi-Fi network (via OS
        // WifiNetworkSuggestion or Android Saved Wi-Fi). This works in both foreground and
        // background and avoids popping up a redundant system companion picker dialog.
        val existingNet = findExistingDashNetwork()
        if (existingNet != null) {
            Log.i(TAG, "Adopted already-connected bike Wi-Fi network: $existingNet")
            val passiveCb = buildCallback()
            passiveBgCallback = passiveCb
            runCatching { cm.registerNetworkCallback(passiveRequest, passiveCb) }
            promoteToConnected(existingNet)
            return
        }

        // 2. When connecting headlessly from the background (screen off / app closed):
        //  • Do NOT pass a WifiNetworkSpecifier (Android 10–16 immediately rejects specifier
        //    requests from background apps with onUnavailable()).
        //  • DO acquire ensureWifiHoldLease() (specifier-free requestNetwork for TRANSPORT_WIFI
        //    without NET_CAPABILITY_INTERNET) right away: this tells Android ConnectivityService
        //    an app actively wants non-internet Wi-Fi, prompting WifiNetworkSelector to auto-join
        //    our no-internet RE_* WifiNetworkSuggestion / Saved Wi-Fi even while mobile data is up!
        //  • Poll across the full 42s Tripper boot/DHCP window so late DHCP completion is caught.
        if (pendingFromBackground) {
            ensureWifiHoldLease()
            val passiveCb = buildCallback()
            passiveBgCallback = passiveCb
            try {
                cm.registerNetworkCallback(passiveRequest, passiveCb)
                Log.i(TAG, "Registered background Wi-Fi demand + callback for '$pendingSsid'")
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    repeat(56) {
                        delay(750)
                        if (!wantConnected || _state.value.status == WifiConnStatus.CONNECTED) return@launch
                        findExistingDashNetwork()?.let { net ->
                            Log.i(TAG, "Background poll found active Dash Wi-Fi network: $net")
                            promoteToConnected(net)
                            return@launch
                        }
                    }
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "registerNetworkCallback failed in background: ${e.message}")
            }
        }

        // 3. Foreground connection path (when the bike's Wi-Fi isn't already joined):
        // Also keep a passive callback active so if the OS auto-joins the network as a saved/suggested
        // network while the specifier request is pending, we still adopt it immediately.
        val passiveCb = buildCallback()
        passiveBgCallback = passiveCb
        runCatching { cm.registerNetworkCallback(passiveRequest, passiveCb) }

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
            Log.w(TAG, "requestNetwork specifier threw: ${e.message}")
            if (passiveBgCallback == null) {
                _state.value = WifiState(
                    status = WifiConnStatus.ERROR,
                    ssid   = pendingSsid,
                    error  = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun isLikelyDashNetwork(network: Network, checkStrict: Boolean = false): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false

        // 1. Check SSID from NetworkCapabilities.transportInfo or WifiManager.connectionInfo
        val info = caps.transportInfo as? WifiInfo
        val rawCapsSsid = info?.ssid.orEmpty().trim('"')
        val rawConnSsid = runCatching {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.ssid.orEmpty().trim('"')
        }.getOrDefault("")

        val knownSsid = listOf(rawCapsSsid, rawConnSsid).firstOrNull {
            it.isNotBlank() && it != WifiManagerUnknownSsid
        }
        if (knownSsid != null) {
            val target = pendingSsid.ifBlank { DashConfig.get(context).ssid }
            return (target.isNotBlank() && !pendingPrefix && knownSsid == target) ||
                knownSsid.startsWith(DashConfig.DEFAULT_PREFIX)
        }

        // 2. If SSID is redacted by Android ("<unknown ssid>" in background without background
        // location permission), inspect LinkProperties and DhcpInfo for the Tripper Dash subnet
        // (192.168.1.x or 192.168.43.x) on a Wi-Fi network WITHOUT validated internet.
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return false
        }

        val lp = cm.getLinkProperties(network)
        val addresses = lp?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty()
        val routes = lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty()
        if ((addresses + routes).any { it.startsWith("192.168.1.") || it.startsWith("192.168.43.") }) {
            return true
        }

        // Check DhcpInfo gateway/ipAddress (requires only ACCESS_WIFI_STATE, never redacted in background)
        val dhcpMatch = runCatching {
            @Suppress("DEPRECATION")
            val dhcp = wifi.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val ipB0 = dhcp.ipAddress and 0xFF
                val ipB1 = (dhcp.ipAddress shr 8) and 0xFF
                val ipB2 = (dhcp.ipAddress shr 16) and 0xFF
                ipB0 == 192 && ipB1 == 168 && (ipB2 == 1 || ipB2 == 43)
            } else false
        }.getOrDefault(false)
        if (dhcpMatch) return true

        if (checkStrict) return false
        return findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false) != null
    }

    /**
     * Acquires a specifier-free `NetworkRequest` (`TRANSPORT_WIFI` without `NET_CAPABILITY_INTERNET`)
     * so Android's `ConnectivityService` sees an active requester for the bike's no-internet Wi-Fi
     * and does NOT tear down the connection after captive-portal validation fails.
     * Because there is no `WifiNetworkSpecifier`, this is allowed from background services without
     * any system dialog and keeps cellular active as the default internet route.
     */
    private fun ensureWifiHoldLease() {
        if (holdNetworkCallback != null) return
        val holdReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                Log.d(TAG, "Active non-internet Wi-Fi hold lease confirmed on $net")
                if (wantConnected && _state.value.status != WifiConnStatus.CONNECTED) {
                    handleAvailable(net)
                }
            }
            override fun onLost(net: Network) {
                handleLost(net)
            }
        }
        holdNetworkCallback = cb
        runCatching {
            cm.requestNetwork(holdReq, cb)
            Log.i(TAG, "Acquired active non-internet Wi-Fi hold lease (prevents OS no-internet drop)")
        }.onFailure { e ->
            holdNetworkCallback = null
            Log.w(TAG, "Could not acquire Wi-Fi hold lease: ${e.message}")
        }
    }

    private fun promoteToConnected(network: Network) {
        val prevNet = this@DashWifiManager.network
        val alreadyConnectedSameNet = prevNet == network && _state.value.status == WifiConnStatus.CONNECTED
        this@DashWifiManager.network = network
        everConnected = true
        ensureWifiHoldLease()

        val savedSsid = DashConfig.get(context).ssid
        val directPending = pendingSsid.takeIf { !pendingPrefix && it.isNotBlank() && !it.endsWith("_") }
        val resolved = resolveSsid(network).takeIf { it.isNotBlank() }
            ?: directPending
            ?: savedSsid.takeIf { it.isNotBlank() }
            ?: findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false)?.takeIf { it.isNotBlank() }
            ?: pendingSsid

        if (resolved.isNotBlank() && !resolved.endsWith("_") && resolved != resolvedSsid) {
            resolvedSsid = resolved
            onSsidResolved?.invoke(resolved)
        }

        if (!alreadyConnectedSameNet || _state.value.ssid != resolved) {
            Log.i(TAG, "WiFi connected ✓  ssid='$resolved' network=$network (bg=$pendingFromBackground)")
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
        }
    }

    private fun hasUnredactedDashSsid(network: Network): Boolean {
        val s = resolveSsid(network)
        if (s.isBlank()) return false
        val target = pendingSsid.ifBlank { DashConfig.get(context).ssid }
        return (target.isNotBlank() && !pendingPrefix && s == target) ||
            s.startsWith(DashConfig.DEFAULT_PREFIX)
    }

    private fun handleAvailable(network: Network) {
        // If we can read the unredacted RE_* SSID immediately, promote right away.
        if (hasUnredactedDashSsid(network)) {
            promoteToConnected(network)
            return
        }
        // Otherwise wait briefly for Tripper DHCP (192.168.1.x) AND give home 192.168.1.1 routers
        // ~1s to pass internet validation so we never mistake home Wi-Fi for a redacted-SSID Dash!
        scope.launch {
            for (waitMs in longArrayOf(400L, 600L, 900L, 1400L, 2200L)) {
                delay(waitMs)
                if (!wantConnected || _state.value.status == WifiConnStatus.CONNECTED) return@launch
                if (isLikelyDashNetwork(network, checkStrict = true)) {
                    Log.i(TAG, "Wi-Fi network $network verified as Dash after DHCP/validation settle")
                    promoteToConnected(network)
                    return@launch
                }
            }
        }
    }

    private fun handleLinkProperties(network: Network, lp: android.net.LinkProperties) {
        if (!wantConnected) return
        val addrs = lp.linkAddresses.mapNotNull { it.address?.hostAddress }
        Log.d(TAG, "onLinkPropertiesChanged net=$network addrs=$addrs")
        if (hasUnredactedDashSsid(network)) {
            promoteToConnected(network)
        }
    }

    private fun handleCaps(network: Network, caps: NetworkCapabilities) {
        if (!wantConnected) return
        if (hasUnredactedDashSsid(network)) {
            promoteToConnected(network)
        }
    }

    private fun handleUnavailable() {
        // If we are already connected via the passive WifiNetworkSuggestion path, ignore
        // a failure from the parallel WifiNetworkSpecifier request!
        if (this@DashWifiManager.network != null && _state.value.status == WifiConnStatus.CONNECTED) {
            return
        }
        // If another active Wi-Fi network is already the Dash network, adopt it instead of failing!
        findExistingDashNetwork()?.let { net ->
            promoteToConnected(net)
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
        val activeNet = this@DashWifiManager.network
        // Strictly ignore onLost from any other Wi-Fi (e.g., home Wi-Fi dropping during handoff)
        if (activeNet == null || activeNet != network) return
        Log.w(TAG, "Dash WiFi link lost ($network) — reconnecting in ${RECONNECT_DELAY}ms")
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
        val fromConn = runCatching {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it != WifiManagerUnknownSsid }
        }.getOrNull().orEmpty()
        if (fromConn.isNotBlank()) return fromConn
        return findDashSsid(DashConfig.DEFAULT_PREFIX, triggerActiveScan = false).orEmpty()
    }

    private fun release() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        passiveBgCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        passiveBgCallback = null
        holdNetworkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        holdNetworkCallback = null
        network = null
    }
}
