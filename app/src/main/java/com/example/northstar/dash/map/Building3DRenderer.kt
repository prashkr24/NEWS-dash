package com.example.northstar.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import com.example.northstar.dash.nav.GeoPoint
import java.util.Locale

/**
 * EXPERIMENTAL 3D-buildings dash view. Renders the map off-screen with MapLibre's
 * [MapSnapshotter] using the OpenFreeMap Liberty vector style, a tilted camera, and a
 * fill-extrusion buildings layer — producing the raised-building look flat raster tiles
 * can never give (see the note in DashViewModel.redrawFrame about why tilt on tiles fails).
 *
 * Design constraints that make this safe to ship as opt-in:
 *  - The snapshotter must be driven on the MAIN thread (MapLibre requirement); we post all
 *    calls to the main looper regardless of caller thread.
 *  - Only ONE snapshot runs at a time; requestFor() while busy is dropped (we keep the last
 *    good frame instead of queueing — the dash streams at a few fps so this is plenty).
 *  - The renderer holds the LAST successful bitmap. DashViewModel draws it only if present;
 *    if the 3D path never produces a frame, the caller falls back to flat tiles. The video
 *    stream therefore CANNOT break because of this class.
 */
class Building3DRenderer(
    private val context: Context,
    private val widthPx: Int,
    private val heightPx: Int,
) {
    @Volatile var latestBitmap: Bitmap? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var snapshotter: MapSnapshotter? = null
    @Volatile private var busy = false
    @Volatile private var styleReady = false

    // Requested camera, updated every frame by the caller; the snapshot loop reads the latest.
    @Volatile private var reqLat = 0.0
    @Volatile private var reqLng = 0.0
    @Volatile private var reqZoom = 17.0
    @Volatile private var reqBearing = 0.0

    // Route baked into the style as GeoJSON: a pitched camera can't be overlaid with
    // flat-Mercator route drawing, so MapLibre renders the line in-scene instead.
    @Volatile private var routeCoordsJson: String? = null
    private var lastRouteRef: List<GeoPoint>? = null

    /** Update the desired camera + route (cheap, any thread). Kick a render if idle. */
    fun requestFor(lat: Double, lng: Double, zoom: Int, bearingDeg: Float, route: List<GeoPoint>) {
        reqLat = lat; reqLng = lng
        reqZoom = zoom.toDouble().coerceIn(14.0, 19.0)  // extrusion only meaningful mid/high zoom
        reqBearing = bearingDeg.toDouble()
        if (route !== lastRouteRef) {   // same list instance -> JSON already built
            lastRouteRef = route
            routeCoordsJson = if (route.size >= 2)
                route.joinToString(",", prefix = "[", postfix = "]") {
                    "[%.6f,%.6f]".format(Locale.US, it.lng, it.lat)
                }
            else null
        }
        if (!busy) main.post { renderOnce() }
    }

    private fun renderOnce() {
        if (busy) return
        busy = true
        try {
            val cam = CameraPosition.Builder()
                .target(LatLng(reqLat, reqLng))
                .zoom(reqZoom)
                .tilt(55.0)                 // the raised-building angle
                .bearing(reqBearing)        // heading-up
                .build()

            val options = MapSnapshotter.Options(widthPx, heightPx)
                .withPixelRatio(1f)
                .withStyleBuilder(Style.Builder().fromJson(styleJson()))
                .withCameraPosition(cam)
                .withLogo(false)

            // Recreate per render: snapshotter isn't reliably reusable across camera changes
            // in 11.x, and creating it is cheap relative to a several-hundred-ms render.
            snapshotter?.cancel()
            snapshotter = MapSnapshotter(context, options)
            snapshotter?.start({ snapshot ->
                latestBitmap = snapshot.bitmap
                styleReady = true
                busy = false
            }, { err ->
                Log.w(TAG, "snapshot failed: $err")
                busy = false
            })
        } catch (e: Throwable) {
            Log.w(TAG, "renderOnce threw: ${e.message}")
            busy = false
        }
    }

    fun destroy() {
        main.post {
            runCatching { snapshotter?.cancel() }
            snapshotter = null
            latestBitmap = null
        }
    }

    /**
     * Liberty vector style + an extra fill-extrusion layer for buildings. We inject the
     * extrusion layer into the style JSON so buildings rise by their `render_height`.
     * Colors are dark to match the dash's night aesthetic.
     */
    private fun styleJson(): String {
        // Minimal OpenMapTiles-schema style over OpenFreeMap's vector tiles (same host the
        // phone preview's Liberty style uses). Dark palette to match the dash aesthetic;
        // buildings-3d extrudes by render_height; the active route (when present) is a
        // GeoJSON source rendered in-scene so it aligns under the pitched camera.
        val routeSource = routeCoordsJson?.let {
            """,
            "route": {
              "type": "geojson",
              "data": { "type": "Feature", "properties": {},
                        "geometry": { "type": "LineString", "coordinates": $it } }
            }"""
        } ?: ""
        val routeLayers = if (routeCoordsJson != null) """,
            { "id": "route-casing", "type": "line", "source": "route",
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": { "line-color": "#FFFFFF", "line-width": 9 } },
            { "id": "route-line", "type": "line", "source": "route",
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": { "line-color": "#2E7CF6", "line-width": 6 } }""" else ""

        return """
        {
          "version": 8,
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "url": "https://tiles.openfreemap.org/planet"
            }$routeSource
          },
          "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#0A111C" } },
            {
              "id": "water", "type": "fill", "source": "openmaptiles",
              "source-layer": "water", "paint": { "fill-color": "#0E2233" }
            },
            {
              "id": "roads", "type": "line", "source": "openmaptiles",
              "source-layer": "transportation",
              "paint": { "line-color": "#2A3A52", "line-width": 1.5 }
            },
            {
              "id": "buildings-3d", "type": "fill-extrusion", "source": "openmaptiles",
              "source-layer": "building", "minzoom": 14,
              "paint": {
                "fill-extrusion-color": "#24406B",
                "fill-extrusion-height": ["get", "render_height"],
                "fill-extrusion-base": ["get", "render_min_height"],
                "fill-extrusion-opacity": 0.9
              }
            }$routeLayers
          ]
        }
        """.trimIndent()
    }

    companion object { private const val TAG = "Building3DRenderer" }
}
