package com.example.northstar.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.ParcelUuid
import android.util.Log
import com.example.northstar.dash.nav.GeoPoint
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.pow

data class ParkedBikeLocation(
    val lat: Double,
    val lng: Double,
    val address: String = "",
    val note: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val source: String = "Auto-saved on Dash disconnect",
)

data class BleTagCandidate(
    val address: String,
    val name: String,
    val rssi: Int,
    val isJioOrFindHub: Boolean,
    val lastSeenMs: Long,
) {
    /** Estimated distance in meters from RSSI (log-distance path loss model). */
    val estimatedMeters: Double
        get() {
            val txPower = -59.0
            return 10.0.pow((txPower - rssi) / (10.0 * 2.2)).coerceIn(0.3, 50.0)
        }

    val proximityLabel: String
        get() = when {
            rssi >= -58 -> "Right here (< 2 m)"
            rssi >= -70 -> "Very close (~3–5 m)"
            rssi >= -82 -> "Nearby (~6–12 m)"
            else        -> "Faint signal (> 15 m)"
        }
}

data class BikeTrackerState(
    val parked: ParkedBikeLocation? = null,
    val pairedTagMac: String = "",
    val pairedTagName: String = "JioTag 2 (Guerrilla 450)",
    val scanningBle: Boolean = false,
    val liveTag: BleTagCandidate? = null,
    val nearbyCandidates: List<BleTagCandidate> = emptyList(),
)

/**
 * Manages bike location & JioTag 2 integration:
 *  1. Auto-Parking Pin: Automatically records exact GPS + street address whenever the bike's
 *     Tripper Dash disconnects (ignition off), and syncs to Firestore when signed in.
 *  2. JioTag 2 Live BLE Proximity Radar: Scans for the JioTag 2's BLE advertisement
 *     (Google Find Hub 0xFEAA / Fast Pair 0xFE2C / JioTag BLE) to show live RSSI signal
 *     strength and estimated meters to walk straight to the bike in parking lots.
 *  3. Deep-link bridge to Google Find Hub (Find My Device) & JioThings for ringing the
 *     120 dB buzzer or checking remote crowd-sourced network location.
 *     (Note: Google Find My Device network uses end-to-end encrypted beacons and does not
 *     expose a public third-party REST API, so we combine local BLE radar + Auto-Parking GPS
 *     + 1-tap Find Hub deep-linking + coordinate/share import.)
 */
class BikeTrackerManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "BikeTrackerManager"
        private const val PREFS = "bike_tracker_prefs"
        private const val KEY_LAT = "parked_lat"
        private const val KEY_LNG = "parked_lng"
        private const val KEY_ADDR = "parked_addr"
        private const val KEY_NOTE = "parked_note"
        private const val KEY_TIME = "parked_time"
        private const val KEY_SRC = "parked_src"
        private const val KEY_TAG_MAC = "jiotag_mac"
        private const val KEY_TAG_NAME = "jiotag_name"

        // Google Find My Device / Eddystone EID service UUID (used by JioTag 2 / JioTag Go on Android)
        private val UUID_GOOGLE_FMDN = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
        private val UUID_FAST_PAIR   = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
        // Apple Find My / Continuity service UUID
        private val UUID_FIND_MY     = ParcelUuid.fromString("0000fd44-0000-1000-8000-00805f9b34fb")

        @Volatile private var instance: BikeTrackerManager? = null
        fun get(context: Context): BikeTrackerManager =
            instance ?: synchronized(this) {
                instance ?: BikeTrackerManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(loadInitialState())
    val state = _state.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private val seenMap = LinkedHashMap<String, BleTagCandidate>()

    private fun loadInitialState(): BikeTrackerState {
        val hasParked = prefs.contains(KEY_LAT) && prefs.contains(KEY_LNG)
        val parked = if (hasParked) {
            val lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
            val lng = Double.fromBits(prefs.getLong(KEY_LNG, 0L))
            if (lat != 0.0 || lng != 0.0) {
                ParkedBikeLocation(
                    lat = lat,
                    lng = lng,
                    address = prefs.getString(KEY_ADDR, "") ?: "",
                    note = prefs.getString(KEY_NOTE, "") ?: "",
                    timestampMs = prefs.getLong(KEY_TIME, 0L),
                    source = prefs.getString(KEY_SRC, "Auto-saved") ?: "Auto-saved",
                )
            } else null
        } else null

        return BikeTrackerState(
            parked = parked,
            pairedTagMac = prefs.getString(KEY_TAG_MAC, "") ?: "",
            pairedTagName = prefs.getString(KEY_TAG_NAME, "JioTag 2 (Guerrilla 450)") ?: "JioTag 2 (Guerrilla 450)",
        )
    }

    /** Record the bike's parked location (called automatically when Dash disconnects, or manually). */
    fun recordParking(lat: Double, lng: Double, source: String = "Auto-saved on Dash disconnect", note: String? = null) {
        if (lat == 0.0 && lng == 0.0) return
        val existingNote = note ?: _state.value.parked?.note.orEmpty()
        val now = System.currentTimeMillis()
        val initial = ParkedBikeLocation(
            lat = lat,
            lng = lng,
            address = "Resolving street address…",
            note = existingNote,
            timestampMs = now,
            source = source,
        )
        saveParked(initial)

        scope.launch {
            val addr = reverseGeocode(lat, lng)
            val updated = initial.copy(address = addr.ifBlank { "%.5f, %.5f".format(lat, lng) })
            saveParked(updated)
            syncToCloud(updated)
        }
    }

    fun updateParkingNote(note: String) {
        val current = _state.value.parked ?: return
        val updated = current.copy(note = note.trim())
        saveParked(updated)
        scope.launch { syncToCloud(updated) }
    }

    fun pairBleTag(mac: String, name: String) {
        val cleanMac = mac.trim().uppercase()
        val cleanName = name.trim().ifBlank { "JioTag 2 (Guerrilla 450)" }
        prefs.edit()
            .putString(KEY_TAG_MAC, cleanMac)
            .putString(KEY_TAG_NAME, cleanName)
            .apply()
        val live = seenMap[cleanMac]
        _state.value = _state.value.copy(
            pairedTagMac = cleanMac,
            pairedTagName = cleanName,
            liveTag = live,
        )
    }

    fun unpairBleTag() {
        prefs.edit().remove(KEY_TAG_MAC).apply()
        _state.value = _state.value.copy(pairedTagMac = "", liveTag = null)
    }

    private fun saveParked(loc: ParkedBikeLocation) {
        prefs.edit()
            .putLong(KEY_LAT, loc.lat.toRawBits())
            .putLong(KEY_LNG, loc.lng.toRawBits())
            .putString(KEY_ADDR, loc.address)
            .putString(KEY_NOTE, loc.note)
            .putLong(KEY_TIME, loc.timestampMs)
            .putString(KEY_SRC, loc.source)
            .apply()
        _state.value = _state.value.copy(parked = loc)
    }

    private fun syncToCloud(loc: ParkedBikeLocation) {
        if (!FirebaseGate.isConfigured(context)) return
        runCatching {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("bike_location").document("current")
                .set(
                    mapOf(
                        "lat" to loc.lat,
                        "lng" to loc.lng,
                        "address" to loc.address,
                        "note" to loc.note,
                        "timestampMs" to loc.timestampMs,
                        "source" to loc.source,
                    )
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String = try {
        if (!Geocoder.isPresent()) ""
        else {
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
            if (a == null) ""
            else {
                val parts = listOfNotNull(
                    a.featureName?.takeIf { it.isNotBlank() && !it.contains("+") },
                    a.thoroughfare?.takeIf { it.isNotBlank() },
                    a.subLocality?.takeIf { it.isNotBlank() },
                    a.locality?.takeIf { it.isNotBlank() },
                ).distinct()
                if (parts.isNotEmpty()) parts.take(3).joinToString(", ")
                else a.getAddressLine(0).orEmpty()
            }
        }
    } catch (_: Exception) {
        ""
    }

    // ── Live BLE Proximity Scanner for JioTag 2 ──────────────────────────

    @SuppressLint("MissingPermission")
    fun startBleRadar() {
        if (_state.value.scanningBle) return
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return

        seenMap.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val r = result ?: return
                val dev = r.device ?: return
                val mac = dev.address?.uppercase() ?: return
                val rawName = runCatching { dev.name }.getOrNull()
                    ?: r.scanRecord?.deviceName
                    ?: ""
                val uuids = r.scanRecord?.serviceUuids.orEmpty()
                val mfg = r.scanRecord?.manufacturerSpecificData
                // Detect JioTag / Google Find Hub (0xFEAA / 0xFE2C) / Apple Find My (0xFD44 / 0x004C)
                val isTracker = rawName.contains("jio", ignoreCase = true) ||
                    rawName.contains("tag", ignoreCase = true) ||
                    rawName.contains("tripper", ignoreCase = true) ||
                    rawName.contains("re_", ignoreCase = true) ||
                    uuids.any { it == UUID_GOOGLE_FMDN || it == UUID_FAST_PAIR || it == UUID_FIND_MY } ||
                    (mfg != null && (mfg.indexOfKey(0x00E0) >= 0 || mfg.indexOfKey(0x004C) >= 0))

                val displayName = when {
                    rawName.isNotBlank() -> rawName
                    uuids.any { it == UUID_GOOGLE_FMDN || it == UUID_FAST_PAIR } -> "JioTag 2 / Find Hub Tracker"
                    isTracker -> "Smart Tag ($mac)"
                    else -> "BLE Device ($mac)"
                }

                val prev = seenMap[mac]
                val smoothRssi = if (prev != null) ((prev.rssi * 0.6) + (r.rssi * 0.4)).toInt() else r.rssi
                val cand = BleTagCandidate(
                    address = mac,
                    name = displayName,
                    rssi = smoothRssi,
                    isJioOrFindHub = isTracker,
                    lastSeenMs = System.currentTimeMillis(),
                )
                seenMap[mac] = cand

                val pairedMac = _state.value.pairedTagMac
                val live = when {
                    pairedMac.isNotBlank() -> seenMap[pairedMac]
                    else -> seenMap.values.filter { it.isJioOrFindHub }.maxByOrNull { it.rssi }
                }
                val sorted = seenMap.values
                    .sortedWith(compareByDescending<BleTagCandidate> { it.isJioOrFindHub }.thenByDescending { it.rssi })
                    .take(8)
                _state.value = _state.value.copy(
                    liveTag = live,
                    nearbyCandidates = sorted,
                )
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, cb)
            scanCallback = cb
            _state.value = _state.value.copy(scanningBle = true)
            Log.i(TAG, "BLE JioTag radar started")
        } catch (e: Exception) {
            Log.w(TAG, "BLE radar start failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleRadar() {
        val cb = scanCallback ?: return
        scanCallback = null
        _state.value = _state.value.copy(scanningBle = false)
        runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.bluetoothLeScanner?.stopScan(cb)
        }
    }

    /** Launch Google's Find Hub (Find My Device) app where JioTag 2 is registered for remote crowd-sourced locating & 120dB ringing. */
    fun openGoogleFindHub(context: Context) {
        val pkg = "com.google.android.apps.adm"
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent); return }
        }
        // Fallback to web Find My Device hub
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/android/find")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(webIntent) }
    }

    /** Launch JioThings app if the user paired their JioTag in JioThings mode. */
    fun openJioThings(context: Context) {
        val pkg = "com.jio.jiothings"
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent); return }
        }
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(marketIntent) }
    }

    /** Distance in meters from rider's current phone position to the parked bike. */
    fun distanceToBikeMeters(riderLat: Double?, riderLng: Double?): Double? {
        val p = _state.value.parked ?: return null
        if (riderLat == null || riderLng == null) return null
        return GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(p.lat, p.lng))
    }
}
