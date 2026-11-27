package com.example.northstar.dash.nav

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Structural flyover / bridge / underpass detector backed by OpenStreetMap vector tiles
 * (`https://tiles.openfreemap.org/planet`, `transportation` layer `brunnel = bridge | tunnel`).
 *
 * Why name-matching alone failed in India:
 *  - Both TomTom and OSRM label almost all urban flyovers with the arterial highway's name
 *    (e.g. "Outer Ring Road", "Hosur Road", "HAL Old Airport Road") rather than "... Flyover".
 *  - Straight-through flyovers (e.g. Domlur Flyover) emit *zero* turn instructions in routing APIs.
 *
 * By matching the route polyline against OSM's structural `brunnel` road geometry at zoom 14:
 *  1. Climbing an elevated span (`brunnel = bridge`, >= 60 m) injects `"Take the flyover"`
 *     anchored ~65 m before the deck so guidance fires ahead of the entry ramp divider.
 *  2. Entering a depressed span (`brunnel = tunnel`, >= 30 m) injects `"Keep to the underpass"`.
 *  3. Riding on a ground-level slip road immediately beside an arterial flyover (`9..26 m`
 *     parallel `bridge`) injects `"Keep to service road"` so the rider doesn't climb the ramp.
 */
object FlyoverRouteEnricher {
    private const val TAG = "FlyoverEnricher"
    private const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val UA = "WayDash/2.0 (Android motorcycle nav)"
    private const val ZOOM = 14
    private const val MAX_TILES_PER_ROUTE = 24
    private const val ENRICH_TIMEOUT_MS = 3_800L
    private const val DISK_TTL_MS = 7L * 24 * 3600 * 1000L
    private const val MEM_CACHE_MAX = 64

    internal enum class Kind { NONE, BRIDGE, TUNNEL }

    internal data class BrunnelSeg(
        val kind: Kind,
        val majorRoad: Boolean,
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double,
        val aLat: Double,
        val aLng: Double,
        val bLat: Double,
        val bLng: Double,
        val bearingDeg: Float,
    )

    internal data class DetectedSpan(
        val kind: Kind,
        val serviceRoadBesideFlyover: Boolean,
        val startMeters: Double,
        val lengthMeters: Double,
    )

    @Volatile private var cachedTemplate: String? = null
    @Volatile private var templateFetchedAtMs: Long = 0L

