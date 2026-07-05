package com.example.northstar.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture buffer for everything the dash sends us on UDP/2002. DashSession feeds each
 * inbound TLV here; the Discovery screen renders + exports it. This is the tool for
 * mapping the Guerrilla's firmware-specific joystick codes (and spotting any telemetry
 * the Himalayan reverse-engineering never documented).
 *
 * Purely in-memory + a bounded ring buffer; recording is OFF by default so we never
 * capture unless the rider explicitly starts it.
 */
object DiscoveryLog {

    enum class Kind { JOYSTICK, TELEMETRY_ENC, TELEMETRY_PLAIN, AUTH, FRAME_ACK, UNKNOWN }

    data class Packet(
        val timeMs: Long,
        val kind: Kind,
        val type: Int,
        val sub: Int,
        val sizeBytes: Int,
        val rawHex: String,
        /** Decrypted hex for encrypted telemetry (0x0F), else null. */
        val decodedHex: String? = null,
    ) {
        val clock: String get() = TIME_FMT.format(Date(timeMs))
        /** The single trailing byte of a joystick event — the button code we want. */
        val buttonCode: String?
            get() = if (kind == Kind.JOYSTICK && rawHex.length >= 2)
                rawHex.trim().takeLast(2).uppercase() else null
    }

    private const val MAX = 500  // ring-buffer cap; oldest dropped past this

    private val _packets = MutableStateFlow<List<Packet>>(emptyList())
    val packets: StateFlow<List<Packet>> = _packets

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    fun setRecording(on: Boolean) { _recording.value = on }
    fun toggleRecording() { _recording.value = !_recording.value }

    fun clear() { _packets.value = emptyList() }

    /** Called from DashSession for every inbound TLV. No-op unless recording. */
    fun record(
        kind: Kind, type: Int, sub: Int, sizeBytes: Int,
        rawHex: String, decodedHex: String? = null,
    ) {
        if (!_recording.value) return
        val p = Packet(System.currentTimeMillis(), kind, type, sub, sizeBytes, rawHex, decodedHex)
        val cur = _packets.value
        _packets.value = (if (cur.size >= MAX) cur.drop(cur.size - MAX + 1) else cur) + p
    }

    /** Distinct joystick button codes seen so far, in first-seen order (the mapping goal). */
    fun distinctButtonCodes(): List<String> =
        _packets.value.filter { it.kind == Kind.JOYSTICK }.mapNotNull { it.buttonCode }.distinct()

    /** Full capture as shareable text (for saving / pasting back for firmware mapping). */
    fun exportText(): String = buildString {
        appendLine("WayDash dash-link capture")
        appendLine("Generated: ${TIME_FMT.format(Date())}")
        appendLine("Packets: ${_packets.value.size}")
        val codes = distinctButtonCodes()
        if (codes.isNotEmpty()) appendLine("Distinct joystick codes: ${codes.joinToString(", ")}")
        appendLine("---- time  kind  type/sub  size  raw  (decoded) ----")
        _packets.value.forEach { p ->
            append("${p.clock}  ${p.kind}  ")
            append("0x%02X/0x%02X  ".format(p.type, p.sub))
            append("${p.sizeBytes}B  ${p.rawHex}")
            p.decodedHex?.let { append("  dec=$it") }
            appendLine()
        }
    }

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
