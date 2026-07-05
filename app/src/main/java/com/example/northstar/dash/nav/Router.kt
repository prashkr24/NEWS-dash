package com.example.northstar.dash.nav

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a road route from the public OSRM demo server. Called at planning time
 * (destination shared) while the phone still has internet — the result is cached
 * so riding can proceed offline. Driving profile suits the Himalayan fine.
 */
object Router {
    private const val TAG = "Router"
    private const val BASE = "https://router.project-osrm.org/route/v1/driving"
    private const val UA = "Northstar/1.0 (personal motorcycle nav; single user)"

    /** Best single route (first alternative) — kept for existing callers. */
    suspend fun route(from: GeoPoint, to: GeoPoint): Route? = routes(from, to).firstOrNull()

    /**
     * Up to 3 route alternatives (OSRM `alternatives`). Index 0 is OSRM's preferred
     * route; the rest are meaningfully different options the rider can pick from.
     */
    suspend fun routes(from: GeoPoint, to: GeoPoint): List<Route> = withContext(Dispatchers.IO) {
        // Primary attempt asks for alternatives; if the server rejects the parameter or
        // errors, retry the plain single-route request so routing never silently breaks.
        fetchRoutes(from, to, withAlternatives = true).ifEmpty {
            Log.w(TAG, "alternatives request empty/failed — retrying single-route")
            fetchRoutes(from, to, withAlternatives = false)
        }
    }

    private fun fetchRoutes(from: GeoPoint, to: GeoPoint, withAlternatives: Boolean): List<Route> {
        val url = "$BASE/${from.lng},${from.lat};${to.lng},${to.lat}" +
                "?overview=full&geometries=polyline&steps=true&annotations=false" +
                if (withAlternatives) "&alternatives=true" else ""
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parseAll(body).also { Log.i(TAG, "OSRM returned ${it.size} route(s) (alts=$withAlternatives)") }
        } catch (e: Exception) {
            Log.w(TAG, "fetchRoutes(alts=$withAlternatives) failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseAll(json: String): List<Route> {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            Log.w(TAG, "OSRM code=${root.optString("code")}")
            return emptyList()
        }
        val routes = root.optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { i ->
            runCatching { parseOne(routes.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun parseOne(r0: JSONObject): Route? {

        val geometry = PolylineCodec.decode(r0.getString("geometry"))
        if (geometry.size < 2) return null

        // Cumulative distance at each vertex
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        // Maneuvers from steps
        val maneuvers = ArrayList<Maneuver>()
        val legs = r0.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location") ?: continue
                    val p = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                    val type = ManeuverType.fromOsrm(man.optString("type"), man.optString("modifier"))
                    val name = step.optString("name").ifBlank { "road" }
                    // India-specific: elevated roads are almost always named "... Flyover" in
                    // OSM. Surfacing this mirrors Google Maps' "Take flyover" tooltip.
                    val flyover = name.contains("flyover", ignoreCase = true)
                    maneuvers.add(
                        Maneuver(
                            type = type,
                            instruction = if (flyover) "Take the flyover" else buildInstruction(type, name),
                            location = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                            flyover = flyover,
                        )
                    )
                }
            }
        }

        val summary = r0.optJSONArray("legs")
            ?.optJSONObject(0)?.optString("summary").orEmpty()
        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = r0.optDouble("distance", cum.last()),
            totalSeconds = r0.optDouble("duration", 0.0),
            cumulative = cum,
            summary = summary,
        )
    }

    private fun buildInstruction(type: ManeuverType, road: String): String = when (type) {
        ManeuverType.DEPART       -> "Head out on $road"
        ManeuverType.ARRIVE       -> "Arrive at destination"
        ManeuverType.TURN_LEFT    -> "Turn left onto $road"
        ManeuverType.TURN_RIGHT   -> "Turn right onto $road"
        ManeuverType.SLIGHT_LEFT  -> "Slight left onto $road"
        ManeuverType.SLIGHT_RIGHT -> "Slight right onto $road"
        ManeuverType.SHARP_LEFT   -> "Sharp left onto $road"
        ManeuverType.SHARP_RIGHT  -> "Sharp right onto $road"
        ManeuverType.UTURN        -> "Make a U-turn"
        ManeuverType.ROUNDABOUT   -> "At the roundabout, take $road"
        ManeuverType.CONTINUE     -> "Continue on $road"
    }

    /** Cumulative distance of the geometry vertex nearest to a maneuver location. */
    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }
}
