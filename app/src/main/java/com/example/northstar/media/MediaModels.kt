package com.example.northstar.media

import android.app.PendingIntent
import android.graphics.Bitmap

/** Now-playing snapshot read from the active MediaSession. [art] may be null. */
data class NowPlaying(
    val title: String,
    val album: String,
    val artist: String,
    val art: Bitmap? = null,
)

/**
 * An incoming phone or VoIP (WhatsApp, Telegram, etc.) call surfaced from notifications.
 * Displayed on the dash as a visual alert banner while [incoming] is true.
 */
data class IncomingCall(
    val caller: String,
    val incoming: Boolean = true,
    val answerIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
    /** Empty for cellular dialer calls; app/mode label (e.g. "WhatsApp", "WhatsApp Video") for VoIP. */
    val source: String = "",
    val displayOnly: Boolean = true,
)
