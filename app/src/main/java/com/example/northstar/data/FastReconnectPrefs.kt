package com.example.northstar.data

import android.content.Context

/**
 * Opt-in preference for the EXPERIMENTAL background auto-join (Option 2).
 *
 * When on, WayDash registers a WifiNetworkSuggestion for the known dash SSID so Android
 * itself auto-joins the bike hotspot whenever it's in range — no dialog, no app open,
 * after a one-time approval. Off by default; the reliable foreground retry (Option 1)
 * always runs regardless. Kept separate because the Suggestion API behaves inconsistently
 * across OEMs and must be validated per-device before trusting.
 */
object FastReconnectPrefs {
    private const val PREFS = "fast_reconnect_prefs"
    private const val KEY_ENABLED = "fast_reconnect_enabled"
    private const val KEY_SUGGESTED = "suggestion_registered"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Whether we've already handed Android our suggestion this install (avoid re-adding). */
    fun suggestionRegistered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUGGESTED, false)

    fun setSuggestionRegistered(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUGGESTED, on).apply()
    }
}
