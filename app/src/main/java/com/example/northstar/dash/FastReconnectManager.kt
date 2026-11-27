package com.example.northstar.dash

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.northstar.data.BikeTrackerManager
import com.example.northstar.data.DashPrefs
import com.example.northstar.util.DeviceReadiness
import com.example.northstar.viewmodel.ConnStage
import com.example.northstar.viewmodel.DashViewModel
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wireless Android-Auto style background & screen-off pocket auto-connect manager.
 *
 * Combines hardware-offloaded and OS-level mechanisms so the phone connects to the bike's
 * Tripper Dash automatically while locked in the rider's pocket (screen OFF, app closed):
 *  1. [WifiNetworkSuggestion] (API 29+, priority 100) + optional System Saved Wi-Fi
 *     ([Settings.ACTION_WIFI_ADD_NETWORKS]): Tells Android OS & Wi-Fi PNO firmware to auto-join
 *     the learned `RE_*` hotspot.
 *  2. Screen-Off Wi-Fi Scan Wakeups on Bike Ignition (`ACTION_ACL_CONNECTED`, USB power, or BLE):
 *     Android suspends periodic Wi-Fi scans when the screen is OFF. When the bike's Bluetooth,
 *     paired JioTag/Tripper BLE, or switched USB-C port wakes the phone, we hold a bounded 45s
 *     `PARTIAL_WAKE_LOCK` and trigger up to 4 staggered `wifi.startScan()` calls across the
 *     Tripper Dash's 15–35s cold-boot window (strictly within Android's 4-scans/2-min quota).
 *  3. Hardware-Offloaded BLE `PendingIntent` Filter (`SCAN_MODE_LOW_POWER`):
 *     Runs in the Bluetooth controller chip at ~0% idle battery even when the app is killed,
 *     waking [DashAutoConnectReceiver] when the bike's BLE radio or paired JioTag 2 is in range.
 *  4. Passive [ConnectivityManager.NetworkCallback] + runtime BT/USB/Wi-Fi receivers (kept alive
 *     24/7 when [com.example.northstar.media.NorthstarNotificationListener] is enabled).
 */
object FastReconnectManager {
    private const val TAG = "FastReconnectMgr"
    private const val UNKNOWN_SSID = "<unknown ssid>"
    // Cooldown after user explicitly taps Disconnect so the app doesn't immediately re-link
    internal const val MANUAL_DISCONNECT_COOLDOWN_MS = 120_000L
    // Cooldown after a real streaming ride ends so residual cached scans don't re-wake after key-off
    internal const val SESSION_END_COOLDOWN_MS = 45_000L
    // Maximum age of a Wi-Fi ScanResult to be considered "live" (20 seconds in microseconds)
    private const val MAX_SCAN_RESULT_AGE_US = 20_000_000L
    // Minimum RSSI (dBm) to trigger auto-connect so weak ghost beacons don't wake the app
    private const val MIN_SCAN_RSSI_DBM = -82
    // Pocket WakeLock lease covering Tripper AP boot + DHCP + RSA handshake while screen is OFF
    private const val POCKET_WAKELOCK_MS = 45_000L
    // Android allows 4 background startScan() calls per 2-minute window
    private const val BG_SCAN_WINDOW_MS = 125_000L
    private const val BG_SCAN_MAX_PER_WINDOW = 4
    private const val BG_SCAN_MIN_INTERVAL_MS = 5_500L
    private const val BLE_WAKE_COOLDOWN_MS = 40_000L
    private const val BLE_PENDING_INTENT_REQ_CODE = 4709

    private val BIKE_BT_NAME_TOKENS = listOf(
        "re_", "royal", "enfield", "tripper", "guerrilla", "himalayan",
        "bluarmor", "sena", "cardo", "intercom", "helmet",
    )

    @Volatile private var scanCallbackRegistered = false
    @Volatile private var networkCallbackRegistered = false
    @Volatile private var wifiStateReceiverRegistered = false
    @Volatile private var pocketTriggerReceiverRegistered = false
    @Volatile private var lastManualDisconnectMs = 0L
    @Volatile private var lastSessionEndMs = 0L
    @Volatile private var lastAutoTriggerMs = 0L
    @Volatile private var lastBleWakeMs = 0L
    @Volatile private var lastPostBtScheduleMs = 0L
    @Volatile private var lastTriggeredScanTimestampUs = 0L
    @Volatile private var lastSyncedSignature: String? = null
    @Volatile private var lastBleFilterSignature: String? = null

    private val wakeLockSync = Any()
    private var pocketWakeLock: PowerManager.WakeLock? = null

