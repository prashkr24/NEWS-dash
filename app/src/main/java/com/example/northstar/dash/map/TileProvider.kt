package com.example.northstar.dash.map

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import com.example.northstar.data.MapPrefs
import com.example.northstar.dash.nav.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Raster tile provider with memory + disk cache — now source-pluggable (Google / Mappls).
 *
 * Differences from upstream NorthStar:
 *  • Tile URLs come from [TileSource] instead of a hardcoded template.
 *  • Disk cache lives under a per-source namespace, and memory-cache keys are prefixed with
 *    the namespace, so switching providers mid-session never mixes tile styles.
 *  • [setSource] switches provider at runtime; the next rendered frame uses the new tiles.
 *
 * Everything else (byte-budgeted LruCache, decode-before-cache, rate-limited fetches through
 * the internet-capable network while bound to the Tripper's Wi-Fi, prefetch strategies) is
 * kept identical to upstream.
 */
class TileProvider(
    private val context: Context,
    private val scope: CoroutineScope,
    initialSource: TileSource = MapPrefs.effectiveTileSource(context),
) {
    companion object {
        private const val TAG = "TileProvider"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_PREFETCH_TILES = 600
        private const val MIN_FETCH_GAP_MS = 60L

        /** ~1/5 of the app heap for the tile cache, clamped to a sane 48–128 MB. */
        private fun memoryCacheBytes(ctx: Context): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val heapBytes = am.memoryClass.toLong() * 1024L * 1024L
            return (heapBytes / 5L).coerceIn(48L * 1024 * 1024, 128L * 1024 * 1024).toInt()
        }
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile var source: TileSource = initialSource
        private set
    @Volatile private var apiKey: String? = MapPrefs.mapplsKey(context)
    @Volatile private var diskDir: File = dirFor(initialSource)

    private fun dirFor(s: TileSource) = File(context.cacheDir, s.cacheNamespace).apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(memoryCacheBytes(context)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastFetchAt = 0L

    // Self-wiring: react to Settings changes (provider or Mappls key) with no ViewModel
    // plumbing. Held as a field — SharedPreferences keeps listeners weakly.
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        setSource(MapPrefs.effectiveTileSource(context))
    }
    init {
        MapPrefs.raw(context).registerOnSharedPreferenceChangeListener(prefListener)
    }

    /**
     * Switch tile provider at runtime (e.g. from Settings). Memory-cache keys are namespaced,
     * so old-source bitmaps simply age out of the LRU; the disk caches are fully separate.
     * Re-reads the Mappls key so a freshly saved key takes effect immediately.
     */
    fun setSource(newSource: TileSource) {
        apiKey = MapPrefs.mapplsKey(context)
        if (newSource == source) return
        source = newSource
        diskDir = dirFor(newSource)
        Log.i(TAG, "Tile source switched to ${newSource.displayName}")
    }

    /** Memory-cache key, namespaced by source so styles never mix. */
    private fun memKey(key: String) = "${source.cacheNamespace}|$key"

    /** Non-blocking: returns the cached tile, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        val key = "$z/$xw/$y"

        memory.get(memKey(key))?.let { return it }

        if (inflight.add(memKey(key))) {
            scope.launch(Dispatchers.IO) {
                try {
                    val raw =
                        if (z > source.maxZoom) synthesizeFromAncestor(z, xw, y)
                        else loadDisk(key) ?: fetchKey(z, xw, y, key)
                    if (raw != null) memory.put(memKey(key), raw)
                } finally {
                    inflight.remove(memKey(key))
                }
            }
        }
        return null
    }

    /**
     * For zooms above the provider's ceiling (e.g. Mappls stops at 18 while nav rides at
     * 19–20): fetch the deepest real ancestor tile and upscale the matching sub-region to a
     * full 256 px tile. Memory-cached only — it's derived data, cheap to recreate.
     */
    private fun synthesizeFromAncestor(z: Int, x: Int, y: Int): Bitmap? {
        val za = source.maxZoom
        val d = z - za
        if (d <= 0 || d > 3) return null // >8× upscale is mush; renderer's fallback can cope
        val xa = x shr d; val ya = y shr d
        val ancKey = "$za/$xa/$ya"
        val anc = memory.get(memKey(ancKey))
            ?: (loadDisk(ancKey) ?: fetchKey(za, xa, ya, ancKey))?.also { memory.put(memKey(ancKey), it) }
            ?: return null
        val cells = 1 shl d
        val sub = anc.width / cells
        if (sub <= 0) return null
        val sx = (x and (cells - 1)) * sub
        val sy = (y and (cells - 1)) * sub
        return try {
            val crop = Bitmap.createBitmap(anc, sx, sy, sub, sub)
            val scaled = Bitmap.createScaledBitmap(crop, 256, 256, true)
            if (crop !== scaled && crop !== anc) crop.recycle()
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "Synth $z/$x/$y failed: ${e.message}"); null
        }
    }

    /**
     * Memory-cache-only peek — render-thread safe and never triggers a load. Used by the
     * renderer to substitute a cached neighbouring-zoom tile while the exact one loads.
     */
    fun getCached(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        return memory.get(memKey("$z/$xw/$y"))
    }

    /** Warm a single zoom level around a point right now (e.g. right after the rider zooms). */
    fun prefetchZoom(lat: Double, lng: Double, z: Int, radius: Int = 2) {
        if (z < 0) return
        val zc = minOf(z, source.maxZoom)
        scope.launch(Dispatchers.IO) { prefetchAround(lat, lng, zc, radius) }
    }

    /** Prefetch tiles around a point (and optionally a straight corridor) into disk. */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            for (z in 11..minOf(20, source.maxZoom)) {
                val radius = if (z in 15..16) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    if (z in 12..13) for (i in 1..8) {
                        val f = i / 9.0
                        count += prefetchAround(fromLat + (lat - fromLat) * f, fromLng + (lng - fromLng) * f, z, 1)
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            Log.i(TAG, "Prefetch (point) done — ~$count tiles ensured [${source.id}]")
        }
    }

    /** Prefetch tiles along the actual route polyline so offline riding has coverage. */
    fun prefetchRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        scope.launch(Dispatchers.IO) {
            var count = 0
            for (z in 12..minOf(16, source.maxZoom)) {
                val seen = HashSet<String>()
                val step = if (z >= 15) 1 else 3
                var i = 0
                while (i < route.size) {
                    val p = route[i]
                    val cx = Mercator.lngToTileX(p.lng, z).toInt()
                    val cy = Mercator.latToTileY(p.lat, z).toInt()
                    val r = if (z >= 15) 1 else 0
                    for (dx in -r..r) for (dy in -r..r) {
                        val key = "$z/${cx + dx}/${cy + dy}"
                        if (seen.add(key) && !diskFile(key).exists()) {
                            fetchKey(z, cx + dx, cy + dy, key); count++
                        }
                    }
                    i += step
                    if (count > MAX_PREFETCH_TILES) break
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            Log.i(TAG, "Prefetch (route) done — ~$count tiles ensured [${source.id}]")
        }
    }

    /**
     * Warm the tile cache around a single point (the rider's current location) BEFORE the
     * dash link is up — called on app open / first GPS fix. Attacks the "map loads slowly
     * on connect" problem: on a cold start nothing is cached, and tiles fetch over cellular
     * exactly as the network routing settles, so the dash map crawls. Pre-warming means the
     * tiles are already on disk when streaming begins.
     */
    fun prewarmLocation(lat: Double, lng: Double) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            // Dash rides at high zoom; warm the immediate area at the zooms it uses most,
            // widest radius at the tightest zoom where tiles are smallest/most numerous.
            for (z in 15..minOf(18, source.maxZoom)) {
                val radius = if (z >= 17) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (count > MAX_PREFETCH_TILES) break
            }
            Log.i(TAG, "Prewarm (location) done — ~$count tiles ensured [${source.id}]")
        }
    }

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "$z/$x/$y"
            if (!diskFile(key).exists()) { fetchKey(z, x, y, key); n++ }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    // Batched (block) fetching: up to 3 concurrent Mappls requests — their still-image
    // renders are slow (~0.3–0.8 s) but the server tolerates modest parallelism, and each
    // request now covers 4 tiles, so total request count is already quartered.
    private val blockPermits = java.util.concurrent.Semaphore(3)
    private val blocksInflight = ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /**
     * Fetch the 2×2 block containing (z,x,y) as one 512×512 image, slice into four 256 px
     * tiles, cache all four (disk + memory), and return the requested one. Concurrent
     * requests for tiles of the same block wait on one fetch instead of duplicating it.
     * Returns null on any failure so the caller falls back to a single-tile fetch.
     */
    private fun fetchBlock(z: Int, x: Int, y: Int): Bitmap? {
        val src = source
        val dir = diskDir
        val keyForSrc = if (src.requiresApiKey) apiKey else null
        if (src.requiresApiKey && keyForSrc == null) return null

        val bx = x - (x % 2); val by = y - (y % 2)
        val blockKey = "${src.cacheNamespace}|B$z/$bx/$by"
        val wantKey = "$z/$x/$y"

        // Dedup: one fetch per block; late arrivals wait, then read the cache.
        val myLatch = java.util.concurrent.CountDownLatch(1)
        val existing = blocksInflight.putIfAbsent(blockKey, myLatch)
        if (existing != null) {
            try { existing.await(12, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            return memory.get(memKey(wantKey)) ?: loadDisk(wantKey)
        }

        try {
            blockPermits.acquire()
            try {
                val urlStr = src.blockUrl(z, bx, by, keyForSrc) ?: return null
                val net = internetNetwork()
                val url = URL(urlStr)
                val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "Block $blockKey HTTP ${conn.responseCode}"); conn.disconnect(); return null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val img = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                if (img.width != 512 || img.height != 512) {
                    // API ignored/clamped size=512 → batching unsupported; caller falls back.
                    Log.w(TAG, "Block image is ${img.width}×${img.height}, expected 512 — disabling batch for this fetch")
                    return null
                }
                var wanted: Bitmap? = null
                for (dy in 0..1) for (dx in 0..1) {
                    val tx = bx + dx; val ty = by + dy
                    if (ty < 0 || ty >= (1 shl z)) continue
                    val tile = Bitmap.createBitmap(img, dx * 256, dy * 256, 256, 256)
                    val tKey = "$z/$tx/$ty"
                    memory.put(memKey(tKey), tile)
                    try {
                        val out = java.io.ByteArrayOutputStream()
                        tile.compress(Bitmap.CompressFormat.PNG, 100, out)
                        File(dir, tKey.replace('/', '_') + ".png").writeBytes(out.toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "Block tile $tKey disk write failed: ${e.message}")
                    }
                    if (tx == x && ty == y) wanted = tile
                }
                return wanted
            } finally {
                blockPermits.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Block $blockKey fetch failed: ${e.message}")
            return null
        } finally {
            blocksInflight.remove(blockKey)
            myLatch.countDown()
        }
    }

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".png")

    private fun loadDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetchKey(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null

        // Snapshot source + key + dir together so a mid-fetch switch can't write a tile
        // from provider A into provider B's cache directory.
        val src = source
        val dir = diskDir
        val keyForSrc = if (src.requiresApiKey) apiKey else null

        // ── Batched path (Mappls): one 512×512 image = the whole 2×2 block ──
        if (src.blockUrl(z, 0, 0, "probe") != null && z <= src.maxZoom) {
            fetchBlock(z, x, y)?.let { return it }
            // fall through to single-tile fetch if the block path failed
        }

        // Rate-limit network fetches to avoid hot loops on the radio.
        val now = System.currentTimeMillis()
        val wait = MIN_FETCH_GAP_MS - (now - lastFetchAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        lastFetchAt = System.currentTimeMillis()

        if (src.requiresApiKey && keyForSrc == null) {
            Log.w(TAG, "Tile $key skipped — ${src.displayName} needs an API key")
            return null
        }

        val net = internetNetwork()
        return try {
            val url = URL(src.url(z, x, ((y % max) + max) % max, keyForSrc))
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Tile $key HTTP ${conn.responseCode} [${src.id}]")
                conn.disconnect(); return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            // Only cache a tile that actually DECODES (see upstream note about cache poisoning).
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                Log.w(TAG, "Tile $key decoded null (${bytes.size}B) — not caching")
                return null
            }
            File(dir, key.replace('/', '_') + ".png").writeBytes(bytes)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Tile $key fetch failed [${src.id}]: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
}
