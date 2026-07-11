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
import com.example.northstar.data.MapPrefs
import com.example.northstar.dash.map.TileSource
import com.example.northstar.ui.theme.*

/**
 * "Dash map" settings section: pick Google Maps or Mappls tiles, and enter the Mappls
 * REST key when Mappls is selected. Insert into SettingsScreen's Column:
 *
 *     SectionLabel("DASH MAP")
 *     MapProviderSection(onSourceChanged = { vm.onTileSourceChanged(it) })
 *
 * Styling mirrors the existing SettingRow/SectionLabel look (Surf2/Line/Gold tokens).
 */
@Composable
fun MapProviderSection(onSourceChanged: (TileSource) -> Unit = {}) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(MapPrefs.tileSource(context)) }
    var mapplsKey by remember { mutableStateOf(MapPrefs.mapplsKey(context) ?: "") }
    var showKeyDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surf1)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        TileSource.entries.filter { it != TileSource.MAPPLS }.forEach { src ->
            val active = src == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Surf2 else Surf1)
                    .border(1.dp, if (active) Gold else Line, RoundedCornerShape(10.dp))
                    .clickable {
                        selected = src
                        MapPrefs.setTileSource(context, src)
                        if (src.requiresApiKey && MapPrefs.mapplsKey(context) == null) {
                            showKeyDialog = true // ask for the key right away
                        } else {
                            onSourceChanged(MapPrefs.effectiveTileSource(context))
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(src.displayName, color = TextHi, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(
                        when (src) {
                            TileSource.GOOGLE -> "Best POI detail · no key needed"
                            TileSource.MAPPLS -> if (mapplsKey.isBlank())
                                "Indian road data · Beta · tap to add REST key"
                            else "Indian road data · Beta · daily free limit applies"
                            TileSource.MAPLIBRE_3D ->
                                "Raised 3D buildings · Experimental · OSM data, no key"
                        },
                        color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (active) Text("ACTIVE", color = Gold, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (selected == TileSource.MAPPLS) {
            Text(
                if (mapplsKey.isBlank()) "No key yet — falling back to Google until one is added."
                else "Change REST key",
                color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showKeyDialog = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }

    if (showKeyDialog) {
        var draft by remember { mutableStateOf(mapplsKey) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = Surf1,
            title = { Text("Mappls REST key", color = TextHi, fontSize = 16.sp, fontFamily = GeistFamily) },
            text = {
                Column {
                    Text(
                        "Create a free key at apis.mappls.com (sign up → dashboard → REST API key), then paste it here. The key stays on this phone.",
                        color = TextMid, fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        singleLine = true, placeholder = { Text("REST API key", color = TextLo) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    MapPrefs.setMapplsKey(context, draft)
                    mapplsKey = draft.trim()
                    showKeyDialog = false
                    onSourceChanged(MapPrefs.effectiveTileSource(context))
                }) { Text("Save", color = Gold) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel", color = TextLo) }
            },
        )
    }
}
