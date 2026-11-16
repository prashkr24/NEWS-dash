package com.example.northstar.dash.nav

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.example.northstar.data.RoutePrefs
import com.example.northstar.util.LocationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class SearchPlace(
    val id: String,
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    val category: String = "",
    val distanceMeters: Double? = null,
)

data class QuickCategory(
    val id: String,
    val label: String,
    val query: String,
    val emoji: String,
)

/**
 * In-app destination & POI search engine (WayDash v2).
 *
 * Eliminates the need to open Google Maps and share a link:
 *  1. Direct parse if the rider pastes a Google Maps URL, geo: URI, or raw "lat, lng".
 *  2. Primary search via TomTom Fuzzy Search API (biased to rider's live GPS location + India).
 *  3. Automatic keyless fallback to Komoot Photon (OSM geocoder) + Android on-device Geocoder.
 *  4. Persists recent search selections + Home / Work shortcuts on-device.
 */
object PlaceSearchEngine {
    private const val TAG = "PlaceSearchEngine"
    private const val PREFS = "waydash_search_prefs"
    private const val KEY_RECENTS = "recent_places_json"
    private const val KEY_HOME = "home_place_json"
    private const val KEY_WORK = "work_place_json"
    private const val MAX_RECENTS = 12
    private const val UA = "WayDash/2.0 (Android motorcycle cockpit)"

    val QUICK_CATEGORIES = listOf(
        QuickCategory("fuel", "Petrol Pump", "petrol pump fuel station", "⛽"),
        QuickCategory("service", "RE Service", "Royal Enfield service motorcycle mechanic", "🔧"),
        QuickCategory("food", "Cafe / Dhaba", "cafe restaurant dhaba", "☕"),
        QuickCategory("atm", "ATM", "ATM bank", "🏧"),
        QuickCategory("hospital", "Hospital", "hospital clinic", "🏥"),
    )

