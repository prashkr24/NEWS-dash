package com.example.northstar.dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import com.example.northstar.data.FastReconnectPrefs

/**
 * EXPERIMENTAL background auto-join (Option 2), opt-in via [FastReconnectPrefs].
 *
 * Uses Android's WifiNetworkSuggestion API (API 29+): we hand the OS a suggestion for the
 * known dash SSID, and after a ONE-TIME user approval Android auto-associates whenever the
 * hotspot is in range — even with the app closed. This is Google's sanctioned "auto-connect
 * to my device" path, unlike the always-a-dialog WifiNetworkSpecifier used for the reliable
 * foreground flow.
 *
 * Why it stays opt-in and separate:
 *  - OEM inconsistency: some skins still prompt once per boot or throttle suggestions.
 *  - The dash hotspot has NO internet; Android can deprioritise such networks. We flag the
 *    suggestion accordingly, but real behaviour must be verified per phone.
 *  - It does NOT replace the connection path — once Android joins the hotspot, the normal
 *    DashWifiManager/DashSession still binds sockets and authenticates. This only removes
 *    the manual "get the phone onto the hotspot" step.
 *
 * Requires the exact SSID (learned on first manual connect and stored in DashConfig); a
 * prefix can't be suggested, only a concrete network.
 */
object FastReconnectManager {
    private const val TAG = "FastReconnect"

    private fun wifi(context: Context) =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** True on devices new enough for the Suggestion API. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Register (or refresh) the dash suggestion if fast-reconnect is enabled and we know the
     * exact SSID. Safe to call repeatedly (idempotent-ish: removes any prior copy first).
     * Returns a human status for the UI.
     */
    @SuppressLint("MissingPermission")
    fun sync(context: Context): String {
        if (!supported) return "Needs Android 10 or newer"
        val cfg = DashConfig(context)
        val ssid = cfg.ssid
        if (!FastReconnectPrefs.enabled(context)) {
            clear(context)
            return "Off"
        }
        if (ssid.isBlank()) {
            return "Connect once first so WayDash learns your dash's network name"
        }
        return try {
            val suggestion = buildSuggestion(ssid, cfg.password)
            val w = wifi(context)
            // Remove any previous copy, then add fresh, so a changed password/SSID takes effect.
            runCatching { w.removeNetworkSuggestions(listOf(suggestion)) }
            val status = w.addNetworkSuggestions(listOf(suggestion))
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                FastReconnectPrefs.setSuggestionRegistered(context, true)
                Log.i(TAG, "suggestion registered for '$ssid'")
                "On · Android will auto-join $ssid when in range"
            } else {
                Log.w(TAG, "addNetworkSuggestions status=$status")
                "Couldn't register (code $status) — approve the prompt or retry"
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /** Remove our suggestion (called when the rider turns fast-reconnect off). */
    @SuppressLint("MissingPermission")
    fun clear(context: Context) {
        if (!supported) return
        val cfg = DashConfig(context)
        if (cfg.ssid.isBlank()) return
        runCatching {
            wifi(context).removeNetworkSuggestions(listOf(buildSuggestion(cfg.ssid, cfg.password)))
            FastReconnectPrefs.setSuggestionRegistered(context, false)
            Log.i(TAG, "suggestion cleared")
        }
    }

    private fun buildSuggestion(ssid: String, password: String): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(false)
        if (password.isNotBlank()) b.setWpa2Passphrase(password)
        // The dash AP has no internet; without this Android may refuse to stay on it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setIsInitialAutojoinEnabled(true)
        }
        return b.build()
    }
}
