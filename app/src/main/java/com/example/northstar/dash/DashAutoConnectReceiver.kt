package com.example.northstar.dash

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import com.example.northstar.data.DashPrefs
import com.example.northstar.viewmodel.DashViewModel

/**
 * Manifest receiver that wakes WayDash in the background (with phone screen OFF in pocket):
 *  - [WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION]: Fired by Android OS the
 *    moment the phone auto-joins the bike's `RE_*` Wi-Fi via [FastReconnectManager].
 *  - [BluetoothDevice.ACTION_ACL_CONNECTED]: When the rider's helmet or bike Bluetooth links,
 *    wakes the Wi-Fi scanner across the Tripper Dash's 15–35s cold-boot window while screen-off.
 *  - [ACTION_BLE_BIKE_WAKE]: Hardware-offloaded zero-battery BLE `PendingIntent` wakeup when
 *    the bike's Bluetooth/Tripper radio or paired JioTag 2 comes in range.
 *  - [Intent.ACTION_BOOT_COMPLETED] / [Intent.ACTION_MY_PACKAGE_REPLACED]: Re-arms passive
 *    Wi-Fi suggestions, BLE hardware filters, and scan watchers after phone reboot or app update.
 */
class DashAutoConnectReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DashAutoReceiver"
        const val ACTION_BLE_BIKE_WAKE = "com.example.northstar.ACTION_BLE_BIKE_WAKE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return

        // Acquire a bounded pocket WakeLock immediately BEFORE returning from onReceive() so
        // Android's Doze & Cached App Freezer cannot freeze the process mid-handshake while
        // the phone screen is OFF in the rider's pocket.
        FastReconnectManager.acquirePocketWakeLock(appCtx)
        val pendingResult = goAsync()
        Thread {
            try {
                when (action) {
                    WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION -> {
                        Log.i(TAG, "OS auto-joined suggested bike Wi-Fi → starting Dash session in background")
                        FastReconnectManager.ensureWatchersRegistered(appCtx)
                        DashViewModel.getInstance(appCtx).autoConnectFromBackground()
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        Log.i(TAG, "Bluetooth device connected → scheduling screen-off bike Wi-Fi scans (0s..42s)")
                        FastReconnectManager.schedulePostBluetoothChecks(appCtx, triggerActiveScans = true)
                    }
                    ACTION_BLE_BIKE_WAKE -> {
                        Log.i(TAG, "Hardware BLE bike/tracker wake → scheduling screen-off bike Wi-Fi scans")
                        FastReconnectManager.onBleIgnitionSighting(appCtx)
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        Log.i(TAG, "Boot/update ($action) → syncing Wireless Auto-Connect suggestions & BLE wake")
                        FastReconnectManager.sync(appCtx, forceReset = false)
                        FastReconnectManager.schedulePostBluetoothChecks(appCtx, triggerActiveScans = true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onReceive($action) failed: ${e.message}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }.start()
    }
}
