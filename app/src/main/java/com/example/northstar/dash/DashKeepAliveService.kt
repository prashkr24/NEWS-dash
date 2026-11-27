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
        private const val EXTRA_FROM_BG = "from_background"

        @Volatile var isRunning = false
            private set
        @Volatile private var activeInstance: DashKeepAliveService? = null
        @Volatile private var currentTitle = "WayDash — linking to Tripper Dash…"
        @Volatile private var currentText = "Connecting Wi-Fi & authenticating Dash"

        // Process-level fallback locks: held directly whenever a Dash session is active so that
        // even if Android 12–16 blocks startForegroundService() from a background callback (before
        // the rider grants battery-optimization exemption), CPU, low-latency Wi-Fi, and screen-off
        // UDP broadcast reception (MulticastLock / APF bypass for 192.168.1.255:2002) still work!
        private val fallbackLockSync = Any()
        private var fallbackWakeLock: PowerManager.WakeLock? = null
        private var fallbackWifiLock: WifiManager.WifiLock? = null
        private var fallbackMulticastLock: WifiManager.MulticastLock? = null

        fun acquireSessionLocks(context: Context) {
            val appCtx = context.applicationContext
            synchronized(fallbackLockSync) {
                runCatching {
                    if (fallbackWakeLock?.isHeld != true) {
                        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
                        fallbackWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "northstar:dash-session").apply {
                            setReferenceCounted(false)
                            acquire(3 * 60 * 60 * 1000L)
                        }
                    }
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    if (fallbackWifiLock?.isHeld != true) {
                        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            @Suppress("DEPRECATION")
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        fallbackWifiLock = wm.createWifiLock(lockMode, "northstar:dash-session-wifi").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                    }
                    if (fallbackMulticastLock?.isHeld != true) {
                        // CRITICAL for screen-off pocket connection: without MulticastLock, Android's
                        // APF hardware packet filter drops incoming 192.168.1.255:2002 UDP broadcast
                        // auth & frame-ack packets from the Tripper Dash whenever the screen is OFF!
                        fallbackMulticastLock = wm.createMulticastLock("northstar:dash-session-bcast").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                    }
                }
            }
        }

        fun releaseSessionLocks() {
            synchronized(fallbackLockSync) {
                runCatching { if (fallbackWakeLock?.isHeld == true) fallbackWakeLock?.release() }
                runCatching { if (fallbackWifiLock?.isHeld == true) fallbackWifiLock?.release() }
                runCatching { if (fallbackMulticastLock?.isHeld == true) fallbackMulticastLock?.release() }
                fallbackWakeLock = null
                fallbackWifiLock = null
                fallbackMulticastLock = null
            }
        }

        fun start(context: Context, fromBackground: Boolean = false): Boolean {
            val appCtx = context.applicationContext
            acquireSessionLocks(appCtx)
            currentTitle = "WayDash — linking to Tripper Dash…"
            currentText = "Connecting Wi-Fi & authenticating Dash"
            val i = Intent(appCtx, DashKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FROM_BG, fromBackground)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appCtx.startForegroundService(i)
                else appCtx.startService(i)
                true
            }.getOrElse { e ->
                Log.w(TAG, "Could not start DashKeepAliveService from current context (fallback locks active): ${e.message}")
                postFallbackNotification(appCtx)
                false
            }
        }

        fun updateStatus(context: Context, title: String, text: String) {
            currentTitle = title
            currentText = text
            val svc = activeInstance
            if (svc != null) {
                runCatching {
                    val nm = svc.getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotificationStatic(svc))
                }
            } else {
                postFallbackNotification(context.applicationContext)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            releaseSessionLocks()
            runCatching {
                if (isRunning) {
                    appCtx.stopService(Intent(appCtx, DashKeepAliveService::class.java))
                }
            }
            runCatching {
                appCtx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            }
        }

        private fun ensureChannelStatic(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Dash streaming", NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Keeps the map streaming to the Tripper Dash" }
                )
            }
        }

        private fun postFallbackNotification(context: Context) {
            runCatching {
                ensureChannelStatic(context)
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.notify(NOTIF_ID, buildNotificationStatic(context))
            }
        }

        private fun buildNotificationStatic(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val disconnectIntent = PendingIntent.getService(
                context, 1,
                Intent(context, DashKeepAliveService::class.java).setAction(ACTION_USER_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(context, CHANNEL_ID)
            else
                @Suppress("DEPRECATION") Notification.Builder(context)

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

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_USER_DISCONNECT -> {
                com.example.northstar.dash.FastReconnectManager.notifyManualDisconnect()
                com.example.northstar.viewmodel.DashViewModel.getInstance(applicationContext).disconnect()
                isRunning = false
                activeInstance = null
                releaseSessionLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                isRunning = false
                activeInstance = null
                releaseSessionLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                activeInstance = this
                val fromBg = intent?.getBooleanExtra(EXTRA_FROM_BG, false) == true
                startForegroundLocks(fromBg)
            }
        }
        // START_STICKY: if the OS kills us under memory pressure, restart so the
        // ride keeps streaming.
        return START_STICKY
    }

    private fun startForegroundLocks(fromBackground: Boolean) {
        ensureChannelStatic(this)
        val notif = buildNotificationStatic(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                val canUseLocationType = hasLocationPermission() &&
                    (!fromBackground || com.example.northstar.util.DeviceReadiness.hasBackgroundLocationPermission(this))
                if (canUseLocationType) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                try {
                    startForeground(NOTIF_ID, notif, type)
                } catch (sec: SecurityException) {
                    // Android 14+ rejects FOREGROUND_SERVICE_TYPE_LOCATION when started from a
                    // closed-app background callback if the user only granted "While using the app".
                    // Fall back to CONNECTED_DEVICE so the background connection still succeeds.
                    Log.w(TAG, "FGS with LOCATION rejected (${sec.message}); falling back to CONNECTED_DEVICE")
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isRunning = true
            acquireLocks()
            Log.i(TAG, "Foreground service up — wake+wifi+multicast locks held (fromBg=$fromBackground)")
        } catch (e: Exception) {
            isRunning = false
            Log.e(TAG, "Foreground service start failed — fallback session locks remain active", e)
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
        multicastLock = runCatching {
            wm.createMulticastLock("northstar:dash-bcast").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    override fun onDestroy() {
        isRunning = false
        if (activeInstance === this) activeInstance = null
        releaseLocks()
        Log.i(TAG, "Foreground service stopped — locks released")
        super.onDestroy()
    }
}
