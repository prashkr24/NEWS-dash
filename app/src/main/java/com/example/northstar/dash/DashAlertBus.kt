package com.example.northstar.dash

/**
 * Transient message alerts shown on the dash video frame, Messenger-bubble style.
 *
 * v2 after a week of road feedback ("sometimes disappears instantly, sometimes lingers"):
 *  - QUEUE instead of single slot: a burst of messages no longer stomps the visible one;
 *    each alert is guaranteed its full display window, shown one after another.
 *  - Expiry is measured from when the alert FIRST APPEARED on a frame (shownAt), not from
 *    when it was posted — so an alert that queued behind another still gets its full time.
 *  - A lightweight unread badge counter for the corner bubble (posts within BADGE_WINDOW).
 */
object DashAlertBus {
    private const val SHOW_MS = 7_000L        // each alert's on-screen time
    private const val QUEUE_MAX = 5
    private const val BADGE_WINDOW_MS = 10 * 60_000L

    data class Alert(
        val title: String,
        val line: String,
        val postedMs: Long,
        var shownAtMs: Long = 0L,             // set on first frame it appears
    )

    private val queue = ArrayDeque<Alert>()
    @Volatile private var lastKey = ""
    @Volatile private var lastKeyMs = 0L
    private val badgeTimes = ArrayDeque<Long>()

    @Synchronized
    fun post(title: String, line: String) {
        val now = System.currentTimeMillis()
        val key = "$title|$line"
        if (key == lastKey && now - lastKeyMs < 3_000) return   // duplicate burst
        lastKey = key; lastKeyMs = now
        if (queue.size >= QUEUE_MAX) queue.removeFirst()
        queue.addLast(Alert(title.take(24), line.take(48), now))
        badgeTimes.addLast(now)
        while (badgeTimes.isNotEmpty() && now - badgeTimes.first() > BADGE_WINDOW_MS) {
            badgeTimes.removeFirst()
        }
    }

    /** Current alert to draw, honouring each alert's full display window. */
    @Synchronized
    fun active(): Alert? {
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty()) {
            val head = queue.first()
            if (head.shownAtMs == 0L) head.shownAtMs = now
            if (now - head.shownAtMs <= SHOW_MS) return head
            queue.removeFirst()
        }
        return null
    }

    /** Messages received in the last 10 min — the corner-bubble badge count (0 = hide). */
    @Synchronized
    fun badgeCount(): Int {
        val now = System.currentTimeMillis()
        while (badgeTimes.isNotEmpty() && now - badgeTimes.first() > BADGE_WINDOW_MS) {
            badgeTimes.removeFirst()
        }
        return badgeTimes.size
    }

    @Synchronized
    fun clear() { queue.clear(); badgeTimes.clear() }
}
