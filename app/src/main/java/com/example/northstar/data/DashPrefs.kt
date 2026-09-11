package com.example.northstar.data

import android.content.Context

/**
 * Small persistent store for dash view preferences that should survive app close and
 * app upgrades. Backed by SharedPreferences (survives process death and version updates;
 * only cleared on uninstall or "clear data").
 */
object DashPrefs {
    private const val PREFS = "dash_prefs"
    private const val KEY_ZOOM = "map_zoom"
    private const val KEY_AUTOCONNECT = "auto_connect"
    private const val KEY_TILTED_VIEW = "tilted_view"

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
}
