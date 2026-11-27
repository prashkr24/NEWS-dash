package com.example.northstar.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Cross-OEM background-execution readiness.
 *
 * Stock Android keeps a foreground service alive with the screen off, but OxygenOS, Samsung
 * One UI, MIUI, ColorOS/Realme etc. aggressively kill apps that aren't exempt from battery
 * optimization — which silently stops the dash stream (the whole point of the app) the moment
 * the screen goes off. The behaviour was device-dependent because nothing here asked for the
 * exemption; this makes it uniform.
 *
 * The `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog is a standard framework intent and
 * behaves the same on every OEM. The OEM-specific "Autostart" / "Sleeping apps" toggles can't be
 * granted programmatically — only the app's settings page can be opened for the rider to flip them.
 */
object DeviceReadiness {

    private const val PREFS = "northstar_readiness"
    private const val KEY_BATTERY_ASKED = "battery_exemption_asked"

    /** True if the OS won't throttle/kill us in the background (needed for screen-off streaming). */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Whether we've already shown the rider the battery-exemption dialog once. We fold the request
     * into the first Connect tap and remember it here, so we never re-prompt on later connects even
     * if they declined (they can still grant it later from system settings).
     */
    fun batteryExemptionAsked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATTERY_ASKED, false)

    fun markBatteryExemptionAsked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BATTERY_ASKED, true).apply()
    }

    /**
     * Standard system dialog to exempt the app from battery optimization — uniform across OEMs.
     * (Lint flags BatteryLife for Play Store apps; Northstar is sideloaded per-rider, and the
     * exemption is essential for the screen-off use case, so the prompt is justified.)
     */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's system settings page — every OEM exposes its battery/autostart controls here. */
    fun appSettingsIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Location services (the master toggle, not the permission) must be ON for WiFi scan results
     * to be returned on most OEMs — that's how a not-yet-paired dash SSID is discovered. Once the
     * dash is learned, scanning isn't needed, so this only matters on first pairing.
     */
    fun locationServicesEnabled(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    }.getOrDefault(true)

    /**
     * True when Android 12+ `BLUETOOTH_CONNECT` is granted (or API < 31).
     * Required on Android 12–16 for the OS to deliver `ACTION_ACL_CONNECTED` when the bike or
     * helmet intercom connects while the phone is in the rider's pocket.
     */
    fun hasBluetoothConnectPermission(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** True when Android 12+ `BLUETOOTH_SCAN` is granted (or API < 31) for BLE ignition wakeups. */
    fun hasBluetoothScanPermission(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.BLUETOOTH_SCAN,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** True when both Bluetooth permissions needed for pocket ignition detection are granted. */
    fun hasBluetoothIgnitionPermissions(ctx: Context): Boolean =
        hasBluetoothConnectPermission(ctx) && hasBluetoothScanPermission(ctx)

    /** True when `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") is granted (or API < 29). */
    fun hasBackgroundLocationPermission(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * True when all three Android 12–16 pocket auto-connect prerequisites are satisfied:
     *  1. Unrestricted battery (`isIgnoringBatteryOptimizations`) — grants FGS background start
     *     exemption and prevents Nothing OS / OxygenOS / One UI from freezing the app in pocket.
     *  2. Nearby Bluetooth (`BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN`) — enables `ACL_CONNECTED`
     *     and hardware-offloaded BLE ignition wakeups to trigger screen-off Wi-Fi scans.
     *  3. Background location (`ACCESS_BACKGROUND_LOCATION`) — enables unredacted SSID matching,
     *     `WIFI_NETWORK_SUGGESTION_POST_CONNECTION`, and live screen-off GPS updates.
     */
    fun isPocketAutoConnectReady(ctx: Context): Boolean =
        isIgnoringBatteryOptimizations(ctx) &&
            hasBluetoothIgnitionPermissions(ctx) &&
            hasBackgroundLocationPermission(ctx)
}
