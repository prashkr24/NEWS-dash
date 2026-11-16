package com.example.northstar.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.data.BikeTrackerManager
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.AppViewModel
import com.example.northstar.viewmodel.RouteViewModel

@Composable
fun BikeHubScreen(
    appViewModel: AppViewModel,
    routeViewModel: RouteViewModel,
    onNavigateToBike: () -> Unit,
) {
    var selectedSection by remember { mutableStateOf("Tracker") }
    val garageTab by appViewModel.garageTab.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Top Segmented Bar: Tracker (JioTag 2) · Rides · Garage
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            NorthstarSegmented(
                options = listOf("Tracker", "Rides", "Garage"),
                selected = selectedSection,
                onSelect = { selectedSection = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedSection) {
                "Tracker" -> BikeTrackerTab(
                    routeViewModel = routeViewModel,
                    onNavigateToBike = onNavigateToBike,
                )
                "Rides" -> RidesScreen()
                "Garage" -> GarageScreen(
                    tab = garageTab,
                    onTabChange = { appViewModel.setGarageTab(it) },
                )
            }
        }
    }
}

@Composable
private fun BikeTrackerTab(
    routeViewModel: RouteViewModel,
    onNavigateToBike: () -> Unit,
) {
    val ctx = LocalContext.current
    val tracker = remember { BikeTrackerManager.get(ctx) }
    val trackerState by tracker.state.collectAsState()
    val parked = trackerState.parked

    val blePermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }

    fun hasBlePerms(): Boolean = blePermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (blePermissions.all { res[it] == true }) {
            tracker.startBleRadar()
        }
    }

    DisposableEffect(Unit) {
        onDispose { tracker.stopBleRadar() }
    }

    val riderOrigin = remember(parked) { routeViewModel.currentOrigin() }
    var noteInput by remember(parked?.note) { mutableStateOf(parked?.note ?: "") }
    var editingNote by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 110.dp),
    ) {
        ScreenHeader(
            eyebrow = "GUERRILLA 450 · SECURITY & LOCATION",
            title = "Bike Finder & JioTag 2",
            hint = "Automatically pins your bike's exact GPS when the Tripper Dash disconnects, plus live BLE proximity radar for your JioTag 2.",
        )

        // ── 1. Parked Bike Map & Auto-Pin Card ────────────────────────────────
        NorthstarCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("LAST PARKED LOCATION")
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (parked != null) parked.address.ifBlank { "Pinned GPS Coordinates" }
                        else "No parking pin recorded yet",
                        color = TextHi,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                    )
                    parked?.let { p ->
                        val minsAgo = ((System.currentTimeMillis() - p.timestampMs) / 60_000L).coerceAtLeast(0L)
                        val timeLabel = when {
                            minsAgo < 1 -> "Just now"
                            minsAgo < 60 -> "$minsAgo min ago"
                            minsAgo < 1440 -> "${minsAgo / 60}h ${minsAgo % 60}m ago"
                            else -> "${minsAgo / 1440}d ago"
                        }
                        val distLabel = riderOrigin?.let { (rLat, rLng) ->
                            val m = GeoPoint.distMeters(GeoPoint(rLat, rLng), GeoPoint(p.lat, p.lng))
                            if (m < 1000) " · ${m.toInt()} m away" else " · ${"%.1f".format(m / 1000.0)} km away"
                        } ?: ""
                        Text(
                            "Pinned $timeLabel$distLabel",
                            color = BravaBright,
                            fontSize = 12.sp,
                            fontFamily = GeistMonoFamily,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                NorthstarChip(
                    if (parked != null) "Pinned" else "Standby",
                    if (parked != null) ChipTone.Gold else ChipTone.Neutral,
                    dot = parked != null,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Map showing bike pin
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Line2, RoundedCornerShape(16.dp)),
            ) {
                NorthstarMap(
                    riderLat = riderOrigin?.first,
                    riderLng = riderOrigin?.second,
                    dest = parked?.let { it.lat to it.lng },
                    routePoints = emptyList(),
                    hasLocationPermission = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Parking Note (e.g., "Basement B2 Pillar 14")
            if (parked != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(Surf2)
                        .border(1.dp, Line, RoundedCornerShape(11.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    if (editingNote) {
                        BasicTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            textStyle = TextStyle(color = TextHi, fontSize = 13.sp, fontFamily = GeistFamily),
                            cursorBrush = SolidColor(BravaBright),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Save",
                            color = BravaBright,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                editingNote = false
                                tracker.updateParkingNote(noteInput)
                            },
                        )
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "PARKING NOTE",
                                color = TextLo,
                                fontSize = 9.5.sp,
                                fontFamily = GeistMonoFamily,
                            )
                            Text(
                                parked.note.ifBlank { "Add note (e.g. B2 Pillar 14)…" },
                                color = TextMid,
                                fontSize = 12.5.sp,
                            )
                        }
                        Icon(
                            NorthstarIcons.Edit,
                            contentDescription = "Edit note",
                            tint = TextMid,
                            modifier = Modifier.size(17.dp).clickable { editingNote = true },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                NorthstarBtn(
                    "Pin Parked Here",
                    onClick = {
                        val loc = routeViewModel.currentOrigin()
                        if (loc != null) {
                            tracker.recordParking(loc.first, loc.second, "Manually pinned by rider")
                        } else {
                            permLauncher.launch(blePermissions)
                        }
                    },
                    icon = NorthstarIcons.LocationPin,
                    variant = BtnVariant.Secondary,
                    size = BtnSize.Md,
                    modifier = Modifier.weight(1f),
                )
                if (parked != null) {
                    NorthstarBtn(
                        "Route to Bike",
                        onClick = {
                            routeViewModel.navigateToCoordinates(
                                name = "Parked Guerrilla 450",
                                lat = parked.lat,
                                lng = parked.lng,
                                subtitle = parked.address.ifBlank { "Parked bike location" },
                            )
                            onNavigateToBike()
                        },
                        icon = NorthstarIcons.Navi,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Md,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 2. JioTag 2 Live BLE Proximity Radar Card ─────────────────────────
        val scanning = trackerState.scanningBle
        val liveTag = trackerState.liveTag
        val rssi = liveTag?.rssi

        NorthstarCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (scanning) BravaTint else Surf2)
                            .border(1.dp, if (scanning) BravaBright else Line2, CircleShape),
                    ) {
                        Icon(
                            NorthstarIcons.Bt,
                            contentDescription = null,
                            tint = if (scanning) BravaBright else TextMid,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "JioTag 2 · Proximity Radar",
                            color = TextHi,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily,
                        )
                        Text(
                            if (trackerState.pairedTagMac.isNotBlank())
                                "Paired Tag: ${trackerState.pairedTagName} (${trackerState.pairedTagMac})"
                            else "Scans Google Find Hub (0xFEAA) & JioTag 2 BLE beacons",
                            color = TextLo,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                NorthstarBtn(
                    if (scanning) "Stop" else "Scan Radar",
                    onClick = {
                        if (scanning) {
                            tracker.stopBleRadar()
                        } else if (hasBlePerms()) {
                            tracker.startBleRadar()
                        } else {
                            permLauncher.launch(blePermissions)
                        }
                    },
                    icon = NorthstarIcons.Bt,
                    variant = if (scanning) BtnVariant.Ghost else BtnVariant.Primary,
                    size = BtnSize.Sm,
                )
            }

            Spacer(Modifier.height(14.dp))

            val signalProgress = if (rssi != null) ((rssi + 100f) / 60f).coerceIn(0.05f, 1f) else 0f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surf2)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Eyebrow("PROXIMITY STATUS")
                        Spacer(Modifier.height(3.dp))
                        Text(
                            liveTag?.proximityLabel ?: if (scanning) "Scanning for JioTag 2…" else "Radar idle",
                            color = if (rssi != null && rssi >= -72) OkGreen else TextHi,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val estDistText = liveTag?.let { "~${"%.1f".format(it.estimatedMeters)} m" } ?: "—"
                        val rssiText = rssi?.let { "$it dBm" } ?: "No signal"
                        Text(
                            estDistText,
                            color = BravaBright,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistMonoFamily,
                        )
                        Text(
                            rssiText,
                            color = TextLo,
                            fontSize = 11.sp,
                            fontFamily = GeistMonoFamily,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { signalProgress },
                    color = if (rssi != null && rssi >= -68) OkGreen else BravaBright,
                    trackColor = Bg1,
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                )
            }

            // Nearby detected BLE trackers list so the user can lock onto their JioTag 2
            if (trackerState.nearbyCandidates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Eyebrow("DETECTED TRACKER BEACONS NEARBY (${trackerState.nearbyCandidates.size})")
                Spacer(Modifier.height(6.dp))
                trackerState.nearbyCandidates.take(5).forEach { beacon ->
                    val isPaired = beacon.address == trackerState.pairedTagMac
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isPaired) BravaTint else Surf2)
                            .border(1.dp, if (isPaired) BravaBright else Line, RoundedCornerShape(11.dp))
                            .clickable { tracker.pairBleTag(beacon.address, beacon.name) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    beacon.name,
                                    color = TextHi,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (beacon.isJioOrFindHub) {
                                    NorthstarChip("Find Hub / JioTag 2", ChipTone.Gold)
                                }
                            }
                            Text(
                                "${beacon.address} · ${beacon.rssi} dBm (~${"%.1f".format(beacon.estimatedMeters)} m)",
                                color = TextLo,
                                fontSize = 11.sp,
                                fontFamily = GeistMonoFamily,
                            )
                        }
                        Text(
                            if (isPaired) "Paired ✓" else "Select",
                            color = BravaBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (trackerState.pairedTagMac.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unpair saved tag (${trackerState.pairedTagMac})",
                    color = TextLo,
                    fontSize = 11.5.sp,
                    modifier = Modifier.clickable { tracker.unpairBleTag() },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 3. Google Find Hub & JioThings Deep-Link Card ─────────────────────
        NorthstarCard(modifier = Modifier.fillMaxWidth()) {
            Eyebrow("CROWD-SOURCED NETWORK & 120dB SPEAKER")
            Spacer(Modifier.height(4.dp))
            Text(
                "Remote Tag Lookup & Ring",
                color = TextHi,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "JioTag 2 (2nd Gen) uses Google's end-to-end encrypted Find Hub network for long-range crowd-sourced tracking and ringing the tag's 120dB speaker. Launch either companion app in 1 tap:",
                color = TextMid,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                NorthstarBtn(
                    "Google Find Hub",
                    onClick = { tracker.openGoogleFindHub(ctx) },
                    icon = NorthstarIcons.Target,
                    variant = BtnVariant.Primary,
                    size = BtnSize.Md,
                    modifier = Modifier.weight(1f),
                )
                NorthstarBtn(
                    "JioThings App",
                    onClick = { tracker.openJioThings(ctx) },
                    icon = NorthstarIcons.Share,
                    variant = BtnVariant.Secondary,
                    size = BtnSize.Md,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
