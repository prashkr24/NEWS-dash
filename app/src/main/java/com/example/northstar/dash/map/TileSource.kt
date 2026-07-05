package com.example.northstar.dash.map

import java.util.Locale
import kotlin.math.atan
import kotlin.math.sinh

/**
 * A pluggable raster-tile source for the dash map.
 *
 * The dash renderer consumes standard 256 px XYZ Web-Mercator tiles. Each source gets its
 * own disk-cache namespace so switching providers never mixes tile styles, and its own
 * attribution string which the renderer draws (a licensing requirement for Mappls).
 */
enum class TileSource(
    val id: String,
    val displayName: String,
    /** Short attribution the renderer draws bottom-right of the dash frame. */
    val attribution: String,
    /** Disk cache subdirectory + memory-cache key prefix. Bump suffix to invalidate. */
    val cacheNamespace: String,
    val requiresApiKey: Boolean,
    /** Highest zoom the provider serves; TileProvider synthesizes deeper zooms by upscaling. */
    val maxZoom: Int,
) {
    GOOGLE(
        id = "google",
        displayName = "Google Maps",
        attribution = "© Google",
        cacheNamespace = "tiles_gmaps_v1", // unchanged → existing cached tiles stay valid
        requiresApiKey = false,
        maxZoom = 20,
    ) {
        override fun url(z: Int, x: Int, y: Int, apiKey: String?): String =
            // Google's tile endpoint, keyless. Used for a PERSONAL, single-user,
            // non-distributed build (same caveat as upstream NorthStar). The compliant
            // path for any public release is Google's Map Tiles API.
            "https://mt1.google.com/vt/lyrs=m&hl=en&z=$z&x=$x&y=$y"
    },

    MAPPLS(
        id = "mappls",
        displayName = "Mappls · Beta",
        attribution = "© Mappls | MapmyIndia",
        cacheNamespace = "tiles_mappls_v2", // v2: still_image-based tiles
        requiresApiKey = true,
        maxZoom = 18, // Still Image API zoom range is 4–18 per Mappls docs
    ) {
        override fun url(z: Int, x: Int, y: Int, apiKey: String?): String {
            // Mappls' DOCUMENTED Still Image API, used as a tile server: a 256×256 image
            // at zoom z centred on the tile's centre IS that XYZ tile in Web Mercator.
            // Docs: github.com/MapmyIndia/mapmyindia-rest-api (still-map-image-api).
            val n = 1 shl z
            val lng = (x + 0.5) / n * 360.0 - 180.0
            val lat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (y + 0.5) / n))))
            return String.format(
                Locale.US,
                "https://apis.mapmyindia.com/advancedmaps/v1/%s/still_image?center=%.6f,%.6f&zoom=%d&size=256x256&ssf=0",
                apiKey ?: "", lat, lng, z,
            )
        }

        override fun blockUrl(z: Int, bx: Int, by: Int, apiKey: String?): String {
            // Centre = the grid POINT (bx+1, by+1) where the 2×2 block's four tiles meet.
            val n = 1 shl z
            val lng = (bx + 1.0) / n * 360.0 - 180.0
            val lat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (by + 1.0) / n))))
            return String.format(
                Locale.US,
                "https://apis.mapmyindia.com/advancedmaps/v1/%s/still_image?center=%.6f,%.6f&zoom=%d&size=512x512&ssf=0",
                apiKey ?: "", lat, lng, z,
            )
        }
    };

    /** Build the fetch URL for one tile. [apiKey] is ignored by keyless sources. */
    abstract fun url(z: Int, x: Int, y: Int, apiKey: String? = null): String

    /**
     * Optional batched fetch: URL for ONE image covering the 2×2 tile block anchored at even
     * coords ([bx],[by]) — a 512×512 still image centred on the corner where the four tiles
     * meet. Returns null for sources that serve true XYZ tiles (no batching needed).
     */
    open fun blockUrl(z: Int, bx: Int, by: Int, apiKey: String? = null): String? = null

    companion object {
        fun fromId(id: String?): TileSource =
            entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}
