package com.example.northstar.media

import android.app.Notification
import android.app.Person
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.os.BundleCompat

/**
 * Two jobs:
 *  1. Its mere existence + being enabled grants [MediaInfoProvider] access to MediaSessionManager
 *     (Android gates getActiveSessions() behind an enabled notification listener component).
 *  2. It watches incoming cellular & VoIP (WhatsApp, WhatsApp Business, Telegram, Signal, etc.)
 *     call notifications and pushes the caller name + source to [CallInfoProvider] so the dash
 *     can display an incoming-call alert banner without telephony permissions.
 */
class NorthstarNotificationListener : NotificationListenerService() {

    internal enum class CallState { INCOMING, ONGOING_OR_OUTGOING, ENDED_OR_MISSED }

    internal companion object {
        const val TAG = "NsNotifListener"

        val VOIP_CALL_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp",
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "com.microsoft.teams" to "Teams",
            "com.skype.raider" to "Skype",
            "com.google.android.apps.tachyon" to "Meet",
            "com.discord" to "Discord",
        )

        val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "org.thoughtcrime.securesms",
            "com.instagram.android",
        )

        private val CALL_TIMER_REGEX = Regex("\\b\\d{1,2}:\\d{2}\\b")

        /**
         * Classify a call notification's lifecycle state even when the app uses a custom
         * notification layout instead of Android 12's [Notification.CallStyle] (where
         * [Notification.EXTRA_CALL_TYPE] is absent / `0` — the exact reason WhatsApp calls
         * were previously dropped on API 31+).
         */
        fun classifyCallState(
            callTypeExtra: Int,
            combinedTextLower: String,
            actionTitlesLower: List<String>,
            hasFullScreenIntent: Boolean,
            flags: Int,
            isCategoryCall: Boolean,
        ): CallState {
            // Missed-call summary cards must never trigger a ringing banner.
            if (combinedTextLower.contains("missed") && !hasFullScreenIntent) {
                return CallState.ENDED_OR_MISSED
            }

            // Explicit Android 12+ CallStyle type when present:
            // 1 = INCOMING, 2 = ONGOING, 3 = OUTGOING, 4 = SCREENING
            when (callTypeExtra) {
                1 -> return CallState.INCOMING
                2, 3 -> return CallState.ONGOING_OR_OUTGOING
                4 -> return CallState.ENDED_OR_MISSED
            }

            // Fallback heuristics for custom VoIP / WhatsApp / OEM notifications (callTypeExtra == 0):
            val hasAnswerBtn = actionTitlesLower.any {
                it.contains("answer") || it.contains("accept") || it.contains("join")
            }
            val hasDeclineOrEndBtn = actionTitlesLower.any {
                it.contains("decline") || it.contains("reject") || it.contains("hang") ||
                    it.contains("end") || it.contains("leave") || it.contains("cancel") ||
                    it.contains("disconnect") || it.contains("ignore")
            }
            val looksIncoming = combinedTextLower.contains("incoming") ||
                combinedTextLower.contains("ringing") ||
                combinedTextLower.contains("is calling")
            val looksOngoingOrOutgoing = combinedTextLower.contains("ongoing") ||
                combinedTextLower.contains("in progress") ||
                combinedTextLower.contains("tap to return") ||
                combinedTextLower.contains("calling…") ||
                combinedTextLower.contains("calling...") ||
                CALL_TIMER_REGEX.containsMatchIn(combinedTextLower)

            if (looksIncoming || hasAnswerBtn) return CallState.INCOMING
            if (looksOngoingOrOutgoing || (hasDeclineOrEndBtn && actionTitlesLower.size == 1)) {
                return CallState.ONGOING_OR_OUTGOING
            }
            if (hasFullScreenIntent || (flags and Notification.FLAG_INSISTENT) != 0) {
                return CallState.INCOMING
            }
            // 2-button ongoing call card with no matched English labels (e.g. localized Decline/Answer)
            if (isCategoryCall && actionTitlesLower.size >= 2 && (flags and Notification.FLAG_ONGOING_EVENT) != 0) {
                return CallState.INCOMING
            }
            return CallState.ENDED_OR_MISSED
        }
    }

    @Volatile private var lastCallKey: String? = null
    @Volatile private var lastCallPkg: String? = null

    override fun onListenerConnected() {
        com.example.northstar.data.RideDiagnostics.log("media", "notification listener CONNECTED")
        runCatching {
            com.example.northstar.dash.FastReconnectManager.sync(applicationContext)
            com.example.northstar.dash.FastReconnectManager.checkActiveWifiAndAutoConnect(applicationContext)
            com.example.northstar.dash.FastReconnectManager.checkScanAndAutoConnect(applicationContext)
        }
        // Pick up any call that is already ringing when the listener binds.
        runCatching {
            activeNotifications?.forEach { sbn -> handlePosted(sbn, allowMessageAlert = false) }
        }
    }

    override fun onListenerDisconnected() {
        com.example.northstar.data.RideDiagnostics.log("media", "notification listener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handlePosted(sbn, allowMessageAlert = true)
    }

    private fun handlePosted(sbn: StatusBarNotification, allowMessageAlert: Boolean) {
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val combinedText = buildCombinedText(n)
        val combinedLower = combinedText.lowercase()

        if (!isCallNotification(sbn, n, combinedLower)) {
            if (allowMessageAlert) maybePostMessageAlert(sbn, n)
            return
        }

        val callType = if (Build.VERSION.SDK_INT >= 31) {
            n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        } else 0
        val actionTitles = n.actions?.map { it.title?.toString()?.lowercase().orEmpty() }.orEmpty()

        val state = classifyCallState(
            callTypeExtra = callType,
            combinedTextLower = combinedLower,
            actionTitlesLower = actionTitles,
            hasFullScreenIntent = n.fullScreenIntent != null,
            flags = n.flags,
            isCategoryCall = n.category == Notification.CATEGORY_CALL,
        )

        if (state != CallState.INCOMING) {
            // Same call transitioned from ringing → answered/ended, so dismiss the dash banner immediately.
            if (sbn.key == lastCallKey || sbn.packageName == lastCallPkg) {
                lastCallKey = null
                lastCallPkg = null
                CallInfoProvider.update(null)
            }
            return
        }

        val baseSource = resolveCallSource(sbn.packageName)
        val isVideo = combinedLower.contains("video call") || combinedLower.contains("video ")
        val sourceLabel = when {
            baseSource.isNotBlank() && isVideo && !baseSource.contains("video", ignoreCase = true) ->
                "$baseSource Video"
            else -> baseSource
        }
        val caller = extractCallerName(n, baseSource).ifBlank {
            if (baseSource.isNotBlank()) baseSource else "Incoming call"
        }

        lastCallKey = sbn.key
        lastCallPkg = sbn.packageName
        com.example.northstar.data.RideDiagnostics.log(
            "media",
            "INCOMING ${sourceLabel.ifBlank { "phone" }} call: '$caller' (type=$callType fsi=${n.fullScreenIntent != null} acts=${actionTitles.size})",
        )
        CallInfoProvider.update(
            IncomingCall(
                caller = caller,
                incoming = true,
                source = sourceLabel,
                displayOnly = true,
            )
        )
        Log.d(TAG, "call(INCOMING, src=${sourceLabel.ifBlank { "phone" }}): $caller")
    }

    /**
     * Recognizes both standard [Notification.CATEGORY_CALL] cards and VoIP/WhatsApp call
     * notifications that use dedicated `voip`/`call` channels or full-screen ringing intents.
     */
    private fun isCallNotification(
        sbn: StatusBarNotification,
        n: Notification,
        combinedLower: String,
    ): Boolean {
        if (n.category == Notification.CATEGORY_CALL) return true
        if (sbn.packageName !in VOIP_CALL_PACKAGES) return false
        val ch = n.channelId?.lowercase().orEmpty()
        if (ch.contains("call") || ch.contains("voip")) return true
        if (n.fullScreenIntent != null) return true
        return combinedLower.contains("incoming voice call") ||
            combinedLower.contains("incoming video call") ||
            combinedLower.contains("incoming group voice call") ||
            combinedLower.contains("incoming group video call") ||
            combinedLower.contains("ongoing voice call") ||
            combinedLower.contains("ongoing video call")
    }

    /** Returns "" for cellular dialer notifications, or a clean app name ("WhatsApp", etc.) for VoIP. */
    private fun resolveCallSource(pkg: String): String {
        VOIP_CALL_PACKAGES[pkg]?.let { return it }
        val defaultDialer = runCatching {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        val p = pkg.lowercase()
        val isSystemDialer = pkg == defaultDialer ||
            p.contains("dialer") || p.contains("incallui") || p.contains("telecom") || p == "com.android.phone"
        return if (isSystemDialer) "" else appLabel(pkg)
    }

    private fun buildCombinedText(n: Notification): String {
        val e = n.extras ?: return ""
        return listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            n.tickerText?.toString(),
        ).joinToString(" ")
    }

    /**
     * Extracts the human caller name across CallStyle (`EXTRA_CALL_PERSON`) and custom VoIP
     * notifications (skipping generic app-name titles like `"WhatsApp"` when a real name exists).
     */
    private fun extractCallerName(n: Notification, sourceApp: String): String {
        val e = n.extras ?: return ""
        fun isGenericAppTitle(s: String): Boolean {
            val clean = s.trim()
            if (clean.isEmpty()) return true
            if (sourceApp.isNotBlank() && clean.equals(sourceApp, ignoreCase = true)) return true
            return clean.equals("WhatsApp", ignoreCase = true) ||
                clean.equals("WhatsApp Business", ignoreCase = true)
        }

        if (Build.VERSION.SDK_INT >= 31) {
            val person = runCatching {
                BundleCompat.getParcelable(e, Notification.EXTRA_CALL_PERSON, Person::class.java)
            }.getOrNull()
            val pName = person?.name?.toString()?.trim().orEmpty()
            if (pName.isNotBlank() && !isGenericAppTitle(pName)) return pName
        }

        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isNotBlank() && !isGenericAppTitle(title)) return title

        val bigTitle = e.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim().orEmpty()
        if (bigTitle.isNotBlank() && !isGenericAppTitle(bigTitle)) return bigTitle

        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) {
            // Strip "Incoming voice call from X" if formatted that way; otherwise avoid returning
            // raw status text like "Incoming voice call" when title already held the app name.
            val fromIdx = text.lowercase().indexOf(" from ")
            if (fromIdx >= 0 && fromIdx + 6 < text.length) {
                return text.substring(fromIdx + 6).trim()
            }
            val lower = text.lowercase()
            if (!lower.startsWith("incoming") && !lower.startsWith("ringing") && !lower.startsWith("whatsapp")) {
                return text
            }
        }
        return title
    }

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
    }.getOrDefault("Call")

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.key == lastCallKey ||
            (lastCallKey == null && sbn.notification?.category == Notification.CATEGORY_CALL)
        ) {
            lastCallKey = null
            lastCallPkg = null
            CallInfoProvider.update(null)
        }
    }
}
