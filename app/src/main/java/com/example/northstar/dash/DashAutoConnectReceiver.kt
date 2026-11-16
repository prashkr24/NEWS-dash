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
 * Manifest receiver that wakes WayDash in the background (without needing the app open):
 *  - [WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION]: Fired by Android OS the
 *    moment the phone auto-joins the bike's `RE_*` Wi-Fi via [FastReconnectManager].
 *  - [BluetoothDevice.ACTION_ACL_CONNECTED]: When the rider's helmet or bike Bluetooth links,
 *    proactively checks Wi-Fi scan results for the `RE_*` hotspot.
 *  - [Intent.ACTION_BOOT_COMPLETED] / [Intent.ACTION_MY_PACKAGE_REPLACED]: Re-arms passive
 *    Wi-Fi suggestions and scan watchers after phone reboot or app update.
 */
class DashAutoConnectReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DashAutoReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext
        if (!DashPrefs.wirelessAutoConnect(appCtx)) return

        when (action) {
            WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION -> {
                Log.i(TAG, "OS auto-joined suggested bike Wi-Fi → starting Dash session in background")
                DashViewModel.getInstance(appCtx).autoConnectFromBackground()
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d(TAG, "Bluetooth device connected → checking for bike Wi-Fi hotspot")
                FastReconnectManager.registerPassiveScanWatcher(appCtx)
                FastReconnectManager.checkScanAndAutoConnect(appCtx)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot/update ($action) → syncing Wireless Auto-Connect suggestions & watcher")
                FastReconnectManager.sync(appCtx)
                FastReconnectManager.checkScanAndAutoConnect(appCtx)
            }
        }
    }
}
