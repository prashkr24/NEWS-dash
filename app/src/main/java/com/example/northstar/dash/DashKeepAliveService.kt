package com.example.northstar.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.northstar.MainActivity
import com.example.northstar.R

/**
 * Foreground service that keeps Northstar streaming to the dash while the
 * phone screen is OFF — the whole reason this app exists.
 *
 * It does NOT own the streaming pipeline (that stays in DashViewModel, which
 * survives Activity onStop). Its job is purely to stop Android from freezing
 * the process:
 *   • PARTIAL_WAKE_LOCK  — CPU keeps running the 4 Hz encoder/RTP loop with
 *     the screen off (otherwise Doze suspends the coroutine → dash times out).
 *   • WifiLock (LOW_LATENCY / HIGH_PERF) — stops WiFi power-save from tearing
 *     down the WifiNetworkSpecifier link to the Tripper hotspot.
 *   • Ongoing notification — required for a foreground service; also lets the
 *     rider re-open the app.
 */
class DashKeepAliveService : Service() {
    companion object {
        private const val TAG          = "DashKeepAlive"
        private const val CHANNEL_ID   = "northstar_dash"
        private const val NOTIF_ID     = 4701
        const val ACTION_START = "com.example.northstar.DASH_START"
        const val ACTION_STOP  = "com.example.northstar.DASH_STOP"
        const val ACTION_USER_DISCONNECT = "com.example.northstar.DASH_USER_DISCONNECT"

        @Volatile var isRunning = false
            private set
        @Volatile private var activeInstance: DashKeepAliveService? = null
        @Volatile private var currentTitle = "WayDash — linking to Tripper Dash…"
        @Volatile private var currentText = "Connecting Wi-Fi & authenticating Dash"

        fun start(context: Context) {
            currentTitle = "WayDash — linking to Tripper Dash…"
            currentText = "Connecting Wi-Fi & authenticating Dash"
            val i = Intent(context, DashKeepAliveService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }.onFailure { e ->
                Log.w(TAG, "Could not start DashKeepAliveService from current context: ${e.message}")
            }
        }

        fun updateStatus(title: String, text: String) {
            currentTitle = title
            currentText = text
            val svc = activeInstance ?: return
            runCatching {
                val nm = svc.getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, svc.buildNotification())
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            runCatching {
                context.stopService(Intent(context, DashKeepAliveService::class.java))
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_USER_DISCONNECT -> {
                com.example.northstar.dash.FastReconnectManager.notifyManualDisconnect()
                com.example.northstar.viewmodel.DashViewModel.getInstance(applicationContext).disconnect()
                isRunning = false
                activeInstance = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                isRunning = false
                activeInstance = null
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                activeInstance = this
                startForegroundLocks()
            }
        }
        // START_STICKY: if the OS kills us under memory pressure, restart so the
        // ride keeps streaming.
        return START_STICKY
    }

    private fun startForegroundLocks() {
        createChannel()
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                try {
                    startForeground(NOTIF_ID, notif, type)
                } catch (sec: SecurityException) {
                    // Android 14+ may reject FOREGROUND_SERVICE_TYPE_LOCATION when started from
                    // a closed-app background receiver if the user only granted "While using the app".
                    // Fall back to CONNECTED_DEVICE so the background connection still succeeds.
                    Log.w(TAG, "FGS with LOCATION rejected from background (${sec.message}); falling back to CONNECTED_DEVICE")
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isRunning = true
            acquireLocks()
            Log.i(TAG, "Foreground service up — wake+wifi locks held")
        } catch (e: Exception) {
            isRunning = false
            Log.e(TAG, "Foreground service start failed — continuing without locks", e)
            com.example.northstar.util.Telemetry.recordNonFatal(e, "DashKeepAliveService.startForegroundLocks")
            stopSelf()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun acquireLocks() {
        releaseLocks()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "northstar:dash").apply {
            setReferenceCounted(false)
            // Bounded 3-hour max lease per session so a lock can never leak forever
            acquire(3 * 60 * 60 * 1000L)
        }

        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockMode, "northstar:dash").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        isRunning = false
        if (activeInstance === this) activeInstance = null
        releaseLocks()
        Log.i(TAG, "Foreground service stopped — locks released")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Dash streaming", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the map streaming to the Tripper Dash" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DashKeepAliveService::class.java).setAction(ACTION_USER_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val disconnectAction = Notification.Action.Builder(
            null, "Disconnect", disconnectIntent
        ).build()

        return builder
            .setContentTitle(currentTitle)
            .setContentText(currentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(disconnectAction)
            .build()
    }
}
