package com.example.northstar.data

import android.content.Context

/**
 * Small persistent store for dash view & v2 preferences that survive app close and
 * app upgrades. Backed by SharedPreferences.
 */
object DashPrefs {
    private const val PREFS = "dash_prefs"
    private const val KEY_ZOOM = "map_zoom"
    private const val KEY_AUTOCONNECT = "auto_connect"
    private const val KEY_WIRELESS_AA = "wireless_auto_connect"
    private const val KEY_TILTED_VIEW = "tilted_view"
    private const val KEY_UI_VERSION = "ui_version"
    private const val KEY_UNITS_KM = "units_km"
    private const val KEY_WIFI_SAVED_SSID = "wifi_saved_ssid"

    private const val ZOOM_MIN = 11
    private const val ZOOM_MAX = 20
    const val ZOOM_DEFAULT = 19

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun zoom(context: Context): Int =
        prefs(context).getInt(KEY_ZOOM, ZOOM_DEFAULT).coerceIn(ZOOM_MIN, ZOOM_MAX)

    fun setZoom(context: Context, zoom: Int) {
        prefs(context).edit().putInt(KEY_ZOOM, zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)).apply()
    }

    /** Tilted 3D perspective view for navigation heading-up map. Default on. */
    fun tiltedView(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TILTED_VIEW, true)

    fun setTiltedView(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TILTED_VIEW, on).apply()
    }

    /** Auto-connect: keep retrying the dash link while a relevant screen is open. Default on. */
    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOCONNECT, true)

    fun setAutoConnect(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOCONNECT, on).apply()
    }

    /**
     * Wireless Android-Auto style background auto-connect: automatically detects the bike's
     * RE_* Wi-Fi hotspot in the background and starts streaming without needing to open the app.
     */
    fun wirelessAutoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIRELESS_AA, true)

    fun setWirelessAutoConnect(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIRELESS_AA, on).apply()
    }

    /** UI version: 2 = WayDash v2 (Modern Search-First Cockpit), 1 = Classic v1 UI. */
    fun uiVersion(context: Context): Int =
        prefs(context).getInt(KEY_UI_VERSION, 2)

    fun setUiVersion(context: Context, version: Int) {
        prefs(context).edit().putInt(KEY_UI_VERSION, version).apply()
    }

    /** Distance units: true = Kilometres, false = Miles. */
    fun unitsKm(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNITS_KM, true)

    fun setUnitsKm(context: Context, km: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNITS_KM, km).apply()
    }

    /** SSID saved to Android's OS Saved Wi-Fi list via ACTION_WIFI_ADD_NETWORKS (empty if not yet saved). */
    fun wifiSavedSsid(context: Context): String =
        prefs(context).getString(KEY_WIFI_SAVED_SSID, "") ?: ""

    fun setWifiSavedSsid(context: Context, ssid: String) {
        prefs(context).edit().putString(KEY_WIFI_SAVED_SSID, ssid.trim()).apply()
    }
}
