package com.example.northstar.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Two jobs:
 *  1. Its mere existence + being enabled grants [MediaInfoProvider] access to MediaSessionManager
 *     (Android gates getActiveSessions() behind an enabled notification listener component).
 *  2. It watches CATEGORY_CALL notifications (the dialer's incoming/active-call card) and pushes the
 *     caller name to [CallInfoProvider] — that's how the dash shows "Amma calling" without any
 *     telephony permission, exactly mirroring what the dash put on the wire (TLV 05 22).
 */
class NorthstarNotificationListener : NotificationListenerService() {

    private companion object {
        const val TAG = "NsNotifListener"
        // Common Indian-market messaging apps whose notifications become dash alerts.
        // Category-based detection covers the rest.
        val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "org.thoughtcrime.securesms",   // Signal
            "com.instagram.android",
        )
    }

    // These two confirm in the PERSISTENT diagnostics whether the listener is actually bound — the
    // crux of the "reinstall unbinds the listener" bug. If 'listener connected' never appears in a
    // remote pull, the service is dead (toggle notification access off/on, or the requestRebind in
    // NorthstarApplication should have re-bound it).
    override fun onListenerConnected() {
        com.example.northstar.data.RideDiagnostics.log("media", "notification listener CONNECTED")
    }

    override fun onListenerDisconnected() {
        com.example.northstar.data.RideDiagnostics.log("media", "notification listener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (n.category != Notification.CATEGORY_CALL) {
            maybePostMessageAlert(sbn, n)
            return
        }
        // CallStyle's EXTRA_CALL_TYPE (API 31+): 1=INCOMING, 2=ONGOING, 3=OUTGOING, 4=SCREENING.
        // INCOMING → ringing (overlay + answer/reject). ONGOING/OUTGOING → active (end only, no
        // overlay). Pre-31 we can't tell, so assume incoming.
        val type = if (android.os.Build.VERSION.SDK_INT >= 31) n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 1
        val incoming = type == 1
        val active = type == 2 || type == 3
        if (!incoming && !active) { CallInfoProvider.update(null); return }   // screening / unknown

        lastCallKey = sbn.key
        val (answer, decline) = extractActions(n, incoming)
        val caller = callerName(n).ifBlank { if (incoming) "Call" else "On call" }
        com.example.northstar.data.RideDiagnostics.log("media",
            "${if (incoming) "INCOMING" else "ACTIVE"} call: '$caller' (answerAct=${answer != null} declineAct=${decline != null})")
        CallInfoProvider.update(IncomingCall(caller, incoming, answer, decline))
        Log.d(TAG, "call($incoming): $caller")
    }

    /**
     * Pull the dialer's Answer / Decline (or Hang-up) PendingIntents off the notification's action
     * buttons — firing these is the reliable way to control the call. Match by button title; for a
     * 2-button incoming call with no title match, fall back to [decline, answer] order.
     */
    /**
     * Messaging notifications → dash alert card (opt-in via AlertPrefs). Trusts either
     * the msg category or a known messaging package; skips group summaries and silent
     * re-posts. Only sender + a short snippet ever reach the dash.
     */
    private fun maybePostMessageAlert(sbn: StatusBarNotification, n: Notification) {
        if (!com.example.northstar.data.AlertPrefs.messagesOnDash(applicationContext)) return
        val isMsgCategory = n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_EMAIL
        val isMsgApp = sbn.packageName in MESSAGING_PACKAGES
        if (!isMsgCategory && !isMsgApp) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        com.example.northstar.dash.DashAlertBus.post(
            title.ifBlank { appLabel(sbn.packageName) },
            text,
        )
        Log.d(TAG, "message alert: $title")
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("Message")

    private fun extractActions(n: Notification, incoming: Boolean): Pair<android.app.PendingIntent?, android.app.PendingIntent?> {
        var answer: android.app.PendingIntent? = null
        var decline: android.app.PendingIntent? = null
        val acts = n.actions
        acts?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (t.contains("answer") || t.contains("accept")) -> answer = a.actionIntent
                decline == null && (t.contains("decline") || t.contains("reject") || t.contains("dismiss") ||
                    t.contains("hang") || t.contains("end") || t.contains("ignore")) -> decline = a.actionIntent
            }
        }
        if (incoming && answer == null && decline == null && acts != null && acts.size == 2) {
            decline = acts[0].actionIntent; answer = acts[1].actionIntent
        }
        return answer to decline
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Android sometimes strips the category on removal, which left the dash call
        // card stuck after hang-up. Match by the notification KEY we saw at post time
        // (works regardless of category), with the category check kept as a fallback.
        if (sbn?.key == lastCallKey || sbn?.notification?.category == Notification.CATEGORY_CALL) {
            lastCallKey = null
            CallInfoProvider.update(null)
        }
    }
    @Volatile private var lastCallKey: String? = null

    /** Caller name = the call notification's title; fall back to its text. */
    private fun callerName(n: Notification): String {
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (!title.isNullOrBlank()) return title
        return e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }

}