    private val memCache = object : LinkedHashMap<String, List<BrunnelSeg>>(MEM_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<BrunnelSeg>>?): Boolean =
            size > MEM_CACHE_MAX
    }
    private val fetchSemaphore = Semaphore(4)

    private val EXCLUDED_CLASSES = setOf(
        "path", "track", "footway", "steps", "pedestrian", "bridleway",
        "cycleway", "rail", "transit", "ferry", "aerialway", "pier",
    )
    private val MAJOR_CLASSES = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary",
    )

    /**
     * Enriches [routes] with structural flyover / underpass / service-road maneuvers.
     * Never throws and caps network latency to [ENRICH_TIMEOUT_MS]; returns original routes on failure.
     */
    suspend fun enrichRoutes(context: Context, routes: List<Route>): List<Route> {
        if (routes.isEmpty()) return routes
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                runCatching {
                    val cacheDir = File(context.applicationContext.cacheDir, "flyover_mvt_v1").apply { mkdirs() }
                    // Collect unique z14 tiles across the candidate routes (prioritizing the primary route).
                    val neededTiles = LinkedHashSet<Pair<Int, Int>>()
                    for (r in routes) {
                        for (pt in sampleTilesForRoute(r.geometry)) {
                            if (neededTiles.size >= MAX_TILES_PER_ROUTE) break
                            neededTiles.add(pt)
                        }
                        if (neededTiles.size >= MAX_TILES_PER_ROUTE) break
                    }
                    if (neededTiles.isEmpty()) return@runCatching routes

                    val template = resolveTileTemplate() ?: return@runCatching routes
                    val tileMap = coroutineScope {
                        neededTiles.map { (tx, ty) ->
                            async {
                                (tx to ty) to loadTileSegments(cacheDir, template, tx, ty)
                            }
                        }.awaitAll().toMap()
                    }
                    routes.map { r -> enrichSingleRoute(r, tileMap) }
                }.onFailure { e ->
                    Log.w(TAG, "Flyover enrichment skipped: ${e.message}")
                }.getOrDefault(routes)
            } ?: routes
        }
    }

    private fun sampleTilesForRoute(geometry: List<GeoPoint>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var lastTile: Pair<Int, Int>? = null
        var accumM = 0.0
        for (i in geometry.indices) {
            if (i > 0) accumM += GeoPoint.distMeters(geometry[i - 1], geometry[i])
            // Walk every ~250 m plus endpoints so long segments don't skip intermediate tiles.
            if (i == 0 || i == geometry.lastIndex || accumM >= 250.0) {
                accumM = 0.0
                val t = latLngToTile(geometry[i].lat, geometry[i].lng, ZOOM)
                if (t != lastTile) {
                    out.add(t)
                    lastTile = t
                }
            }
        }
        return out
    }

    internal fun enrichSingleRoute(
        route: Route,
        tileSegments: Map<Pair<Int, Int>, List<BrunnelSeg>>,
    ): Route {
        val geom = route.geometry
        if (geom.size < 2 || route.totalMeters < 100.0) return route

        // Collect all segments from the tiles this route touches.
        val touchedTiles = LinkedHashSet<Pair<Int, Int>>()
        for (pt in geom) touchedTiles.add(latLngToTile(pt.lat, pt.lng, ZOOM))
        val segs = ArrayList<BrunnelSeg>()
        for (t in touchedTiles) {
            tileSegments[t]?.let { segs.addAll(it) }
        }
        if (segs.isEmpty()) return route

        // Sample route every 15 m.
        val stepM = 15.0
        val samplePts = ArrayList<GeoPoint>()
        val sampleCum = ArrayList<Double>()
        val sampleHdg = ArrayList<Float>()
        samplePts.add(geom[0])
        sampleCum.add(0.0)
        sampleHdg.add(GeoPoint.bearing(geom[0], geom[1]).toFloat())

        var acc = 0.0
        var nextSampleAt = stepM
        for (i in 0 until geom.size - 1) {
            val a = geom[i]; val b = geom[i + 1]
            val segLen = GeoPoint.distMeters(a, b)
            if (segLen < 0.5) continue
            val hdg = GeoPoint.bearing(a, b).toFloat()
            while (nextSampleAt <= acc + segLen) {
                val f = (nextSampleAt - acc) / segLen
                samplePts.add(GeoPoint(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f))
                sampleCum.add(nextSampleAt)
                sampleHdg.add(hdg)
                nextSampleAt += stepM
            }
            acc += segLen
        }
        val n = samplePts.size
        if (n < 4) return route

        val onStruct = Array(n) { Kind.NONE }
        val parallelFlyover = BooleanArray(n)

        for (idx in 0 until n) {
            val sp = samplePts[idx]
            val shdg = sampleHdg[idx]
            var bestKind = Kind.NONE
            var bestDist = 7.5 // <= 7.5 m lateral match: stays strictly on the ramp/deck, never the service road!
            var hasParallelBridge = false

            for (s in segs) {
                if (sp.lat < s.minLat || sp.lat > s.maxLat || sp.lng < s.minLng || sp.lng > s.maxLng) continue
                val ad = angleDiff180(shdg, s.bearingDeg)
                // Ignore cross-bridges/overpasses crossing perpendicular overhead!
                if (ad > 22f && (180f - ad) > 22f) continue
                val d = pointToSegMeters(sp.lat, sp.lng, s.aLat, s.aLng, s.bLat, s.bLng)
                if (d < bestDist) {
                    bestDist = d
                    bestKind = s.kind
                } else if (s.kind == Kind.BRIDGE && s.majorRoad && d in 8.5..26.0 && (ad <= 18f || ad >= 162f)) {
                    hasParallelBridge = true
                }
            }
            onStruct[idx] = bestKind
            parallelFlyover[idx] = (bestKind == Kind.NONE) && hasParallelBridge
        }

        // Smooth 1-sample (15 m) micro-gaps between adjacent bridge/tunnel ways at expansion joints.
        for (i in 1 until n - 1) {
            if (onStruct[i] == Kind.NONE && onStruct[i - 1] != Kind.NONE && onStruct[i - 1] == onStruct[i + 1]) {
                onStruct[i] = onStruct[i - 1]
            }
            if (!parallelFlyover[i] && onStruct[i] == Kind.NONE && parallelFlyover[i - 1] && parallelFlyover[i + 1]) {
                parallelFlyover[i] = true
            }
        }

        val detected = extractSpans(onStruct, parallelFlyover, sampleCum)
        if (detected.isEmpty()) return route

        val merged = route.maneuvers.toMutableList()
        var injectedCount = 0
        for (span in detected) {
            val hint = when {
                span.serviceRoadBesideFlyover -> "Keep to service road"
                span.kind == Kind.TUNNEL -> "Keep to the underpass"
                else -> "Take the flyover"
            }
            val leadBackM = if (span.kind == Kind.TUNNEL) 55.0 else 65.0
            val anchorM = (span.startMeters - leadBackM).coerceAtLeast(15.0)
            val anchorPt = pointAtMeters(samplePts, sampleCum, anchorM)

            // Look for an existing maneuver near the ramp split (e.g. TomTom's BIFURCATION).
            val nearIdx = merged.indexOfFirst {
                it.type != ManeuverType.DEPART &&
                    it.type != ManeuverType.ARRIVE &&
                    abs(it.cumulativeMeters - anchorM) <= 95.0
            }
            if (nearIdx >= 0) {
                val existing = merged[nearIdx]
                if (!existing.flyover &&
                    existing.type in setOf(ManeuverType.CONTINUE, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT)
                ) {
                    merged[nearIdx] = existing.copy(instruction = hint, flyover = true)
                    injectedCount++
                } else if (!existing.flyover && abs(existing.cumulativeMeters - anchorM) > 40.0) {
                    merged.add(
                        Maneuver(
                            type = if (span.serviceRoadBesideFlyover) ManeuverType.SLIGHT_LEFT else ManeuverType.CONTINUE,
                            instruction = hint,
                            location = anchorPt,
                            cumulativeMeters = anchorM,
                            flyover = true,
                        )
                    )
                    injectedCount++
                }
            } else {
                merged.add(
                    Maneuver(
                        type = if (span.serviceRoadBesideFlyover) ManeuverType.SLIGHT_LEFT else ManeuverType.CONTINUE,
                        instruction = hint,
                        location = anchorPt,
                        cumulativeMeters = anchorM,
                        flyover = true,
                    )
                )
                injectedCount++
            }
        }

        if (injectedCount == 0) return route
        merged.sortBy { it.cumulativeMeters }
        runCatching {
            Log.i(TAG, "Enriched route (${route.totalMeters.toInt()} m) with $injectedCount flyover/underpass guidance point(s)")
        }
        return route.copy(maneuvers = merged)
    }

    internal fun extractSpans(
        onStruct: Array<Kind>,
        parallelFlyover: BooleanArray,
        sampleCum: List<Double>,
    ): List<DetectedSpan> {
        val out = ArrayList<DetectedSpan>()
        val n = onStruct.size
        var lastBridgeEndM = -500.0

        // 1. On-structure spans (flyovers / bridges & underpasses / tunnels)
        var i = 0
        while (i < n) {
            val k = onStruct[i]
            if (k == Kind.NONE) { i++; continue }
            var j = i
            while (j < n && onStruct[j] == k) j++
            val startM = sampleCum[i]
            val endM = sampleCum[j - 1]
            val lenM = endM - startM
            val minLen = if (k == Kind.BRIDGE) 60.0 else 30.0
            if (lenM >= minLen && startM >= 40.0) {
                out.add(DetectedSpan(k, serviceRoadBesideFlyover = false, startMeters = startM, lengthMeters = lenM))
            }
            if (k == Kind.BRIDGE) lastBridgeEndM = endM
            i = j
        }

        // 2. Parallel service-road spans (ground road running right alongside a major flyover)
        i = 0
        while (i < n) {
            if (!parallelFlyover[i]) { i++; continue }
            var j = i
            while (j < n && parallelFlyover[j]) j++
            val startM = sampleCum[i]
            val endM = sampleCum[j - 1]
            val lenM = endM - startM
            // Ignore if we just came off or are about to merge onto an on-route bridge within 140 m.
            val overlapsOnBridge = out.any {
                !it.serviceRoadBesideFlyover &&
                    startM <= (it.startMeters + it.lengthMeters + 140.0) &&
                    endM >= (it.startMeters - 140.0)
            }
            if (!overlapsOnBridge && lenM >= 90.0 && startM >= 50.0 && startM - lastBridgeEndM > 140.0) {
                out.add(DetectedSpan(Kind.BRIDGE, serviceRoadBesideFlyover = true, startMeters = startM, lengthMeters = lenM))
            }
            i = j
        }

        out.sortBy { it.startMeters }
        return out
    }

    private fun pointAtMeters(pts: List<GeoPoint>, cum: List<Double>, targetM: Double): GeoPoint {
        var lo = 0
        var hi = cum.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < targetM) lo = mid + 1 else hi = mid
        }
        return pts[lo.coerceIn(0, pts.lastIndex)]
    }

    private fun resolveTileTemplate(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedTemplate
        if (cached != null && now - templateFetchedAtMs < 12 * 3600_000L) return cached
        return runCatching {
            val conn = (URL(TILEJSON_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 2_500
                readTimeout = 2_500
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val tiles = JSONObject(body).optJSONArray("tiles")
            val tpl = if (tiles != null && tiles.length() > 0) tiles.getString(0) else null
            if (!tpl.isNullOrBlank()) {
                cachedTemplate = tpl
                templateFetchedAtMs = now
            }
            tpl ?: cached
        }.getOrElse { cached }
    }

    private suspend fun loadTileSegments(
        cacheDir: File,
        template: String,
        tx: Int,
        ty: Int,
    ): List<BrunnelSeg> {
        val key = "${ZOOM}_${tx}_${ty}"
        synchronized(memCache) { memCache.get(key) }?.let { return it }

        val diskFile = File(cacheDir, "$key.pbf")
        val now = System.currentTimeMillis()
        val rawBytes: ByteArray? = if (diskFile.exists() && now - diskFile.lastModified() < DISK_TTL_MS && diskFile.length() > 0) {
            runCatching { diskFile.readBytes() }.getOrNull()
        } else {
            fetchSemaphore.withPermit {
                runCatching {
                    val url = template
                        .replace("{z}", ZOOM.toString())
                        .replace("{x}", tx.toString())
                        .replace("{y}", ty.toString())
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", UA)
                        connectTimeout = 2_500
                        readTimeout = 3_000
                    }
                    val code = conn.responseCode
                    val data = if (code in 200..299) conn.inputStream.use { it.readBytes() } else null
                    conn.disconnect()
                    if (data != null && data.isNotEmpty()) {
                        runCatching { diskFile.writeBytes(data) }
                    }
                    data
                }.getOrNull()
            }
        }
        if (rawBytes == null || rawBytes.isEmpty()) return emptyList()

        val uncompressed = runCatching {
            if (rawBytes.size >= 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
                GZIPInputStream(rawBytes.inputStream()).use { it.readBytes() }
            } else rawBytes
        }.getOrNull() ?: return emptyList()

        val parsed = runCatching { parseMvtTransportationBrunnels(uncompressed, ZOOM, tx, ty) }
            .getOrDefault(emptyList())
        synchronized(memCache) { memCache.put(key, parsed) }
        return parsed
    }

    /**
     * Minimal Mapbox Vector Tile (MVT v2) protobuf parser extracting `brunnel = bridge | tunnel`
     * road segments from the `transportation` layer.
     */
    internal fun parseMvtTransportationBrunnels(
        buf: ByteArray,
        z: Int,
        tx: Int,
        ty: Int,
    ): List<BrunnelSeg> {
        val out = ArrayList<BrunnelSeg>()
        var pos = 0
        val limit = buf.size
        while (pos < limit) {
            val tagPair = readVarint(buf, pos, limit) ?: break
            val tag = tagPair.first.toInt()
            pos = tagPair.second
            val fieldNum = tag ushr 3
            val wireType = tag and 7
            when (wireType) {
                0 -> { pos = skipVarint(buf, pos, limit); if (pos < 0) return out }
                1 -> { pos += 8; if (pos > limit) return out }
                5 -> { pos += 4; if (pos > limit) return out }
                2 -> {
                    val lenPair = readVarint(buf, pos, limit) ?: return out
                    val len = lenPair.first.toInt()
                    val start = lenPair.second
                    val end = start + len
                    if (len < 0 || end > limit) return out
                    if (fieldNum == 3) {
                        parseTransportationLayer(buf, start, end, z, tx, ty, out)
                    }
                    pos = end
                }
                else -> return out
            }
        }
        return out
    }

    private fun parseTransportationLayer(
        buf: ByteArray,
        layerStart: Int,
        layerEnd: Int,
        z: Int,
        tx: Int,
        ty: Int,
        out: MutableList<BrunnelSeg>,
    ) {
        var layerName: String? = null
        var extent = 4096
        val keys = ArrayList<String>()
        val stringValues = ArrayList<String?>()
        val featureSpans = ArrayList<IntArray>()

        var pos = layerStart
        while (pos < layerEnd) {
            val tagPair = readVarint(buf, pos, layerEnd) ?: return
            val tag = tagPair.first.toInt()
            pos = tagPair.second
            val fn = tag ushr 3
            val wt = tag and 7
            when (wt) {
                0 -> {
                    val vp = readVarint(buf, pos, layerEnd) ?: return
                    pos = vp.second
                    if (fn == 5 && vp.first > 0) extent = vp.first.toInt()
                }
                1 -> { pos += 8; if (pos > layerEnd) return }
                5 -> { pos += 4; if (pos > layerEnd) return }
                2 -> {
                    val lp = readVarint(buf, pos, layerEnd) ?: return
                    val len = lp.first.toInt()
                    val s = lp.second
                    val e = s + len
                    if (len < 0 || e > layerEnd) return
                    when (fn) {
                        1 -> {
                            layerName = String(buf, s, len, Charsets.UTF_8)
                            if (layerName != "transportation") return
                        }
                        2 -> featureSpans.add(intArrayOf(s, e))
                        3 -> keys.add(String(buf, s, len, Charsets.UTF_8))
                        4 -> stringValues.add(parseMvtStringValue(buf, s, e))
                    }
                    pos = e
                }
                else -> return
            }
        }

        if (layerName != "transportation") return
        val brunnelKeyIdx = keys.indexOf("brunnel")
        if (brunnelKeyIdx < 0) return
        val classKeyIdx = keys.indexOf("class")
        val extF = extent.toDouble()

        for (span in featureSpans) {
            var fType = 0
            var tagsStart = -1; var tagsEnd = -1
            var geomStart = -1; var geomEnd = -1
            var fp = span[0]; val fe = span[1]
            while (fp < fe) {
                val tp = readVarint(buf, fp, fe) ?: break
                val t = tp.first.toInt()
                fp = tp.second
                val ffn = t ushr 3
                val fwt = t and 7
                when (fwt) {
                    0 -> {
                        val vp = readVarint(buf, fp, fe) ?: break
                        fp = vp.second
                        if (ffn == 3) fType = vp.first.toInt()
                    }
                    1 -> fp += 8
                    5 -> fp += 4
                    2 -> {
                        val lp = readVarint(buf, fp, fe) ?: break
                        val s = lp.second
                        val e = s + lp.first.toInt()
                        if (e > fe) break
                        if (ffn == 2) { tagsStart = s; tagsEnd = e }
                        else if (ffn == 4) { geomStart = s; geomEnd = e }
                        fp = e
                    }
                    else -> break
                }
            }
            if (fType != 2 || tagsStart < 0 || geomStart < 0) continue // LineString only

            var brunnelVal: String? = null
            var classVal: String? = null
            var tp = tagsStart
            while (tp < tagsEnd) {
                val kp = readVarint(buf, tp, tagsEnd) ?: break
                val vp = readVarint(buf, kp.second, tagsEnd) ?: break
                tp = vp.second
                val kIdx = kp.first.toInt()
                val vIdx = vp.first.toInt()
                if (kIdx == brunnelKeyIdx && vIdx in stringValues.indices) brunnelVal = stringValues[vIdx]
                else if (kIdx == classKeyIdx && vIdx in stringValues.indices) classVal = stringValues[vIdx]
            }

            val kind = when (brunnelVal) {
                "bridge" -> Kind.BRIDGE
                "tunnel" -> Kind.TUNNEL
                else -> continue
            }
            if (classVal != null && classVal in EXCLUDED_CLASSES) continue
            val isMajor = classVal == null || classVal in MAJOR_CLASSES

            decodeLineStrings(buf, geomStart, geomEnd, z, tx, ty, extF, kind, isMajor, out)
        }
    }

    private fun parseMvtStringValue(buf: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos < end) {
            val tp = readVarint(buf, pos, end) ?: return null
            val tag = tp.first.toInt()
            pos = tp.second
            val fn = tag ushr 3
            val wt = tag and 7
            when (wt) {
                0 -> { pos = skipVarint(buf, pos, end); if (pos < 0) return null }
                1 -> pos += 8
                5 -> pos += 4
                2 -> {
                    val lp = readVarint(buf, pos, end) ?: return null
                    val len = lp.first.toInt()
                    val s = lp.second
                    if (len < 0 || s + len > end) return null
                    if (fn == 1) return String(buf, s, len, Charsets.UTF_8)
                    pos = s + len
                }
                else -> return null
            }
        }
        return null
    }

    private fun decodeLineStrings(
        buf: ByteArray,
        geomStart: Int,
        geomEnd: Int,
        z: Int,
        tx: Int,
        ty: Int,
        extent: Double,
        kind: Kind,
        majorRoad: Boolean,
        out: MutableList<BrunnelSeg>,
    ) {
        var cx = 0
        var cy = 0
        var hasPrev = false
        var prevLat = 0.0
        var prevLng = 0.0
        var pos = geomStart

        while (pos < geomEnd) {
            val cp = readVarint(buf, pos, geomEnd) ?: return
            pos = cp.second
            val cmdInt = cp.first.toInt()
            val cmd = cmdInt and 7
            val count = cmdInt ushr 3
            when (cmd) {
                1 -> { // MoveTo
                    hasPrev = false
                    repeat(count) {
                        val xp = readVarint(buf, pos, geomEnd) ?: return
                        val yp = readVarint(buf, xp.second, geomEnd) ?: return
                        pos = yp.second
                        cx += zigzagDecode(xp.first.toInt())
                        cy += zigzagDecode(yp.first.toInt())
                        prevLat = tileYToLat(z, ty, cy / extent)
                        prevLng = tileXToLng(z, tx, cx / extent)
                        hasPrev = true
                    }
                }
                2 -> { // LineTo
                    repeat(count) {
                        val xp = readVarint(buf, pos, geomEnd) ?: return
                        val yp = readVarint(buf, xp.second, geomEnd) ?: return
                        pos = yp.second
                        cx += zigzagDecode(xp.first.toInt())
                        cy += zigzagDecode(yp.first.toInt())
                        val lat = tileYToLat(z, ty, cy / extent)
                        val lng = tileXToLng(z, tx, cx / extent)
                        if (hasPrev && (lat != prevLat || lng != prevLng)) {
                            val pad = 0.00032 // ~35 m bbox margin for fast candidate culling
                            val hdg = flatBearing(prevLat, prevLng, lat, lng)
                            out.add(
                                BrunnelSeg(
                                    kind = kind,
                                    majorRoad = majorRoad,
                                    minLat = min(prevLat, lat) - pad,
                                    maxLat = max(prevLat, lat) + pad,
                                    minLng = min(prevLng, lng) - pad,
                                    maxLng = max(prevLng, lng) + pad,
                                    aLat = prevLat,
                                    aLng = prevLng,
                                    bLat = lat,
                                    bLng = lng,
                                    bearingDeg = hdg,
                                )
                            )
                        }
                        prevLat = lat
                        prevLng = lng
                        hasPrev = true
                    }
                }
                else -> return // ClosePath (7) not used on LineStrings
            }
        }
    }

    private fun zigzagDecode(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun readVarint(buf: ByteArray, start: Int, limit: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < limit && shift < 64) {
            val b = buf[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
        }
        return null
    }

    private fun skipVarint(buf: ByteArray, start: Int, limit: Int): Int {
        var i = start
        var count = 0
        while (i < limit && count < 10) {
            val b = buf[i].toInt()
            i++; count++
            if ((b and 0x80) == 0) return i
        }
        return -1
    }

    internal fun latLngToTile(lat: Double, lng: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val tx = (((lng + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val ty = (((1.0 - asinh(tan(latRad)) / Math.PI) / 2.0) * n).toInt().coerceIn(0, n - 1)
        return tx to ty
    }

    private fun tileXToLng(z: Int, tx: Int, fracX: Double): Double {
        val n = (1 shl z).toDouble()
        return ((tx + fracX) / n) * 360.0 - 180.0
    }

    private fun tileYToLat(z: Int, ty: Int, fracY: Double): Double {
        val n = (1 shl z).toDouble()
        return Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (ty + fracY) / n))))
    }

    private fun flatBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLat = lat2 - lat1
        val dLng = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) * 0.5))
        return ((Math.toDegrees(atan2(dLng, dLat)) + 360.0) % 360.0).toFloat()
    }

    private fun angleDiff180(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun pointToSegMeters(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Double {
        val cosLat = cos(Math.toRadians(pLat))
        val px = pLng * 111_320.0 * cosLat; val py = pLat * 111_320.0
        val ax = aLng * 111_320.0 * cosLat; val ay = aLat * 111_320.0
        val bx = bLng * 111_320.0 * cosLat; val by = bLat * 111_320.0
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