    private val bgScanSync = Any()
    private val recentBgScanTimestampsMs = ArrayDeque<Long>()

    private val scanExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "waydash-passive-scan").apply { isDaemon = true }
        }
    }

    /** Staggered post-ignition check schedule covering the Tripper Dash's 15–35s Linux AP boot. */
    internal fun buildPostIgnitionCheckDelaysSec(): LongArray =
        longArrayOf(0L, 3L, 7L, 12L, 18L, 25L, 33L, 42L)

    /** Strategic active Wi-Fi scan wakeups (<= 4 scans in 2 min) while screen is OFF in pocket. */
    internal fun buildWifiScanTriggerDelaysSec(): LongArray =
        longArrayOf(2L, 9L, 18L, 30L)

    /** Pure gate helper (unit-testable without Android framework mocks). */
    internal fun shouldAllowAutoConnect(
        wirelessAutoEnabled: Boolean,
        nowMs: Long,
        lastManualDisconnectMs: Long,
        lastSessionEndMs: Long,
        lastAutoTriggerMs: Long,
        fromForeground: Boolean,
        forScanResult: Boolean,
        minTriggerGapMs: Long,
    ): Boolean {
        if (!wirelessAutoEnabled) return false
        if (lastManualDisconnectMs > 0L && nowMs - lastManualDisconnectMs < MANUAL_DISCONNECT_COOLDOWN_MS) {
            return false
        }
        val sessionCooldown = if (forScanResult) SESSION_END_COOLDOWN_MS else 10_000L
        if (!fromForeground && lastSessionEndMs > 0L && nowMs - lastSessionEndMs < sessionCooldown) {
            return false
        }
        if (!fromForeground && lastAutoTriggerMs > 0L && nowMs - lastAutoTriggerMs < minTriggerGapMs) {
            return false
        }
        return true
    }

    /** Pure rate-limiter helper ensuring we never exceed Android's 4-scans/2-min background quota. */
    internal fun canTriggerBackgroundWifiScan(
        nowMs: Long,
        historyMs: MutableList<Long>,
    ): Boolean {
        historyMs.removeAll { nowMs - it > BG_SCAN_WINDOW_MS }
        val last = historyMs.lastOrNull()
        if (last != null && nowMs - last < BG_SCAN_MIN_INTERVAL_MS) return false
        if (historyMs.size >= BG_SCAN_MAX_PER_WINDOW) return false
        historyMs.add(nowMs)
        return true
    }

    fun acquirePocketWakeLock(context: Context, durationMs: Long = POCKET_WAKELOCK_MS) {
        val appCtx = context.applicationContext
        synchronized(wakeLockSync) {
            runCatching {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pocketWakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "northstar:pocket-autoconnect",
                ).apply { setReferenceCounted(false) }.also { pocketWakeLock = it }
                wl.acquire(durationMs)
            }
        }
    }

    fun releasePocketWakeLock() {
        synchronized(wakeLockSync) {
            runCatching {
                if (pocketWakeLock?.isHeld == true) pocketWakeLock?.release()
            }
        }
    }

    fun notifyManualDisconnect() {
        lastManualDisconnectMs = System.currentTimeMillis()
        releasePocketWakeLock()
    }

    fun notifySessionStarted() {
        releasePocketWakeLock()
    }

    fun notifySessionEnded() {
        lastSessionEndMs = System.currentTimeMillis()
        releasePocketWakeLock()
    }

    fun clearManualDisconnectCooldown() {
        lastManualDisconnectMs = 0L
        lastSessionEndMs = 0L
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean =
        DeviceReadiness.hasBackgroundLocationPermission(context)

    private fun wifi(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun cm(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Build a 1-tap system intent ([Settings.ACTION_WIFI_ADD_NETWORKS], API 30+) so the rider can
     * save their paired `RE_*` hotspot into Android's OS-managed Saved Wi-Fi list for hardware PNO
     * screen-off auto-join. Must NOT set `FLAG_ACTIVITY_NEW_TASK` when launched via
     * `ActivityResultLauncher`, otherwise Android immediately returns `RESULT_CANCELED`.
     */
    fun createSystemSaveWifiIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val cfg = DashConfig.get(context.applicationContext)
        val ssid = cfg.ssid.trim()
        if (ssid.isBlank()) return null
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                if (cfg.password.isNotBlank()) setWpa2Passphrase(cfg.password)
                setIsInitialAutojoinEnabled(true)
            }
            .build()
        val list = ArrayList<WifiNetworkSuggestion>(1).apply { add(suggestion) }
        val bundle = Bundle().apply {
            putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, list)
        }
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putExtras(bundle)
    }

    /**
     * Returns true if the phone is currently associated with the bike's `RE_*` Wi-Fi.
     * Used as a safety guard so we NEVER call `removeNetworkSuggestions()` while connected
     * (Android immediately disconnects the active Wi-Fi link whenever its suggestion is removed!).
     */
    @SuppressLint("MissingPermission")
    fun isCurrentlyOnBikeWifi(context: Context): Boolean {
        val appCtx = context.applicationContext
        val cfg = DashConfig.get(appCtx)
        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return false
        val connSsid = runCatching {
            @Suppress("DEPRECATION")
            w.connectionInfo?.ssid.orEmpty().trim('"')
        }.getOrDefault("")
        if (connSsid.isNotBlank() && connSsid != UNKNOWN_SSID) {
            if ((cfg.ssid.isNotBlank() && connSsid == cfg.ssid) || connSsid.startsWith(cfg.ssidPrefix)) {
                return true
            }
        }
        val connMgr = cm(appCtx)
        return runCatching {
            connMgr.allNetworks.any { net ->
                val caps = connMgr.getNetworkCapabilities(net) ?: return@any false
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@any false
                val info = caps.transportInfo as? WifiInfo
                val ssid = info?.ssid.orEmpty().trim('"')
                if (ssid.isNotBlank() && ssid != UNKNOWN_SSID) {
                    (cfg.ssid.isNotBlank() && ssid == cfg.ssid) || ssid.startsWith(cfg.ssidPrefix)
                } else if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    val lp = connMgr.getLinkProperties(net)
                    val addrs = lp?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty()
                    val routes = lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty()
                    (addrs + routes).any { it.startsWith("192.168.1.") || it.startsWith("192.168.43.") }
                } else false
            }
        }.getOrDefault(false)
    }

    /** Ensure passive OS listeners and BLE ignition wake filters are registered. */
    fun ensureWatchersRegistered(context: Context) {
        val appCtx = context.applicationContext
        registerPassiveNetworkWatcher(appCtx)
        registerPassiveScanWatcher(appCtx)
        registerRuntimePocketTriggers(appCtx)
        syncBleIgnitionWake(appCtx)
    }

    /**
     * Called when the user opens or resumes [com.example.northstar.MainActivity].
     * Ensures passive watchers are active, promotes [DashKeepAliveService] if streaming started
     * headlessly in the pocket, and checks if the phone is ALREADY connected to the bike's Wi-Fi.
     */
    fun onAppForegrounded(context: Context) {
        val appCtx = context.applicationContext
        lastSessionEndMs = 0L
        ensureWatchersRegistered(appCtx)
        val vm = DashViewModel.getInstance(appCtx)
        if (vm.ui.value.stage == ConnStage.STREAMING && !DashKeepAliveService.isRunning) {
            DashKeepAliveService.start(appCtx, fromBackground = false)
        }
        if (!isCurrentlyOnBikeWifi(appCtx)) {
            sync(appCtx, forceReset = false)
        }
        scanExecutor.execute {
            checkActiveWifiAndAutoConnect(appCtx, fromForeground = true)
            checkScanAndAutoConnect(appCtx, fromForeground = true)
        }
    }

    /** Debounced entry point when hardware BLE `PendingIntent` detects the bike or paired JioTag. */
    fun onBleIgnitionSighting(context: Context) {
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return
        val now = System.currentTimeMillis()
        if (now - lastBleWakeMs < BLE_WAKE_COOLDOWN_MS) return
        val vm = DashViewModel.getInstance(appCtx)
        if (vm.ui.value.stage == ConnStage.STREAMING || vm.ui.value.stage == ConnStage.AUTH) return
        lastBleWakeMs = now
        schedulePostBluetoothChecks(appCtx, triggerActiveScans = true)
    }

    /**
     * When the bike's Bluetooth links (`ACTION_ACL_CONNECTED`), BLE beacon is seen, or handlebar
     * USB power turns on, the Tripper Dash Linux Wi-Fi AP typically finishes booting 12–30 seconds
     * later. While the phone screen is OFF in a pocket, Android suspends periodic Wi-Fi scans — so
     * we hold a short `PARTIAL_WAKE_LOCK`, schedule passive checks over 0..42s, and fire up to 4
     * staggered `wifi.startScan()` wakeups so the OS immediately discovers and joins `RE_*`.
     */
    fun schedulePostBluetoothChecks(context: Context, triggerActiveScans: Boolean = true) {
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return
        val now = System.currentTimeMillis()
        val alreadyScheduledRecently = now - lastPostBtScheduleMs < 8_000L
        lastPostBtScheduleMs = now

        acquirePocketWakeLock(appCtx, POCKET_WAKELOCK_MS)
        ensureWatchersRegistered(appCtx)
        if (!isCurrentlyOnBikeWifi(appCtx)) {
            sync(appCtx, forceReset = false)
        }

        if (alreadyScheduledRecently) {
            scanExecutor.execute {
                checkActiveWifiAndAutoConnect(appCtx)
                checkScanAndAutoConnect(appCtx)
            }
            return
        }

        for (delaySec in buildPostIgnitionCheckDelaysSec()) {
            scanExecutor.schedule({
                val stage = DashViewModel.getInstance(appCtx).ui.value.stage
                if (stage == ConnStage.STREAMING) return@schedule
                checkActiveWifiAndAutoConnect(appCtx)
                checkScanAndAutoConnect(appCtx)
            }, delaySec, TimeUnit.SECONDS)
        }

        if (triggerActiveScans) {
            for (scanSec in buildWifiScanTriggerDelaysSec()) {
                scanExecutor.schedule({
                    val stage = DashViewModel.getInstance(appCtx).ui.value.stage
                    if (stage == ConnStage.STREAMING || stage == ConnStage.AUTH) return@schedule
                    if (isCurrentlyOnBikeWifi(appCtx)) {
                        checkActiveWifiAndAutoConnect(appCtx)
                        return@schedule
                    }
                    triggerThrottledBackgroundWifiScan(appCtx)
                }, scanSec, TimeUnit.SECONDS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerThrottledBackgroundWifiScan(appCtx: Context) {
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return
        val now = System.currentTimeMillis()
        if (now - lastManualDisconnectMs < MANUAL_DISCONNECT_COOLDOWN_MS) return
        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return

        val allowed = synchronized(bgScanSync) {
            val list = recentBgScanTimestampsMs.toMutableList()
            val ok = canTriggerBackgroundWifiScan(now, list)
            if (ok) {
                recentBgScanTimestampsMs.clear()
                recentBgScanTimestampsMs.addAll(list)
            }
            ok
        }
        if (!allowed) return

        runCatching {
            @Suppress("DEPRECATION")
            val started = w.startScan()
            Log.i(TAG, "Triggered screen-off Wi-Fi scan for bike hotspot (started=$started)")
        }.onFailure { e ->
            Log.d(TAG, "Background startScan failed: ${e.message}")
        }
    }

    /**
     * Synchronize the [WifiNetworkSuggestion] registration with the user's current settings
     * and learned `RE_*` SSID, and ensure the passive network, BLE, & scan watchers are active.
     *
     * IMPORTANT: On Android, calling `removeNetworkSuggestions()` while connected to a suggested
     * Wi-Fi IMMEDIATELY disconnects that Wi-Fi! Therefore this method is strictly idempotent:
     * it checks existing suggestions first and NEVER removes/re-adds an already-active suggestion
     * unless [forceReset] is true (e.g., user explicitly changed SSID/password or disabled the feature).
     */
    @SuppressLint("MissingPermission")
    fun sync(context: Context, forceReset: Boolean = false): String {
        val appCtx = context.applicationContext
        ensureWatchersRegistered(appCtx)

        if (!DashPrefs.wirelessAutoConnect(appCtx)) {
            lastSyncedSignature = null
            clearSuggestions(appCtx)
            disarmBleIgnitionWake(appCtx)
            return "Off · manual or in-app connect only"
        }

        val cfg = DashConfig.get(appCtx)
        val ssid = cfg.ssid
        if (ssid.isBlank()) {
            return "Active · passive watcher waiting for first ${cfg.ssidPrefix}* pairing"
        }

        val requireInteraction = hasBackgroundLocationPermission(appCtx)
        val signature = "$ssid|${cfg.password}|$requireInteraction|p100"

        // Fast idempotency check: if we already registered this exact suggestion, don't touch WifiManager!
        if (!forceReset && lastSyncedSignature == signature) {
            return "Active · OS auto-joins $ssid even when app is closed (0% idle battery)"
        }

        return try {
            val w = wifi(appCtx)
            // Safety guard: never remove suggestions while actively connected to the bike's Wi-Fi
            if (!forceReset && isCurrentlyOnBikeWifi(appCtx)) {
                Log.d(TAG, "Currently connected to bike Wi-Fi '$ssid' — deferring suggestion update")
                return "Active · OS auto-joins $ssid even when app is closed (0% idle battery)"
            }

            if (!forceReset && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val existing = runCatching { w.networkSuggestions }.getOrNull().orEmpty()
                val exactMatch = existing.any {
                    it.ssid == ssid && it.isAppInteractionRequired == requireInteraction && it.priority == 100
                }
                if (exactMatch) {
                    lastSyncedSignature = signature
                    Log.d(TAG, "WifiNetworkSuggestion already active for '$ssid' — skipping re-register")
                    return "Active · OS auto-joins $ssid even when app is closed (0% idle battery)"
                }
                // Remove stale variant (e.g. after user upgraded location permission) while NOT on bike Wi-Fi
                val staleForSsid = existing.filter { it.ssid == ssid }
                if (staleForSsid.isNotEmpty()) {
                    runCatching { w.removeNetworkSuggestions(staleForSsid) }
                }
            }

            val activeSuggestions = buildSuggestions(appCtx, ssid, cfg.password)
            if (forceReset) {
                runCatching { w.removeNetworkSuggestions(buildAllSuggestionVariants(ssid, cfg.password)) }
            }
            val status = w.addNetworkSuggestions(activeSuggestions)
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            ) {
                lastSyncedSignature = signature
                Log.i(TAG, "WifiNetworkSuggestion active for '$ssid' (bgLoc=$requireInteraction, priority=100)")
                "Active · OS auto-joins $ssid even when app is closed (0% idle battery)"
            } else {
                Log.w(TAG, "addNetworkSuggestions status=$status")
                "Active · passive Wi-Fi watcher enabled for $ssid"
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            "Active · passive Wi-Fi watcher enabled"
        }
    }

    @SuppressLint("MissingPermission")
    fun clearSuggestions(context: Context) {
        val appCtx = context.applicationContext
        lastSyncedSignature = null
        val cfg = DashConfig.get(appCtx)
        val w = wifi(appCtx)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val existing = w.networkSuggestions
                if (existing.isNotEmpty()) {
                    w.removeNetworkSuggestions(existing)
                    return
                }
            }
            if (cfg.ssid.isNotBlank()) {
                w.removeNetworkSuggestions(buildAllSuggestionVariants(cfg.ssid, cfg.password))
            }
        }
    }

    private fun buildSuggestions(context: Context, ssid: String, password: String): List<WifiNetworkSuggestion> {
        val requireInteraction = hasBackgroundLocationPermission(context)
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setPriority(100)
            .setIsAppInteractionRequired(requireInteraction)
            .apply {
                if (password.isNotBlank()) setWpa2Passphrase(password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setIsInitialAutojoinEnabled(true)
                }
            }
            .build()
        return listOf(suggestion)
    }

    private fun buildAllSuggestionVariants(ssid: String, password: String): List<WifiNetworkSuggestion> {
        val out = ArrayList<WifiNetworkSuggestion>(4)
        for (interactive in listOf(true, false)) {
            for (prio in listOf(0, 100)) {
                out += WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setPriority(prio)
                    .setIsAppInteractionRequired(interactive)
                    .apply {
                        if (password.isNotBlank()) setWpa2Passphrase(password)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setIsInitialAutojoinEnabled(true)
                        }
                    }
                    .build()
            }
        }
        return out
    }

    /**
     * Registers a hardware-offloaded zero-battery BLE `PendingIntent` scan (`SCAN_MODE_LOW_POWER`)
     * with strict [ScanFilter]s matching ONLY the rider's paired JioTag 2, bonded Royal Enfield /
     * helmet Bluetooth MACs, or paired `RE_*` Tripper unit name.
     *
     * Because filters are non-empty, Android's Bluetooth controller chip keeps matching while the
     * phone screen is OFF in Deep Doze and wakes [DashAutoConnectReceiver] when beside the bike.
     */
    @SuppressLint("MissingPermission")
    fun syncBleIgnitionWake(context: Context) {
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) {
            disarmBleIgnitionWake(appCtx)
            return
        }
        if (!DeviceReadiness.hasBluetoothScanPermission(appCtx)) return
        val bm = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bm.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return

        val filters = ArrayList<ScanFilter>()
        val sigParts = ArrayList<String>()

        // 1. Paired JioTag 2 MAC (from BikeTrackerManager)
        val pairedTagMac = runCatching {
            BikeTrackerManager.get(appCtx).state.value.pairedTagMac.trim().uppercase()
        }.getOrDefault("")
        if (BluetoothAdapter.checkBluetoothAddress(pairedTagMac)) {
            filters += ScanFilter.Builder().setDeviceAddress(pairedTagMac).build()
            sigParts += "tag:$pairedTagMac"
        }

        // 2. Bonded bike / Tripper / helmet intercom Bluetooth MACs
        if (DeviceReadiness.hasBluetoothConnectPermission(appCtx)) {
            val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
            for (dev in bonded) {
                val addr = dev.address?.uppercase().orEmpty()
                val name = runCatching { dev.name.orEmpty() }.getOrDefault("")
                if (BluetoothAdapter.checkBluetoothAddress(addr) &&
                    BIKE_BT_NAME_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
                ) {
                    filters += ScanFilter.Builder().setDeviceAddress(addr).build()
                    sigParts += "bt:$addr"
                }
            }
        }

        // 3. Learned Tripper Dash unit name (RE_*)
        val learnedSsid = DashConfig.get(appCtx).ssid.trim()
        if (learnedSsid.isNotBlank()) {
            filters += ScanFilter.Builder().setDeviceName(learnedSsid).build()
            sigParts += "name:$learnedSsid"
        }

        if (filters.isEmpty()) return
        val sig = sigParts.sorted().joinToString("|")
        if (sig == lastBleFilterSignature) return

        try {
            val settingsBuilder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settingsBuilder
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            }
            val pi = buildBleWakePendingIntent(appCtx)
            runCatching { scanner.stopScan(pi) }
            val err = scanner.startScan(filters, settingsBuilder.build(), pi)
            if (err == 0) {
                lastBleFilterSignature = sig
                Log.i(TAG, "Armed hardware screen-off BLE ignition wake (${filters.size} filter(s): $sig)")
            } else {
                Log.w(TAG, "Hardware BLE PendingIntent startScan returned code=$err")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncBleIgnitionWake failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disarmBleIgnitionWake(appCtx: Context) {
        if (lastBleFilterSignature == null) return
        lastBleFilterSignature = null
        runCatching {
            val bm = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val scanner = bm?.adapter?.bluetoothLeScanner ?: return
            scanner.stopScan(buildBleWakePendingIntent(appCtx))
        }
    }

    private fun buildBleWakePendingIntent(appCtx: Context): PendingIntent {
        val intent = Intent(appCtx, DashAutoConnectReceiver::class.java).apply {
            action = DashAutoConnectReceiver.ACTION_BLE_BIKE_WAKE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(appCtx, BLE_PENDING_INTENT_REQ_CODE, intent, flags)
    }

    /**
     * Registers live runtime receivers for Bluetooth ignition link, handlebar USB power, and
     * screen wake. When [com.example.northstar.media.NorthstarNotificationListener] is enabled,
     * the app process stays alive 24/7 — so these runtime broadcasts fire instantaneously in the
     * pocket even on OEM ROMs (Nothing OS / OxygenOS / One UI) that delay manifest receivers!
     */
    private fun registerRuntimePocketTriggers(appCtx: Context) {
        if (pocketTriggerReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val act = intent?.action ?: return
                    if (!DashPrefs.wirelessAutoConnect(appCtx)) return
                    when (act) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            Log.i(TAG, "Runtime ACL_CONNECTED → waking screen-off Wi-Fi scanner")
                            schedulePostBluetoothChecks(appCtx, triggerActiveScans = true)
                        }
                        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                            if (st == BluetoothAdapter.STATE_CONNECTED) {
                                Log.i(TAG, "Runtime BT STATE_CONNECTED → waking screen-off Wi-Fi scanner")
                                schedulePostBluetoothChecks(appCtx, triggerActiveScans = true)
                            }
                        }
                        Intent.ACTION_POWER_CONNECTED -> {
                            Log.i(TAG, "USB/Power connected (handlebar ignition?) → checking bike Wi-Fi")
                            schedulePostBluetoothChecks(appCtx, triggerActiveScans = true)
                        }
                        Intent.ACTION_USER_PRESENT,
                        Intent.ACTION_SCREEN_ON -> {
                            scanExecutor.execute {
                                checkActiveWifiAndAutoConnect(appCtx)
                                checkScanAndAutoConnect(appCtx)
                            }
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                appCtx,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            pocketTriggerReceiverRegistered = true
            Log.i(TAG, "Live runtime BT/USB/screen pocket trigger receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerRuntimePocketTriggers failed: ${e.message}")
        }
    }

    /**
     * Registers a zero-battery passive [ConnectivityManager.NetworkCallback] for Wi-Fi networks
     * (including non-internet hotspots like the Tripper Dash) plus a runtime
     * [WifiManager.NETWORK_STATE_CHANGED_ACTION] receiver.
     */
    @SuppressLint("MissingPermission")
    fun registerPassiveNetworkWatcher(context: Context) {
        val appCtx = context.applicationContext
        if (!networkCallbackRegistered) {
            try {
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onAvailable(network: Network) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                            scheduleNetworkCheck(appCtx, network)
                        }
                    }
                }
                cm(appCtx).registerNetworkCallback(req, cb)
                networkCallbackRegistered = true
                Log.i(TAG, "Passive Wi-Fi ConnectivityManager callback registered")
            } catch (e: Exception) {
                Log.w(TAG, "registerPassiveNetworkWatcher failed: ${e.message}")
            }
        }

        if (!wifiStateReceiverRegistered) {
            try {
                val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                ContextCompat.registerReceiver(
                    appCtx,
                    object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            scheduleNetworkCheck(appCtx, null)
                        }
                    },
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
                wifiStateReceiverRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "registerReceiver(NETWORK_STATE_CHANGED) failed: ${e.message}")
            }
        }
    }

    private fun scheduleNetworkCheck(appCtx: Context, network: Network?) {
        scanExecutor.execute {
            checkActiveWifiAndAutoConnect(appCtx, candidateNetwork = network)
        }
        // Re-check at 1.2s and 2.8s once Tripper DHCP and captive-portal validation settle
        for (delayMs in longArrayOf(1200L, 2800L)) {
            scanExecutor.schedule({
                checkActiveWifiAndAutoConnect(appCtx, candidateNetwork = network, afterValidationDelay = true)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Checks if the phone is CURRENTLY connected to the bike's `RE_*` Wi-Fi network and starts
     * the Dash session automatically if not already connected.
     */
    @SuppressLint("MissingPermission")
    fun checkActiveWifiAndAutoConnect(
        context: Context,
        candidateNetwork: Network? = null,
        fromForeground: Boolean = false,
        afterValidationDelay: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        if (!shouldAllowAutoConnect(
                wirelessAutoEnabled = DashPrefs.wirelessAutoConnect(appCtx),
                nowMs = now,
                lastManualDisconnectMs = lastManualDisconnectMs,
                lastSessionEndMs = lastSessionEndMs,
                lastAutoTriggerMs = lastAutoTriggerMs,
                fromForeground = fromForeground,
                forScanResult = false,
                minTriggerGapMs = 3_000L,
            )
        ) return

        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return

        val vm = DashViewModel.getInstance(appCtx)
        val stage = vm.ui.value.stage
        if (stage == ConnStage.STREAMING || stage == ConnStage.AUTH) return
        // If already in ConnStage.WIFI (waiting passively for DHCP/association), nudge the manager
        // to adopt any newly ready network immediately instead of waiting for its next poll tick!
        if (stage == ConnStage.WIFI) {
            vm.nudgeBackgroundWifiCheck()
            return
        }

        val cfg = DashConfig.get(appCtx)
        val connMgr = cm(appCtx)
        val networks = buildList {
            if (candidateNetwork != null) add(candidateNetwork)
            runCatching { addAll(connMgr.allNetworks) }
        }.distinct()

        for (net in networks) {
            val caps = runCatching { connMgr.getNetworkCapabilities(net) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            val info = caps.transportInfo as? WifiInfo
            val capsSsid = info?.ssid.orEmpty().trim('"')
            val connSsid = runCatching {
                @Suppress("DEPRECATION")
                w.connectionInfo?.ssid.orEmpty().trim('"')
            }.getOrDefault("")

            val readableSsid = listOf(capsSsid, connSsid).firstOrNull {
                it.isNotBlank() && it != UNKNOWN_SSID
            }

            if (readableSsid != null) {
                val isMatch = (cfg.ssid.isNotBlank() && readableSsid == cfg.ssid) ||
                    readableSsid.startsWith(cfg.ssidPrefix)
                if (isMatch) {
                    lastAutoTriggerMs = now
                    acquirePocketWakeLock(appCtx)
                    if (cfg.ssid.isBlank()) {
                        cfg.ssid = readableSsid
                        sync(appCtx, forceReset = false)
                    }
                    Log.i(TAG, "Phone is connected to bike Wi-Fi '$readableSsid' (net=$net) → auto-starting Dash session")
                    vm.autoConnectFromBackground()
                    return
                }
                continue
            }

            // Redacted SSID fallback ("<unknown ssid>" in background without ACCESS_BACKGROUND_LOCATION):
            // Wait for `afterValidationDelay` (1.2s+) or `fromForeground` so a home 192.168.1.x router
            // validates internet and is skipped.
            if (cfg.ssid.isNotBlank() &&
                (afterValidationDelay || fromForeground) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                val lp = runCatching { connMgr.getLinkProperties(net) }.getOrNull()
                val addrs = lp?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty()
                val routes = lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty()
                val subnetMatch = (addrs + routes).any {
                    it.startsWith("192.168.1.") || it.startsWith("192.168.43.")
                } || runCatching {
                    @Suppress("DEPRECATION")
                    val dhcp = w.dhcpInfo
                    if (dhcp != null && dhcp.ipAddress != 0) {
                        val b0 = dhcp.ipAddress and 0xFF
                        val b1 = (dhcp.ipAddress shr 8) and 0xFF
                        val b2 = (dhcp.ipAddress shr 16) and 0xFF
                        b0 == 192 && b1 == 168 && (b2 == 1 || b2 == 43)
                    } else false
                }.getOrDefault(false)

                if (subnetMatch) {
                    lastAutoTriggerMs = now
                    acquirePocketWakeLock(appCtx)
                    Log.i(TAG, "Phone joined non-internet Dash subnet (192.168.1.x) for '${cfg.ssid}' → auto-starting Dash session")
                    vm.autoConnectFromBackground()
                    return
                }
            }
        }
    }

    /**
     * Registers a zero-battery passive listener on OS Wi-Fi scans (API 30+).
     */
    @SuppressLint("MissingPermission")
    fun registerPassiveScanWatcher(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (scanCallbackRegistered) return
        val appCtx = context.applicationContext
        val w = wifi(appCtx)
        try {
            w.registerScanResultsCallback(
                scanExecutor,
                object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        checkActiveWifiAndAutoConnect(appCtx)
                        checkScanAndAutoConnect(appCtx)
                    }
                }
            )
            scanCallbackRegistered = true
            Log.i(TAG, "Passive Wi-Fi scan watcher registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerScanResultsCallback failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun checkScanAndAutoConnect(context: Context, fromForeground: Boolean = false) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        if (!shouldAllowAutoConnect(
                wirelessAutoEnabled = DashPrefs.wirelessAutoConnect(appCtx),
                nowMs = now,
                lastManualDisconnectMs = lastManualDisconnectMs,
                lastSessionEndMs = lastSessionEndMs,
                lastAutoTriggerMs = lastAutoTriggerMs,
                fromForeground = fromForeground,
                forScanResult = true,
                minTriggerGapMs = 10_000L,
            )
        ) return

        val cfg = DashConfig.get(appCtx)
        val w = wifi(appCtx)
        if (!w.isWifiEnabled) return

        val nowElapsedUs = android.os.SystemClock.elapsedRealtime() * 1000L
        var newestMatchTimestampUs = 0L

        val foundSsid = try {
            val freshResults = w.scanResults.filter { sr ->
                val ageUs = nowElapsedUs - sr.timestamp
                ageUs in 0..MAX_SCAN_RESULT_AGE_US &&
                    sr.level >= MIN_SCAN_RSSI_DBM &&
                    (fromForeground || sr.timestamp > lastTriggeredScanTimestampUs)
            }
            val matched = if (cfg.ssid.isNotBlank()) {
                freshResults.firstOrNull { it.SSID?.trim('"') == cfg.ssid }
            } else {
                freshResults.firstOrNull {
                    val s = it.SSID?.trim('"').orEmpty()
                    s.isNotBlank() && s.startsWith(cfg.ssidPrefix)
                }
            }
            matched?.let {
                newestMatchTimestampUs = it.timestamp
                it.SSID?.trim('"')
            }
        } catch (_: Exception) {
            null
        } ?: return

        if (cfg.ssid.isBlank()) {
            cfg.ssid = foundSsid
            sync(appCtx, forceReset = false)
        } else if (!isCurrentlyOnBikeWifi(appCtx)) {
            // Ensure suggestion is synced so OS WifiConnectivityManager auto-associates immediately
            sync(appCtx, forceReset = false)
        }

        val vm = DashViewModel.getInstance(appCtx)
        val stage = vm.ui.value.stage
        if (stage == ConnStage.OFFLINE || stage == ConnStage.ERROR) {
            lastAutoTriggerMs = now
            lastTriggeredScanTimestampUs = newestMatchTimestampUs
            acquirePocketWakeLock(appCtx)
            Log.i(TAG, "Fresh bike hotspot '$foundSsid' detected (<20s old) → triggering background auto-connect")
            vm.autoConnectFromBackground()
        } else if (stage == ConnStage.WIFI) {
            vm.nudgeBackgroundWifiCheck()
        }
    }
}

