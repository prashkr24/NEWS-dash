package com.example.northstar.data

import android.content.Context

/** Routing-engine preference: OSRM (free, no traffic) or TomTom (free key, traffic-aware). */
object RoutePrefs {
    const val ENGINE_OSRM = "osrm"
    const val ENGINE_TOMTOM = "tomtom"

    private const val PREFS = "route_prefs"
    private const val KEY_ENGINE = "routing_engine"
    private const val KEY_TOMTOM_KEY = "tomtom_api_key"

    // Rider's own TomTom key baked in as the default (private build). Free tier:
    // 2,500 routing requests/day, no card. Overridable from Settings.
    private const val DEFAULT_TOMTOM_KEY = "mTgwTbHDw7HApveaxrTVYLUoxAPiR2DJ"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engine(context: Context): String =
        prefs(context).getString(KEY_ENGINE, ENGINE_TOMTOM) ?: ENGINE_TOMTOM

    fun setEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun tomtomKey(context: Context): String? =
        prefs(context).getString(KEY_TOMTOM_KEY, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TOMTOM_KEY.takeIf { it.isNotBlank() }

    fun setTomtomKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_TOMTOM_KEY, key.trim()).apply()
    }
}
