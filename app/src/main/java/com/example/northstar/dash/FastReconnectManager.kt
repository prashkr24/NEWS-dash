package com.example.northstar.dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import com.example.northstar.data.DashPrefs
import com.example.northstar.viewmodel.ConnStage
import com.example.northstar.viewmodel.DashViewModel
import java.util.concurrent.Executors

/**
 * Wireless Android-Auto style background auto-connect manager.
 *
 * Combines two complementary Android mechanisms so the phone connects to the bike's
 * Tripper Dash automatically without needing to open the app:
 *  1. [WifiNetworkSuggestion] (API 29+): Tells Android OS to automatically associate with the
 *     learned `RE_*` hotspot in the background whenever the bike ignition turns on, and emit
 *     [WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION] to [DashAutoConnectReceiver].
 *  2. Passive [WifiManager.ScanResultsCallback] (API 30+): Zero-battery listener on system
 *     Wi-Fi scans; the moment an `RE_*` hotspot appears in scan results while `wirelessAutoConnect`
 *     is enabled, it wakes [DashViewModel.autoConnectFromBackground].
 */
object FastReconnectManager {
    private const val TAG = "FastReconnectMgr"
    // Cooldown after user explicitly taps Disconnect so the app doesn't immediately re-link
    private const val MANUAL_DISCONNECT_COOLDOWN_MS = 120_000L
    // Cooldown after a ride session ends or gives up so residual cached scans don't re-wake the app
    private const val SESSION_END_COOLDOWN_MS = 45_000L
    // Maximum age of a Wi-Fi ScanResult to be considered "live" (15 seconds in microseconds)
    private const val MAX_SCAN_RESULT_AGE_US = 15_000_000L
    // Minimum RSSI (dBm) to trigger auto-connect so weak ghost beacons don't wake the app
    private const val MIN_SCAN_RSSI_DBM = -82

    @Volatile private var scanCallbackRegistered = false
    @Volatile private var lastManualDisconnectMs = 0L
    @Volatile private var lastSessionEndMs = 0L
    @Volatile private var lastAutoTriggerMs = 0L
    @Volatile private var lastTriggeredScanTimestampUs = 0L

    private val scanExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
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

    private fun wifi(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Synchronize the [WifiNetworkSuggestion] registration with the user's current settings
     * and learned `RE_*` SSID, and ensure the passive scan watcher is active.
     */
    @SuppressLint("MissingPermission")
    fun sync(context: Context): String {
        val appCtx = context.applicationContext
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
            val suggestions = buildSuggestions(ssid, cfg.password)
            val w = wifi(appCtx)
            runCatching { w.removeNetworkSuggestions(suggestions) }
            val status = w.addNetworkSuggestions(suggestions)
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            ) {
                Log.i(TAG, "WifiNetworkSuggestion active for '$ssid'")
                "Active · OS auto-joins $ssid even when app is closed (0% idle battery)"
            } else {
                Log.w(TAG, "addNetworkSuggestions status=$status")
                "Active · passive scan watcher enabled for $ssid"
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            "Active · passive scan watcher enabled"
        }
    }

    @SuppressLint("MissingPermission")
    fun clearSuggestions(context: Context) {
        val cfg = DashConfig.get(context.applicationContext)
        if (cfg.ssid.isBlank()) return
        runCatching {
            wifi(context).removeNetworkSuggestions(buildSuggestions(cfg.ssid, cfg.password))
        }
    }

    private fun buildSuggestions(ssid: String, password: String): List<WifiNetworkSuggestion> {
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(true)
            .apply {
                if (password.isNotBlank()) setWpa2Passphrase(password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setIsInitialAutojoinEnabled(true)
                }
            }
            .build()
        return listOf(suggestion)
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
