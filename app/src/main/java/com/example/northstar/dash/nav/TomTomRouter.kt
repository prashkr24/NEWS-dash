package com.example.northstar.dash.nav

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * TomTom Routing API client — traffic-aware routing with real alternatives, mapped
 * onto the app's [Route] model so navigation works identically regardless of engine.
 *
 * Free tier: 2,500 routing requests/day, no credit card. `traffic=true` folds live +
 * historical traffic into travel times, so alternative durations behave like Google's
 * ("41 min vs 42 min via the other corridor") instead of OSRM's free-flow guesses.
 */
object TomTomRouter {
    private const val TAG = "TomTomRouter"

    /** HTTP code of the most recent failed call (0 = none/success). 403/429 = quota. */
    @Volatile var lastFailureCode: Int = 0
        private set
    private const val BASE = "https://api.tomtom.com/routing/1/calculateRoute"
    private const val UA = "WayDash/0.7 (personal motorcycle nav; single user)"

    suspend fun routes(from: GeoPoint, to: GeoPoint, apiKey: String): List<Route> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/${from.lat},${from.lng}:${to.lat},${to.lng}/json" +
                    "?key=$apiKey&traffic=true&maxAlternatives=2" +
                    "&instructionsType=text&travelMode=motorcycle"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 12_000
                    readTimeout = 12_000
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                conn.disconnect()
                if (code !in 200..299) {
                    lastFailureCode = code
                    Log.w(TAG, "HTTP $code: ${body.take(200)}")
                    return@withContext emptyList()
                }
                lastFailureCode = 0
                parse(body).also { Log.i(TAG, "TomTom returned ${it.size} route(s)") }
            } catch (e: Exception) {
                lastFailureCode = -1  // network / no response
                Log.w(TAG, "routes() failed: ${e.message}")
                emptyList()
            }
        }

    private fun parse(json: String): List<Route> {
        val routesArr = JSONObject(json).optJSONArray("routes") ?: return emptyList()
        return (0 until routesArr.length()).mapNotNull { i ->
            runCatching { parseOne(routesArr.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun parseOne(r: JSONObject): Route? {
        // Geometry: concatenate every leg's point list.
        val geometry = ArrayList<GeoPoint>()
        val legs = r.optJSONArray("legs") ?: return null
        for (li in 0 until legs.length()) {
            val pts = legs.getJSONObject(li).optJSONArray("points") ?: continue
            for (pi in 0 until pts.length()) {
                val p = pts.getJSONObject(pi)
                geometry.add(GeoPoint(p.getDouble("latitude"), p.getDouble("longitude")))
            }
        }
        if (geometry.size < 2) return null

        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        // Maneuvers from guidance instructions. routeOffsetInMeters IS the cumulative
        // distance — no nearest-vertex search needed.
        val maneuvers = ArrayList<Maneuver>()
        val roadNames = LinkedHashSet<String>()
        val instructions = r.optJSONObject("guidance")?.optJSONArray("instructions")
        if (instructions != null) {
            for (ii in 0 until instructions.length()) {
                val ins = instructions.getJSONObject(ii)
                val pt = ins.optJSONObject("point") ?: continue
                val street = ins.optString("street")
                val refs = ins.optJSONArray("roadNumbers")
                if (refs != null && refs.length() > 0) roadNames.add(refs.getString(0))
                else if (street.isNotBlank()) roadNames.add(street)
                val flyover = street.contains("flyover", ignoreCase = true) ||
                        ins.optString("message").contains("flyover", ignoreCase = true)
                val type = mapManeuver(ins.optString("maneuver"))
                maneuvers.add(
                    Maneuver(
                        type = type,
                        instruction = if (flyover) "Take the flyover"
                        else ins.optString("message").ifBlank { type.name.lowercase().replace('_', ' ') },
                        location = GeoPoint(pt.getDouble("latitude"), pt.getDouble("longitude")),
                        cumulativeMeters = ins.optDouble("routeOffsetInMeters", 0.0),
                        flyover = flyover,
                    )
                )
            }
        }

        val summaryObj = r.optJSONObject("summary")
        val lengthM = summaryObj?.optDouble("lengthInMeters", cum.last()) ?: cum.last()
        val timeS = summaryObj?.optDouble("travelTimeInSeconds", 0.0) ?: 0.0
        val delayS = summaryObj?.optDouble("trafficDelayInSeconds", 0.0) ?: 0.0

        var summary = roadNames.take(3).joinToString(", ")
        if (delayS >= 180) summary = listOf(summary, "+${(delayS / 60).toInt()}m traffic")
            .filter { it.isNotBlank() }.joinToString(" · ")

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = lengthM,
            totalSeconds = timeS, // traffic-aware when traffic=true
            cumulative = cum,
            summary = summary,
        )
    }

    private fun mapManeuver(m: String): ManeuverType = when (m.uppercase()) {
        "TURN_LEFT" -> ManeuverType.TURN_LEFT
        "TURN_RIGHT" -> ManeuverType.TURN_RIGHT
        "BEAR_LEFT", "KEEP_LEFT" -> ManeuverType.SLIGHT_LEFT
        "BEAR_RIGHT", "KEEP_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "SHARP_LEFT" -> ManeuverType.SHARP_LEFT
        "SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
        "MAKE_UTURN" -> ManeuverType.UTURN
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT", "ROUNDABOUT_CROSS", "ROUNDABOUT_BACK",
        "ENTER_ROUNDABOUT", "EXIT_ROUNDABOUT" -> ManeuverType.ROUNDABOUT
        "DEPART" -> ManeuverType.DEPART
        "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> ManeuverType.ARRIVE
        else -> ManeuverType.CONTINUE
    }
}