    suspend fun search(
        context: Context,
        query: String,
        riderLat: Double?,
        riderLng: Double?,
    ): List<SearchPlace> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        // 1. Check if the user pasted a Google Maps link or raw coordinates directly
        if (q.startsWith("http://") || q.startsWith("https://") || q.startsWith("geo:")) {
            val parsed = LocationParser.parse(q)
            if (parsed.lat != null && parsed.lng != null) {
                val dist = if (riderLat != null && riderLng != null) {
                    GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(parsed.lat, parsed.lng))
                } else null
                return@withContext listOf(
                    SearchPlace(
                        id = "link_${parsed.lat}_${parsed.lng}",
                        name = parsed.name.ifBlank { "Pinned Location" },
                        subtitle = "%.5f, %.5f · From link".format(parsed.lat, parsed.lng),
                        lat = parsed.lat,
                        lng = parsed.lng,
                        category = "Link",
                        distanceMeters = dist,
                    )
                )
            } else if (parsed.url != null) {
                val (coords, resolvedName) = LocationParser.resolve(parsed.url)
                if (coords != null) {
                    val dist = if (riderLat != null && riderLng != null) {
                        GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(coords.first, coords.second))
                    } else null
                    return@withContext listOf(
                        SearchPlace(
                            id = "link_${coords.first}_${coords.second}",
                            name = resolvedName?.takeIf { it.isNotBlank() } ?: parsed.name.ifBlank { "Shared Place" },
                            subtitle = "%.5f, %.5f · Resolved Maps link".format(coords.first, coords.second),
                            lat = coords.first,
                            lng = coords.second,
                            category = "Link",
                            distanceMeters = dist,
                        )
                    )
                }
            }
        }

        // Raw "lat, lng" coordinate input
        val rawCoordMatch = Regex("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$").find(q)
        if (rawCoordMatch != null) {
            val lat = rawCoordMatch.groupValues[1].toDoubleOrNull()
            val lng = rawCoordMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                val dist = if (riderLat != null && riderLng != null) {
                    GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(lat, lng))
                } else null
                return@withContext listOf(
                    SearchPlace(
                        id = "coord_${lat}_${lng}",
                        name = "Coordinates (%.4f, %.4f)".format(lat, lng),
                        subtitle = "Direct GPS coordinates",
                        lat = lat,
                        lng = lng,
                        category = "Pin",
                        distanceMeters = dist,
                    )
                )
            }
        }

        // 2. Primary: TomTom Fuzzy Search API (fast POI + address autocomplete with distance)
        val ttKey = RoutePrefs.tomtomKey(context)
        if (!ttKey.isNullOrBlank()) {
            val ttResults = searchTomTom(q, ttKey, riderLat, riderLng)
            if (ttResults.isNotEmpty()) return@withContext ttResults
        }

        // 3. Fallback 1: Photon (Komoot OpenStreetMap search, keyless)
        val photonResults = searchPhoton(q, riderLat, riderLng)
        if (photonResults.isNotEmpty()) return@withContext photonResults

        // 4. Fallback 2: Android built-in Geocoder
        searchDeviceGeocoder(context, q, riderLat, riderLng)
    }

    private fun searchTomTom(
        query: String,
        apiKey: String,
        riderLat: Double?,
        riderLng: Double?,
    ): List<SearchPlace> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val bias = if (riderLat != null && riderLng != null) {
                "&lat=$riderLat&lon=$riderLng&radius=50000"
            } else {
                // Default center on India if GPS isn't warm yet
                "&countrySet=IN"
            }
            val url = "https://api.tomtom.com/search/2/search/$encoded.json" +
                "?key=$apiKey&limit=8&typeahead=true&language=en-GB$bias"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 7_000
                readTimeout = 7_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                Log.w(TAG, "TomTom search HTTP $code")
                return emptyList()
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()

            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<SearchPlace>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val pos = item.optJSONObject("position") ?: continue
                val lat = pos.optDouble("lat", Double.NaN)
                val lon = pos.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue

                val poi = item.optJSONObject("poi")
                val addr = item.optJSONObject("address")
                val poiName = poi?.optString("name").orEmpty()
                val freeform = addr?.optString("freeformAddress").orEmpty()
                val muni = addr?.optString("municipality").orEmpty()
                val sub = addr?.optString("municipalitySubdivision").orEmpty()

                val title = when {
                    poiName.isNotBlank() -> poiName
                    sub.isNotBlank() -> sub
                    freeform.isNotBlank() -> freeform.substringBefore(",")
                    else -> query
                }
                val subtitle = when {
                    freeform.isNotBlank() && freeform != title -> freeform
                    muni.isNotBlank() -> muni
                    else -> "%.4f, %.4f".format(lat, lon)
                }
                val cat = poi?.optJSONArray("categories")?.optString(0)?.replaceFirstChar { it.uppercase() }.orEmpty()
                val dist = if (item.has("dist")) {
                    item.optDouble("dist")
                } else if (riderLat != null && riderLng != null) {
                    GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(lat, lon))
                } else null

                out.add(
                    SearchPlace(
                        id = item.optString("id", "tt_$i"),
                        name = title,
                        subtitle = subtitle,
                        lat = lat,
                        lng = lon,
                        category = cat,
                        distanceMeters = dist,
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "TomTom search failed: ${e.message}")
            emptyList()
        }
    }

    private fun searchPhoton(
        query: String,
        riderLat: Double?,
        riderLng: Double?,
    ): List<SearchPlace> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val bias = if (riderLat != null && riderLng != null) "&lat=$riderLat&lon=$riderLng" else "&lat=20.5937&lon=78.9629"
            val url = "https://photon.komoot.io/api/?q=$encoded&limit=8&lang=en$bias"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 7_000
                readTimeout = 7_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return emptyList()
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()

            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<SearchPlace>(features.length())
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val props = f.optJSONObject("properties") ?: continue
                val name = props.optString("name").ifBlank {
                    props.optString("street").ifBlank { props.optString("city").ifBlank { query } }
                }
                val parts = listOfNotNull(
                    props.optString("street").takeIf { it.isNotBlank() && it != name },
                    props.optString("district").takeIf { it.isNotBlank() && it != name },
                    props.optString("city").takeIf { it.isNotBlank() && it != name },
                    props.optString("state").takeIf { it.isNotBlank() },
                )
                val subtitle = parts.joinToString(", ").ifBlank { "%.4f, %.4f".format(lat, lon) }
                val cat = props.optString("osm_value").replace('_', ' ').replaceFirstChar { it.uppercase() }
                val dist = if (riderLat != null && riderLng != null) {
                    GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(lat, lon))
                } else null
                out.add(
                    SearchPlace(
                        id = "photon_${lat}_${lon}_$i",
                        name = name,
                        subtitle = subtitle,
                        lat = lat,
                        lng = lon,
                        category = cat,
                        distanceMeters = dist,
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Photon search failed: ${e.message}")
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun searchDeviceGeocoder(
        context: Context,
        query: String,
        riderLat: Double?,
        riderLng: Double?,
    ): List<SearchPlace> {
        return try {
            if (!Geocoder.isPresent()) return emptyList()
            val results = Geocoder(context, Locale.getDefault()).getFromLocationName(query, 5).orEmpty()
            results.mapIndexed { idx, addr ->
                val title = addr.featureName?.takeIf { it.isNotBlank() }
                    ?: addr.subLocality?.takeIf { it.isNotBlank() }
                    ?: addr.locality?.takeIf { it.isNotBlank() }
                    ?: query
                val line = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }
                    .joinToString(", ")
                    .ifBlank { "%.4f, %.4f".format(addr.latitude, addr.longitude) }
                val dist = if (riderLat != null && riderLng != null) {
                    GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(addr.latitude, addr.longitude))
                } else null
                SearchPlace(
                    id = "geo_$idx",
                    name = title,
                    subtitle = line,
                    lat = addr.latitude,
                    lng = addr.longitude,
                    category = "Address",
                    distanceMeters = dist,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device Geocoder failed: ${e.message}")
            emptyList()
        }
    }

    // ── Recent Searches & Home / Work Shortcuts ──────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recents(context: Context): List<SearchPlace> = try {
        val raw = prefs(context).getString(KEY_RECENTS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            fromJson(arr.optJSONObject(i))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun addRecent(context: Context, place: SearchPlace) {
        val current = recents(context).filterNot {
            it.name.equals(place.name, ignoreCase = true) ||
                GeoPoint.distMeters(GeoPoint(it.lat, it.lng), GeoPoint(place.lat, place.lng)) < 60.0
        }
        val updated = (listOf(place) + current).take(MAX_RECENTS)
        val arr = JSONArray()
        updated.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    fun clearRecents(context: Context) {
        prefs(context).edit().remove(KEY_RECENTS).apply()
    }

    fun homePlace(context: Context): SearchPlace? =
        prefs(context).getString(KEY_HOME, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun setHomePlace(context: Context, place: SearchPlace?) {
        prefs(context).edit().apply {
            if (place == null) remove(KEY_HOME) else putString(KEY_HOME, toJson(place).toString())
        }.apply()
    }

    fun workPlace(context: Context): SearchPlace? =
        prefs(context).getString(KEY_WORK, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun setWorkPlace(context: Context, place: SearchPlace?) {
        prefs(context).edit().apply {
            if (place == null) remove(KEY_WORK) else putString(KEY_WORK, toJson(place).toString())
        }.apply()
    }

    private fun toJson(p: SearchPlace): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("subtitle", p.subtitle)
        put("lat", p.lat)
        put("lng", p.lng)
        put("category", p.category)
    }

    private fun fromJson(o: JSONObject?): SearchPlace? {
        if (o == null) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lng = o.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        return SearchPlace(
            id = o.optString("id", "saved"),
            name = o.optString("name", "Place"),
            subtitle = o.optString("subtitle", ""),
            lat = lat,
            lng = lng,
            category = o.optString("category", ""),
        )
    }
}
