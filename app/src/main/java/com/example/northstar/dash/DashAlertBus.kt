package com.example.northstar.dash

/**
 * Transient alert shown on the dash video frame (e.g. an incoming message: sender +
 * snippet). The notification listener posts here; DashViewModel.redrawFrame reads the
 * current alert while it's fresh and hands it to MapRenderer to draw as a card.
 *
 * Single-slot by design: a newer alert replaces the current one (glanceable > complete),
 * and everything expires after [SHOW_MS] so the map is never permanently obscured.
 */
object DashAlertBus {
    private const val SHOW_MS = 8_000L

    data class Alert(val title: String, val line: String, val untilMs: Long)

    @Volatile private var current: Alert? = null
    @Volatile private var lastKey: String = ""
    @Volatile private var lastKeyMs: Long = 0L

    fun post(title: String, line: String) {
        val now = System.currentTimeMillis()
        // De-dupe bursts (group summaries, double-posted notifications).
        val key = "$title|$line"
        if (key == lastKey && now - lastKeyMs < 3_000) return
        lastKey = key; lastKeyMs = now
        current = Alert(title.take(28), line.take(60), now + SHOW_MS)
    }

    /** The active alert, or null once expired. */
    fun active(): Alert? {
        val a = current ?: return null
        return if (System.currentTimeMillis() <= a.untilMs) a else { current = null; null }
    }

    fun clear() { current = null }
}
