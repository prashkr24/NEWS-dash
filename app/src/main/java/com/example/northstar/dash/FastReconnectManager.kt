package com.example.northstar.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.northstar.data.DashPrefs
import com.example.northstar.viewmodel.ConnStage
import com.example.northstar.viewmodel.DashViewModel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wireless Android-Auto style background auto-connect manager.
 *
 * Combines three zero-battery Android mechanisms so the phone connects to the bike's
 * Tripper Dash automatically without needing to open the app:
 *  1. [WifiNetworkSuggestion] (API 29+): Tells Android OS to automatically associate with the
 *     learned `RE_*` hotspot in the background whenever the bike ignition turns on.
 *  2. Passive [ConnectivityManager.NetworkCallback] + `NETWORK_STATE_CHANGED_ACTION`:
 *     Does NOT require background location permission. The instant the phone auto-joins the
 *     bike's `RE_*` Wi-Fi (`192.168.1.x` / `RE_*`), it wakes [DashViewModel.autoConnectFromBackground].
 *  3. Passive [WifiManager.ScanResultsCallback] (API 30+): Zero-battery listener on system
 *     Wi-Fi scans; when a fresh (<15s) `RE_*` scan result appears, it triggers auto-connect.
 */
object FastReconnectManager {
    private const val TAG = "FastReconnectMgr"
    private const val UNKNOWN_SSID = "<unknown ssid>"
    // Cooldown after user explicitly taps Disconnect so the app doesn't immediately re-link
    private const val MANUAL_DISCONNECT_COOLDOWN_MS = 120_000L
    // Cooldown after a ride session ends or gives up so residual cached scans don't re-wake the app
    private const val SESSION_END_COOLDOWN_MS = 45_000L
    // Maximum age of a Wi-Fi ScanResult to be considered "live" (15 seconds in microseconds)
    private const val MAX_SCAN_RESULT_AGE_US = 15_000_000L
    // Minimum RSSI (dBm) to trigger auto-connect so weak ghost beacons don't wake the app
    private const val MIN_SCAN_RSSI_DBM = -82

    @Volatile private var scanCallbackRegistered = false
    @Volatile private var networkCallbackRegistered = false
    @Volatile private var wifiStateReceiverRegistered = false
    @Volatile private var lastManualDisconnectMs = 0L
    @Volatile private var lastSessionEndMs = 0L
    @Volatile private var lastAutoTriggerMs = 0L
    @Volatile private var lastTriggeredScanTimestampUs = 0L

