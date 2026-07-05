package com.example.northstar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.data.RoutePrefs
import com.example.northstar.ui.theme.*

/**
 * "Routing" settings section: TomTom (traffic-aware, default) vs OSRM (no setup).
 * Mirrors MapProviderSection's look. Applies to the next route calculation.
 */
@Composable
fun RoutingEngineSection() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(RoutePrefs.engine(context)) }
    var showKeyDialog by remember { mutableStateOf(false) }

    data class EngineOpt(val id: String, val title: String, val sub: String)
    val options = listOf(
        EngineOpt(
            RoutePrefs.ENGINE_TOMTOM, "TomTom · Beta",
            "Live-traffic ETAs & real alternatives · free key (2,500/day)",
        ),
        EngineOpt(
            RoutePrefs.ENGINE_OSRM, "OSRM",
            "Free, no setup · no traffic data (free-flow times)",
        ),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surf1)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        options.forEach { opt ->
            val active = opt.id == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Surf2 else Surf1)
                    .border(1.dp, if (active) Brava else Line, RoundedCornerShape(10.dp))
                    .clickable {
                        selected = opt.id
                        RoutePrefs.setEngine(context, opt.id)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(opt.title, color = TextHi, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(opt.sub, color = TextLo, fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
                if (active) Text("ACTIVE", color = BravaBright, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (selected == RoutePrefs.ENGINE_TOMTOM) {
            Text(
                "Change TomTom key",
                color = BravaBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showKeyDialog = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }

    if (showKeyDialog) {
        var draft by remember { mutableStateOf(RoutePrefs.tomtomKey(context) ?: "") }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = Surf1,
            title = { Text("TomTom API key", color = TextHi, fontSize = 16.sp, fontFamily = GeistFamily) },
            text = {
                Column {
                    Text(
                        "Free key from developer.tomtom.com (no card). Stays on this phone.",
                        color = TextMid, fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    RoutePrefs.setTomtomKey(context, draft)
                    showKeyDialog = false
                }) { Text("Save", color = BravaBright) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel", color = TextLo) }
            },
        )
    }
}
