package com.example.northstar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.data.MapPrefs
import com.example.northstar.dash.map.TileSource
import com.example.northstar.ui.theme.*

/**
 * Compact two-segment dash-map switch (Google | Mappls) for placing inline on the Route
 * screen, right above Start navigation. Writes the same MapPrefs the Settings screen and
 * TileProvider use, so the choice is shared everywhere and applies on the next dash frame.
 */
@Composable
fun MapProviderQuickToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(MapPrefs.tileSource(context)) }

    Column(modifier) {
        Text(
            "DASH MAP",
            color = TextLo, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surf1)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TileSource.entries.filter { it != TileSource.MAPPLS }.forEach { src ->
                val active = src == selected
                Text(
                    text = src.displayName,
                    color = if (active) Bg0 else TextMid,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    fontFamily = GeistFamily,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) Gold else Surf1)
                        .clickable {
                            selected = src
                            MapPrefs.setTileSource(context, src)
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
