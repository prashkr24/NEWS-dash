package com.example.northstar.data

import android.content.Context
import com.example.northstar.dash.map.TileSource

/**
 * Rider's map-provider choice for the dash renderer, plus the Mappls REST key.
 * Same SharedPreferences pattern as [AuthPrefs].
 */
object MapPrefs {
    private const val PREFS = "map_prefs"
    private const val KEY_TILE_SOURCE = "tile_source"
    private const val KEY_MAPPLS_KEY = "mappls_rest_key"

    // Rider's own Mappls REST key, baked in as the default so the phone needs no typing.
    // Still overridable from Settings → Dash map → Mappls → "Change REST key".
    private const val DEFAULT_MAPPLS_KEY = "b21986d499090bb09cec83078eb22f29"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tileSource(context: Context): TileSource =
        TileSource.fromId(prefs(context).getString(KEY_TILE_SOURCE, null))

    fun setTileSource(context: Context, source: TileSource) {
        prefs(context).edit().putString(KEY_TILE_SOURCE, source.id).apply()
    }

    /** Raw handle for change listeners (TileProvider self-wires through this). */
    fun raw(context: Context) = prefs(context)

    fun mapplsKey(context: Context): String? =
        prefs(context).getString(KEY_MAPPLS_KEY, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MAPPLS_KEY.takeIf { it.isNotBlank() }

    fun setMapplsKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_MAPPLS_KEY, key.trim()).apply()
    }

    /**
     * The source that should actually be used for rendering: falls back to Google if
     * Mappls is selected but no key has been entered yet, so the dash never goes blank.
     */
    fun effectiveTileSource(context: Context): TileSource {
        val chosen = tileSource(context)
        return if (chosen.requiresApiKey && mapplsKey(context) == null) TileSource.GOOGLE else chosen
    }
}
