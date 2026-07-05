package com.example.northstar.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.northstar.data.DiscoveryLog
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import java.io.File

/**
 * "Signals" — a live view of everything the dash sends us on UDP/2002, so the rider can
 * map their firmware's joystick codes and inspect telemetry. Recording is off by default;
 * captures export to a shareable text file.
 */
@Composable
fun DiscoveryScreen() {
    val context = LocalContext.current
    val packets by DiscoveryLog.packets.collectAsState()
    val recording by DiscoveryLog.recording.collectAsState()
    val fields by DiscoveryLog.fields.collectAsState()
    val lastButton by DiscoveryLog.lastButton.collectAsState()
    val lastButtonMs by DiscoveryLog.lastButtonMs.collectAsState()
    var filter by remember { mutableStateOf("All") }
    var mode by remember { mutableStateOf("Live") }  // Live monitor vs raw Log

    val filters = listOf("All", "Joystick", "Telemetry", "Unknown")
    val shown = remember(packets, filter) {
        when (filter) {
            "Joystick"  -> packets.filter { it.kind == DiscoveryLog.Kind.JOYSTICK }
            "Telemetry" -> packets.filter {
                it.kind == DiscoveryLog.Kind.TELEMETRY_ENC || it.kind == DiscoveryLog.Kind.TELEMETRY_PLAIN
            }
            "Unknown"   -> packets.filter { it.kind == DiscoveryLog.Kind.UNKNOWN }
            else         -> packets
        }.asReversed()  // newest first
    }
    val codes = remember(packets) { DiscoveryLog.distinctButtonCodes() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 110.dp),
    ) {
        ScreenHeader(
            eyebrow = "Dash link",
            title = "Signals",
            hint = "Record what the dash sends on connect — map your joystick buttons and inspect telemetry.",
        )

        // Record / clear / export controls
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NorthstarBtn(
                if (recording) "Recording…" else "Record",
                onClick = { DiscoveryLog.toggleRecording() },
                icon = if (recording) NorthstarIcons.Power else NorthstarIcons.Bell,
                variant = if (recording) BtnVariant.Danger else BtnVariant.Primary,
                size = BtnSize.Sm,
                modifier = Modifier.weight(1f),
            )
            NorthstarBtn(
                "Clear", onClick = { DiscoveryLog.clear() },
                variant = BtnVariant.Secondary, size = BtnSize.Sm,
                modifier = Modifier.weight(1f),
            )
            NorthstarBtn(
                "Export", onClick = { exportCapture(context) },
                icon = NorthstarIcons.Share, variant = BtnVariant.Secondary, size = BtnSize.Sm,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Live monitor vs raw Log toggle
        NorthstarSegmented(
            options = listOf("Live", "Log"),
            selected = mode,
            onSelect = { mode = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        if (mode == "Live") {
            // ── Big last-joystick indicator: press a direction, read its code, label it. ──
            val fresh = lastButton != null &&
                (System.currentTimeMillis() - lastButtonMs) < 4000
            NorthstarCard(
                glow = fresh, padding = 16.dp, modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Last joystick press", color = TextMid, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lastButton?.let { "0x$it" } ?: "—",
                        color = if (fresh) BravaBright else TextHi,
                        fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily,
                        letterSpacing = (-1).sp,
                    )
                    Spacer(Modifier.width(14.dp))
                    if (codes.isNotEmpty()) {
                        Column {
                            Text("seen so far", color = TextLo, fontSize = 10.sp, fontFamily = GeistMonoFamily)
                            Text(codes.joinToString(" ") { "0x$it" },
                                color = TextMid, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                fontFamily = GeistMonoFamily)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Press ONE direction, note its code, repeat. Tell me which code = which direction.",
                    color = TextLo, fontSize = 11.5.sp, lineHeight = 15.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Telemetry fields", color = TextHi, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = GeistFamily,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))

            val rows = remember(fields) {
                fields.values.sortedBy { it.key }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (recording) "Waiting for telemetry…" else "Tap Record while connected.",
                        color = TextLo, fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(rows, key = { it.key }) { fld -> FieldRow(fld) }
                }
            }
        } else {
            // ── Raw log mode: filter chips + packet list ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    val active = f == filter
                    Text(
                        f, color = if (active) OnBrava else TextMid,
                        fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (active) Brava else Surf1)
                            .clickable { filter = f }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (packets.isEmpty()) "No packets captured yet" else "Nothing matches this filter",
                        color = TextLo, fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(shown) { p -> PacketRow(p) }
                }
            }
        }
    }
}

/** One telemetry field in the live monitor: key, hex, decimal, and a change counter. */
@Composable
private fun FieldRow(f: DiscoveryLog.FieldState) {
    val active = (System.currentTimeMillis() - f.lastMs) < 1500 && f.changes > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) BravaTint else Surf1)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text("0C/${f.key.substringAfter('/')}", color = TextMid, fontSize = 12.sp,
            fontFamily = GeistMonoFamily, modifier = Modifier.width(56.dp))
        Text("0x${f.hex}", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily, modifier = Modifier.width(90.dp))
        Text("${f.decimal}", color = BravaBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily, modifier = Modifier.weight(1f))
        if (f.changes > 0) {
            Text("Δ${f.changes}", color = if (active) BravaBright else TextLo, fontSize = 11.sp,
                fontFamily = GeistMonoFamily)
        }
    }
}

@Composable
private fun PacketRow(p: DiscoveryLog.Packet) {
    val (tone, tint) = when (p.kind) {
        DiscoveryLog.Kind.JOYSTICK        -> BravaBright to BravaTint
        DiscoveryLog.Kind.TELEMETRY_ENC   -> Amber to Amber.copy(alpha = 0.14f)
        DiscoveryLog.Kind.TELEMETRY_PLAIN -> OkGreen to OkGreen.copy(alpha = 0.14f)
        DiscoveryLog.Kind.UNKNOWN         -> Alert to Alert.copy(alpha = 0.12f)
        else                              -> TextMid to Surf2
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surf1)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.kind.name.replace('_', ' '), color = tone, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily,
                modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(tint)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("0x%02X/0x%02X".format(p.type, p.sub), color = TextMid, fontSize = 11.sp, fontFamily = GeistMonoFamily)
            Spacer(Modifier.width(8.dp))
            Text(p.clock, color = TextLo, fontSize = 10.5.sp, fontFamily = GeistMonoFamily)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.rawHex, color = TextHi, fontSize = 12.sp, fontFamily = GeistMonoFamily)
        p.decodedHex?.let {
            Spacer(Modifier.height(3.dp))
            Text("dec: $it", color = OkGreen, fontSize = 11.5.sp, fontFamily = GeistMonoFamily)
        }
    }
}

private fun exportCapture(context: android.content.Context) {
    runCatching {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "waydash-capture-${System.currentTimeMillis()}.txt")
        file.writeText(DiscoveryLog.exportText())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export capture"))
    }
}
