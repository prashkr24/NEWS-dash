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
        val primary = fetchRoutes(from, to, withAlternatives = true).ifEmpty {
            Log.w(TAG, "alternatives request empty/failed — retrying single-route")
            fetchRoutes(from, to, withAlternatives = false)
        }
        if (primary.size >= 2 || primary.isEmpty()) return@withContext primary

        // ── OSRM's alternative finder is very conservative (often returns just one
        // route where Google shows several). Synthesize real alternates the classic
        // way: route THROUGH a via-point offset perpendicular from the base route's
        // midpoint, once per side, and keep results that are genuinely different
        // roads and not absurdly longer. ──
        val base = primary[0]
        val alts = arrayListOf(base)
        val mid = pointAtFraction(base, 0.5)
        val brg = bearingAtFraction(base, 0.5)
        if (mid != null) {
            val offsetM = (base.totalMeters * 0.15).coerceIn(1200.0, 5000.0)
            for (side in doubleArrayOf(90.0, -90.0)) {
                if (alts.size >= 3) break
                val via = offsetPoint(mid, brg + side, offsetM)
                val r = fetchVia(from, via, to) ?: continue
                if (r.totalSeconds > base.totalSeconds * 1.5) continue   // too slow to suggest
                if (alts.any { overlapFraction(r, it) > 0.75 }) continue // same road, skip
                alts += r
            }
        }
        Log.i(TAG, "routes(): ${primary.size} from OSRM, ${alts.size} after synthesis")
        alts
    }

    /** Route forced through one intermediate point (used for synthesized alternates). */
    private fun fetchVia(from: GeoPoint, via: GeoPoint, to: GeoPoint): Route? {
        val url = "$BASE/${from.lng},${from.lat};${via.lng},${via.lat};${to.lng},${to.lat}" +
                "?overview=full&geometries=polyline&steps=true&annotations=false"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parseAll(body).firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "fetchVia failed: ${e.message}"); null
        }
    }

    /** Geometry vertex nearest to [fraction] of the route's total distance. */
    private fun pointAtFraction(r: Route, fraction: Double): GeoPoint? {
        val target = r.totalMeters * fraction
        val idx = r.cumulative.indexOfFirst { it >= target }
        return r.geometry.getOrNull(if (idx < 0) r.geometry.lastIndex else idx)
    }

    /** Rough travel bearing (degrees) around [fraction] of the route. */
    private fun bearingAtFraction(r: Route, fraction: Double): Double {
        val target = r.totalMeters * fraction
        var idx = r.cumulative.indexOfFirst { it >= target }
        if (idx < 1) idx = 1
        if (idx > r.geometry.lastIndex) idx = r.geometry.lastIndex
        val a = r.geometry[idx - 1]; val b = r.geometry[idx]
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val y = Math.sin(dLng) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLng)
        return Math.toDegrees(Math.atan2(y, x))
    }

    /** Point [meters] away from [p] at compass [bearingDeg] (equirectangular approx). */
    private fun offsetPoint(p: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
        val rad = Math.toRadians(bearingDeg)
        val dLat = meters * Math.cos(rad) / 111_320.0
        val dLng = meters * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(p.lat)))
        return GeoPoint(p.lat + dLat, p.lng + dLng)
    }

    /** Fraction (0..1) of route [a]'s sampled points lying within ~120 m of route [b]. */
    private fun overlapFraction(a: Route, b: Route): Double {
        if (a.geometry.isEmpty() || b.geometry.isEmpty()) return 1.0
        val stepA = (a.geometry.size / 120).coerceAtLeast(1)
        val stepB = (b.geometry.size / 200).coerceAtLeast(1)
        val bs = b.geometry.filterIndexed { i, _ -> i % stepB == 0 }
        var near = 0; var total = 0
        for (i in a.geometry.indices step stepA) {
            total++
            val p = a.geometry[i]
            if (bs.any { GeoPoint.distMeters(p, it) < 120.0 }) near++
        }
        return if (total == 0) 1.0 else near.toDouble() / total
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
                    // India-aware elevated detection across name, highway ref, and signpost destinations.
                    val elevatedHint = FlyoverDetector.hint(
                        name,
                        step.optString("ref"),
                        step.optString("destinations"),
                    )
                    maneuvers.add(
                        Maneuver(
                            type = type,
                            instruction = elevatedHint ?: buildInstruction(type, name),
                            location = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                            flyover = elevatedHint != null,
                        )
                    )
                }
            }
        }

        val legsArr = r0.optJSONArray("legs")
        val summary = buildList {
            if (legsArr != null) for (i in 0 until legsArr.length()) {
                legsArr.optJSONObject(i)?.optString("summary")
                    ?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.flatMap { it.split(", ") }.distinct().take(3).joinToString(", ")
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