    private val scanExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "waydash-passive-scan").apply { isDaemon = true }
        }
    }

    fun notifyManualDisconnect() {
        lastManualDisconnectMs = System.currentTimeMillis()
    }

    fun notifySessionEnded() {
        lastSessionEndMs = System.currentTimeMillis()
    }

    fun clearManualDisconnectCooldown() {
        lastManualDisconnectMs = 0L
        lastSessionEndMs = 0L
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun wifi(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun cm(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Called when the user opens or resumes [com.example.northstar.MainActivity].
     * Re-syncs Wi-Fi suggestions and immediately checks if the phone is ALREADY connected
     * to the bike's Wi-Fi (or sees it in fresh scan results) so the rider never has to tap Connect.
     */
    fun onAppForegrounded(context: Context) {
        val appCtx = context.applicationContext
        lastSessionEndMs = 0L
        sync(appCtx)
        scanExecutor.execute {
            checkActiveWifiAndAutoConnect(appCtx, fromForeground = true)
            checkScanAndAutoConnect(appCtx)
        }
    }

    /**
     * When the bike's Bluetooth links (`ACTION_ACL_CONNECTED`), the Tripper Dash Wi-Fi AP
     * typically finishes booting 2–10 seconds later. Schedule a few lightweight checks so
     * we catch the Wi-Fi connection the moment it comes up.
     */
    fun schedulePostBluetoothChecks(context: Context) {
        val appCtx = context.applicationContext
        sync(appCtx)
        for (delaySec in longArrayOf(0L, 2L, 5L, 9L, 14L)) {
            scanExecutor.schedule({
                checkActiveWifiAndAutoConnect(appCtx)
                checkScanAndAutoConnect(appCtx)
            }, delaySec, TimeUnit.SECONDS)
        }
    }

    /**
     * Synchronize the [WifiNetworkSuggestion] registration with the user's current settings
     * and learned `RE_*` SSID, and ensure the passive network & scan watchers are active.
     */
    @SuppressLint("MissingPermission")
    fun sync(context: Context): String {
        val appCtx = context.applicationContext
        registerPassiveNetworkWatcher(appCtx)
        registerPassiveScanWatcher(appCtx)

        if (!DashPrefs.wirelessAutoConnect(appCtx)) {
            clearSuggestions(appCtx)
            return "Off · manual or in-app connect only"
        }

        val cfg = DashConfig.get(appCtx)
        val ssid = cfg.ssid
        if (ssid.isBlank()) {
            return "Active · passive watcher waiting for first ${cfg.ssidPrefix}* pairing"
        }

        return try {
            val allPossible = buildAllSuggestionVariants(ssid, cfg.password)
            val activeSuggestions = buildSuggestions(appCtx, ssid, cfg.password)
            val w = wifi(appCtx)
            runCatching { w.removeNetworkSuggestions(allPossible) }
            val status = w.addNetworkSuggestions(activeSuggestions)
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            ) {
                Log.i(TAG, "WifiNetworkSuggestion active for '$ssid' (bgLoc=${hasBackgroundLocationPermission(appCtx)})")
                "Active · OS auto-joins $ssid even when app is closed (0% idle battery)"
            } else {
                Log.w(TAG, "addNetworkSuggestions status=$status")
                "Active · passive Wi-Fi watcher enabled for $ssid"
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            "Active · passive Wi-Fi watcher enabled"
        }
    }

    @SuppressLint("MissingPermission")
    fun clearSuggestions(context: Context) {
        val appCtx = context.applicationContext
        val cfg = DashConfig.get(appCtx)
        if (cfg.ssid.isBlank()) return
        runCatching {
            wifi(appCtx).removeNetworkSuggestions(buildAllSuggestionVariants(cfg.ssid, cfg.password))
        }
    }

    private fun buildSuggestions(context: Context, ssid: String, password: String): List<WifiNetworkSuggestion> {
        // Note: Android AOSP only delivers ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION when
        // setIsAppInteractionRequired(true) AND the app has ACCESS_BACKGROUND_LOCATION.
        // If the user has not granted ACCESS_BACKGROUND_LOCATION yet, setting false lets Android
        // silently auto-join the Wi-Fi without failing the permission check, and our passive
        // ConnectivityManager.NetworkCallback catches the connection immediately!
        val requireInteraction = hasBackgroundLocationPermission(context)
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(requireInteraction)
            .apply {
                if (password.isNotBlank()) setWpa2Passphrase(password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setIsInitialAutojoinEnabled(true)
                }
            }
            .build()
        return listOf(suggestion)
    }

    private fun buildAllSuggestionVariants(ssid: String, password: String): List<WifiNetworkSuggestion> {
        return listOf(true, false).map { interactive ->
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsAppInteractionRequired(interactive)
                .apply {
                    if (password.isNotBlank()) setWpa2Passphrase(password)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setIsInitialAutojoinEnabled(true)
                    }
                }
                .build()
        }
    }

    /**
     * Registers a zero-battery passive [ConnectivityManager.NetworkCallback] for Wi-Fi networks
     * (including non-internet hotspots like the Tripper Dash) plus a runtime
     * [WifiManager.NETWORK_STATE_CHANGED_ACTION] receiver.
     *
     * Why this is critical:
     * When the phone connects to the bike's `RE_*` Wi-Fi automatically (either as a Saved Wi-Fi
     * network or via [WifiNetworkSuggestion] when the app is in the background without background
     * location permission), Android OS does NOT send `WIFI_NETWORK_SUGGESTION_POST_CONNECTION` and
     * redacts `ScanResults`. However, [ConnectivityManager.NetworkCallback] ALWAYS fires in the
     * background without needing background location permission!
     */
    @SuppressLint("MissingPermission")
    fun registerPassiveNetworkWatcher(context: Context) {
        val appCtx = context.applicationContext
        if (!networkCallbackRegistered) {
            try {
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onAvailable(network: Network) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                    }
                }
                cm(appCtx).registerNetworkCallback(req, cb)
                networkCallbackRegistered = true
                Log.i(TAG, "Passive Wi-Fi ConnectivityManager callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "registerPassiveNetworkWatcher failed: ${e.message}")
            }
        }

        if (!wifiStateReceiverRegistered) {
            try {
                val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                appCtx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            scheduleNetworkCheck(appCtx, null)
                        }
                    },
                    filter,
                )
                wifiStateReceiverRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "registerReceiver(NETWORK_STATE_CHANGED) failed: ${e.message}")
            }
        }
    }

    private fun scheduleNetworkCheck(appCtx: Context, network: Network?) {
        scanExecutor.execute {
            checkActiveWifiAndAutoConnect(appCtx, candidateNetwork = network)
        }
        // Also re-check 1.2s later once DHCP and captive-portal internet validation settle
        scanExecutor.schedule({
            checkActiveWifiAndAutoConnect(appCtx, candidateNetwork = network, afterValidationDelay = true)
        }, 1200L, TimeUnit.MILLISECONDS)
    }

    /**
     * Checks if the phone is CURRENTLY connected to the bike's `RE_*` Wi-Fi network and starts
     * the Dash session automatically if not already connected.
     */
    @SuppressLint("MissingPermission")
    fun checkActiveWifiAndAutoConnect(
        context: Context,
        candidateNetwork: Network? = null,
        fromForeground: Boolean = false,
        afterValidationDelay: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return
        val now = System.currentTimeMillis()
        if (now - lastManualDisconnectMs < MANUAL_DISCONNECT_COOLDOWN_MS) return
        if (!fromForeground && now - lastSessionEndMs < 10_000L) return
        if (now - lastAutoTriggerMs < 5_000L) return

        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return

        val vm = DashViewModel.getInstance(appCtx)
        val stage = vm.ui.value.stage
        if (stage == ConnStage.STREAMING || stage == ConnStage.AUTH || stage == ConnStage.WIFI) return

        val cfg = DashConfig.get(appCtx)
        val connMgr = cm(appCtx)
        val networks = buildList {
            if (candidateNetwork != null) add(candidateNetwork)
            runCatching { addAll(connMgr.allNetworks) }
        }.distinct()

        for (net in networks) {
            val caps = runCatching { connMgr.getNetworkCapabilities(net) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            val info = caps.transportInfo as? WifiInfo
            val capsSsid = info?.ssid.orEmpty().trim('"')
            val connSsid = runCatching {
                @Suppress("DEPRECATION")
                w.connectionInfo?.ssid.orEmpty().trim('"')
            }.getOrDefault("")

            val readableSsid = listOf(capsSsid, connSsid).firstOrNull {
                it.isNotBlank() && it != UNKNOWN_SSID
            }

            if (readableSsid != null) {
                val isMatch = (cfg.ssid.isNotBlank() && readableSsid == cfg.ssid) ||
                    readableSsid.startsWith(cfg.ssidPrefix)
                if (isMatch) {
                    lastAutoTriggerMs = now
                    if (cfg.ssid.isBlank()) cfg.ssid = readableSsid
                    Log.i(TAG, "Phone is connected to bike Wi-Fi '$readableSsid' (net=$net) → auto-starting Dash session")
                    vm.autoConnectFromBackground()
                    return
                }
                continue
            }

            // If SSID is redacted ("<unknown ssid>") because we are in the background without
            // ACCESS_BACKGROUND_LOCATION, we can still identify the bike's Wi-Fi if:
            //  1. We already know the rider's `cfg.ssid` from their first pairing, AND
            //  2. The Wi-Fi network has NO internet (!NET_CAPABILITY_VALIDATED), AND
            //  3. Its IPv4 address / gateway is on the Tripper Dash subnet (192.168.1.x or 192.168.43.x).
            // We wait for `afterValidationDelay` (1.2s) or `fromForeground` so a home 192.168.1.x
            // Wi-Fi router has time to validate internet and be skipped.
            if (cfg.ssid.isNotBlank() &&
                (afterValidationDelay || fromForeground) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                val lp = runCatching { connMgr.getLinkProperties(net) }.getOrNull()
                val addrs = lp?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty()
                val routes = lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty()
                val subnetMatch = (addrs + routes).any {
                    it.startsWith("192.168.1.") || it.startsWith("192.168.43.")
                } || runCatching {
                    @Suppress("DEPRECATION")
                    val dhcp = w.dhcpInfo
                    if (dhcp != null && dhcp.ipAddress != 0) {
                        val b0 = dhcp.ipAddress and 0xFF
                        val b1 = (dhcp.ipAddress shr 8) and 0xFF
                        val b2 = (dhcp.ipAddress shr 16) and 0xFF
                        b0 == 192 && b1 == 168 && (b2 == 1 || b2 == 43)
                    } else false
                }.getOrDefault(false)

                if (subnetMatch) {
                    lastAutoTriggerMs = now
                    Log.i(TAG, "Phone joined non-internet Dash subnet (192.168.1.x) for '${cfg.ssid}' → auto-starting Dash session")
                    vm.autoConnectFromBackground()
                    return
                }
            }
        }
    }

    /**
     * Registers a zero-battery passive listener on OS Wi-Fi scans (API 30+).
     * Does NOT trigger any Wi-Fi scans itself — only inspects results when Android OS
     * performs its normal system scan.
     */
    @SuppressLint("MissingPermission")
    fun registerPassiveScanWatcher(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (scanCallbackRegistered) return
        val appCtx = context.applicationContext
        val w = wifi(appCtx)
        try {
            w.registerScanResultsCallback(
                scanExecutor,
                object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        checkActiveWifiAndAutoConnect(appCtx)
                        checkScanAndAutoConnect(appCtx)
                    }
                }
            )
            scanCallbackRegistered = true
            Log.i(TAG, "Passive Wi-Fi scan watcher registered (zero active polling)")
        } catch (e: Exception) {
            Log.w(TAG, "registerScanResultsCallback failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun checkScanAndAutoConnect(context: Context) {
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return
        val now = System.currentTimeMillis()
        if (now - lastManualDisconnectMs < MANUAL_DISCONNECT_COOLDOWN_MS) return
        if (now - lastSessionEndMs < SESSION_END_COOLDOWN_MS) return
        if (now - lastAutoTriggerMs < 12_000L) return

        val cfg = DashConfig.get(appCtx)
        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return

        val nowElapsedUs = android.os.SystemClock.elapsedRealtime() * 1000L
        var newestMatchTimestampUs = 0L

        val foundSsid = try {
            // Filter strictly to FRESH scan results (< 15s old) with strong signal (>= -82 dBm).
            // This prevents Android's cached scan list from re-triggering auto-connect after
            // the rider has already turned the bike key OFF.
            val freshResults = w.scanResults.filter { sr ->
                val ageUs = nowElapsedUs - sr.timestamp
                ageUs in 0..MAX_SCAN_RESULT_AGE_US &&
                    sr.level >= MIN_SCAN_RSSI_DBM &&
                    sr.timestamp > lastTriggeredScanTimestampUs
            }
            val matched = if (cfg.ssid.isNotBlank()) {
                freshResults.firstOrNull { it.SSID?.trim('"') == cfg.ssid }
            } else {
                freshResults.firstOrNull {
                    val s = it.SSID?.trim('"').orEmpty()
                    s.isNotBlank() && s.startsWith(cfg.ssidPrefix)
                }
            }
            matched?.let {
                newestMatchTimestampUs = it.timestamp
                it.SSID?.trim('"')
            }
        } catch (_: Exception) {
            null
        } ?: return

        val vm = DashViewModel.getInstance(appCtx)
        val stage = vm.ui.value.stage
        if (stage == ConnStage.OFFLINE || stage == ConnStage.ERROR) {
            lastAutoTriggerMs = now
            lastTriggeredScanTimestampUs = newestMatchTimestampUs
            Log.i(TAG, "Fresh bike hotspot '$foundSsid' detected (<15s old) → triggering background auto-connect")
            if (cfg.ssid.isBlank()) cfg.ssid = foundSsid
            vm.autoConnectFromBackground()
        }
    }
}
