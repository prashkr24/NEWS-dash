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
    private const val MANUAL_DISCONNECT_COOLDOWN_MS = 45_000L

    @Volatile private var scanCallbackRegistered = false
    @Volatile private var lastManualDisconnectMs = 0L
    @Volatile private var lastAutoTriggerMs = 0L

    fun notifyManualDisconnect() {
        lastManualDisconnectMs = System.currentTimeMillis()
    }

    fun clearManualDisconnectCooldown() {
        lastManualDisconnectMs = 0L
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
            return "Active · scanning for any ${cfg.ssidPrefix}* bike hotspot"
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
                "Active · auto-links to $ssid like Wireless Android Auto"
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
        val interactive = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(true)
            .apply {
                if (password.isNotBlank()) setWpa2Passphrase(password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setIsInitialAutojoinEnabled(true)
                }
            }
            .build()
        return listOf(interactive)
    }

    /**
     * Registers a zero-battery passive listener on OS Wi-Fi scans (API 30+).
     * Whenever the phone's Wi-Fi chip completes any background or foreground scan and sees
     * the bike's `RE_*` hotspot, we trigger the Dash connection automatically.
     */
    @SuppressLint("MissingPermission")
    fun registerPassiveScanWatcher(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (scanCallbackRegistered) return
        val appCtx = context.applicationContext
        val w = wifi(appCtx)
        try {
            w.registerScanResultsCallback(
                Executors.newSingleThreadExecutor(),
                object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        checkScanAndAutoConnect(appCtx)
                    }
                }
            )
            scanCallbackRegistered = true
            Log.i(TAG, "Passive Wi-Fi scan watcher registered")
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
        if (now - lastAutoTriggerMs < 8_000L) return

        val cfg = DashConfig.get(appCtx)
        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return

        val foundSsid = try {
            val results = w.scanResults.mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } }
            if (cfg.ssid.isNotBlank() && results.any { it == cfg.ssid }) {
                cfg.ssid
            } else {
                results.firstOrNull { it.startsWith(cfg.ssidPrefix) }
            }
        } catch (_: Exception) {
            null
        } ?: return

        val vm = DashViewModel.getInstance(appCtx)
        val stage = vm.ui.value.stage
        if (stage == ConnStage.OFFLINE || stage == ConnStage.ERROR) {
            lastAutoTriggerMs = now
            Log.i(TAG, "Bike hotspot '$foundSsid' detected in range → triggering background auto-connect")
            if (cfg.ssid.isBlank()) cfg.ssid = foundSsid
            vm.autoConnectFromBackground()
        }
    }
}
