package com.example.northstar.dash.nav

/**
 * India-aware elevated-road detection from road/step names. Routing APIs give us NAMES,
 * not OSM structure tags, and Indian naming is wildly inconsistent: "Silk Board Flyover",
 * "Fly Over", "Hebbal ROB" (road over-bridge), "KR Puram Br"/"Brg", "Elevated Tollway",
 * "Viaduct", "Overbridge". One naive contains("flyover") missed most of these — this
 * matcher covers the observed variants with word boundaries so e.g. "Br" can't false-
 * positive on "Brigade Road".
 *
 * RUB ("road under bridge") and "Underpass" are deliberately treated as their own hint —
 * telling a rider to "take the flyover" at an underpass would be exactly wrong.
 */
object FlyoverDetector {

    /** Hint label for an elevated/bridge step, or null when the name implies neither. */
    fun hint(vararg names: String?): String? {
        val text = names.filterNotNull().joinToString(" ").lowercase()
        if (text.isBlank()) return null
        return when {
            UNDERPASS.containsMatchIn(text) -> "Keep to the underpass"
            FLYOVER.containsMatchIn(text)   -> "Take the flyover"
            BRIDGE.containsMatchIn(text)    -> "Take the bridge"
            else -> null
        }
    }

    /** True when [hint] would produce any elevated-road guidance. */
    fun isElevated(vararg names: String?): Boolean = hint(*names) != null

    // "flyover", "fly over", "fly-over", "overbridge", "over bridge", "ROB",
    // "elevated" (Elevated Expressway/Corridor/Tollway), "viaduct"
    private val FLYOVER = Regex(
        "\\bfly[ -]?over\\b|\\bover[ -]?bridge\\b|\\brob\\b|\\belevated\\b|\\bviaduct\\b"
    )

    // "bridge", plus the Indian map abbreviations the user has seen: "brg", "br." / "br"
    private val BRIDGE = Regex(
        "\\bbridge\\b|\\bbrg\\b|\\bbr\\.?\\b"
    )

    // "RUB" (road under bridge), "underpass" — the opposite of a flyover
    private val UNDERPASS = Regex(
        "\\brub\\b|\\bunder[ -]?pass\\b|\\bunder[ -]?bridge\\b"
    )
}
