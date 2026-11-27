package com.example.northstar.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.northstar.dash.nav.PlaceSearchEngine
import com.example.northstar.dash.nav.SearchPlace
import com.example.northstar.data.BikeTrackerManager
import com.example.northstar.data.DashPrefs
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.ConnStage
import com.example.northstar.viewmodel.DashViewModel
import com.example.northstar.viewmodel.RouteViewModel

@Composable
fun V2ExploreScreen(
    dashViewModel: DashViewModel,
    routeViewModel: RouteViewModel,
    onOpenDash: () -> Unit,
    onOpenBikeHub: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val dashUi by dashViewModel.ui.collectAsState()
    val routeState by routeViewModel.state.collectAsState()
    val savedPlaces by routeViewModel.saved.collectAsState()

    val bikeTracker = remember { BikeTrackerManager.get(ctx) }
    val bikeState by bikeTracker.state.collectAsState()
    val parkedBike = bikeState.parked

    val essentialPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val fullConnectPermissions = remember {
        buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    var hasLocPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasBgLocPerm by remember {
        mutableStateOf(com.example.northstar.util.DeviceReadiness.hasBackgroundLocationPermission(ctx))
    }
    var hasBtPerm by remember {
        mutableStateOf(com.example.northstar.util.DeviceReadiness.hasBluetoothIgnitionPermissions(ctx))
    }
    var batteryOk by remember {
        mutableStateOf(com.example.northstar.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx))
    }
    var savedSystemSsid by remember {
        mutableStateOf(DashPrefs.wifiSavedSsid(ctx))
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasLocPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                hasBgLocPerm = com.example.northstar.util.DeviceReadiness.hasBackgroundLocationPermission(ctx)
                hasBtPerm = com.example.northstar.util.DeviceReadiness.hasBluetoothIgnitionPermissions(ctx)
                batteryOk = com.example.northstar.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
                savedSystemSsid = DashPrefs.wifiSavedSsid(ctx)
                com.example.northstar.dash.FastReconnectManager.sync(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = com.example.northstar.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
    }
    val connectBatteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = com.example.northstar.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
        dashViewModel.connect()
    }
    fun connectWithPocketReadiness() {
        if (!batteryOk && !com.example.northstar.util.DeviceReadiness.batteryExemptionAsked(ctx)) {
            com.example.northstar.util.DeviceReadiness.markBatteryExemptionAsked(ctx)
            val launched = runCatching {
                connectBatteryLauncher.launch(com.example.northstar.util.DeviceReadiness.batteryExemptionIntent(ctx))
            }.isSuccess
            if (!launched) dashViewModel.connect()
        } else {
            dashViewModel.connect()
        }
    }

    val bgLocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBgLocPerm = granted || com.example.northstar.util.DeviceReadiness.hasBackgroundLocationPermission(ctx)
        com.example.northstar.dash.FastReconnectManager.sync(ctx)
    }
    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasBtPerm = com.example.northstar.util.DeviceReadiness.hasBluetoothIgnitionPermissions(ctx)
        com.example.northstar.dash.FastReconnectManager.sync(ctx)
    }
    val saveWifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && dashUi.ssid.isNotBlank()) {
            DashPrefs.setWifiSavedSsid(ctx, dashUi.ssid)
            savedSystemSsid = dashUi.ssid
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasLocPerm = res[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasBgLocPerm = com.example.northstar.util.DeviceReadiness.hasBackgroundLocationPermission(ctx)
        hasBtPerm = com.example.northstar.util.DeviceReadiness.hasBluetoothIgnitionPermissions(ctx)
        com.example.northstar.dash.FastReconnectManager.sync(ctx)
        if (essentialPermissions.all { res[it] == true }) {
            connectWithPocketReadiness()
        }
    }

    val origin = remember(dashUi.riderLat, dashUi.riderLng, hasLocPerm) {
        routeViewModel.currentOrigin()
    }
    val wirelessAuto = remember { DashPrefs.wirelessAutoConnect(ctx) }

    var showSaveModal by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var assignShortcutMode by remember { mutableStateOf<String?>(null) } // "HOME" or "WORK"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .padding(bottom = 110.dp),
    ) {
        // ── 1. Top Cockpit Status Header ──────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (dashUi.stage) {
                                    ConnStage.STREAMING -> OkGreen
                                    ConnStage.WIFI, ConnStage.AUTH -> Amber
                                    ConnStage.ERROR -> Alert
                                    ConnStage.OFFLINE -> if (wirelessAuto) BravaBright else Offline
                                }
                            )
                    )
                    Text(
                        "WAYDASH V2 · GUERRILLA 450",
                        color = TextLo,
                        fontSize = 10.5.sp,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when (dashUi.stage) {
                        ConnStage.STREAMING -> dashUi.destinationName?.let { "Navigating to $it" } ?: "Tripper Dash Live"
                        ConnStage.WIFI -> "Joining ${dashUi.ssid.ifBlank { "RE_*" }}…"
                        ConnStage.AUTH -> "Authenticating Dash…"
                        ConnStage.ERROR -> "Dash Link Interrupted"
                        ConnStage.OFFLINE -> "Where are we riding?"
                    },
                    color = TextHi,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (dashUi.stage) {
                    ConnStage.STREAMING -> {
                        NorthstarChip("LIVE", ChipTone.Gold, dot = true)
                        NorthstarBtn(
                            "Dash",
                            onClick = onOpenDash,
                            icon = NorthstarIcons.Dash,
                            variant = BtnVariant.Primary,
                            size = BtnSize.Sm,
                        )
                    }
                    ConnStage.WIFI, ConnStage.AUTH -> {
                        NorthstarChip("Linking…", ChipTone.Warn, dot = true)
                    }
                    else -> {
                        NorthstarBtn(
                            if (wirelessAuto) "Auto-Link · Connect" else "Connect",
                            onClick = {
                                val allGranted = essentialPermissions.all {
                                    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                                }
                                if (allGranted && hasBtPerm) connectWithPocketReadiness()
                                else permLauncher.launch(fullConnectPermissions)
                            },
                            icon = NorthstarIcons.Wifi,
                            variant = BtnVariant.Secondary,
                            size = BtnSize.Sm,
                        )
                    }
                }
            }
        }

        // ── 1b. One-Tap Pocket (Screen-Off) Auto-Connect Readiness Banner ─────
        val needsWifiSystemSave = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            dashUi.ssid.isNotBlank() && savedSystemSsid != dashUi.ssid
        val pocketPrompt: Triple<String, String, () -> Unit>? = when {
            !wirelessAuto -> null
            !batteryOk -> Triple(
                "Allow Screen-Off Pocket Streaming",
                "Tap to set WayDash battery to Unrestricted so Android doesn't freeze background Wi-Fi & Dash link while locked in your pocket.",
            ) {
                runCatching {
                    batteryLauncher.launch(com.example.northstar.util.DeviceReadiness.batteryExemptionIntent(ctx))
                }.onFailure {
                    runCatching { ctx.startActivity(com.example.northstar.util.DeviceReadiness.appSettingsIntent(ctx)) }
                }
            }
            !hasBtPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Triple(
                "Enable Bike Ignition Wakeup (Bluetooth)",
                "Tap to allow Nearby Devices so your bike or helmet Bluetooth wakes Wi-Fi scanning while your phone screen is off.",
            ) {
                btPermLauncher.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                )
            }
            hasLocPerm && !hasBgLocPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Triple(
                "Enable Closed-App Pocket Auto-Connect",
                "Tap to set Location to 'Allow all the time' so WayDash auto-joins ${dashUi.ssid.ifBlank { "RE_*" }} & streams GPS without unlocking your phone.",
            ) {
                bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            needsWifiSystemSave -> Triple(
                "Save ${dashUi.ssid} for Instant Pocket Auto-Join",
                "Add your Tripper Dash hotspot to Android Saved Wi-Fi so hardware screen-off scan joins it automatically.",
            ) {
                com.example.northstar.dash.FastReconnectManager.createSystemSaveWifiIntent(ctx)?.let { intent ->
                    runCatching { saveWifiLauncher.launch(intent) }
                }
            }
            else -> null
        }

        if (pocketPrompt != null) {
            val (bannerTitle, bannerBody, bannerAction) = pocketPrompt
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surf1)
                    .border(1.dp, Brava, RoundedCornerShape(14.dp))
                    .clickable { bannerAction() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        bannerTitle,
                        color = BravaBright,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        bannerBody,
                        color = TextMid,
                        fontSize = 11.sp,
                        fontFamily = GeistFamily,
                    )
                }
                NorthstarChip("Allow →", ChipTone.Gold)
            }
        }

        // ── 2. Built-in In-App Search Bar (No Google Maps Share Needed!) ──────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surf1)
                .border(
                    1.dp,
                    if (routeState.searchQuery.isNotBlank() || routeState.searching) Brava else Line2,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    NorthstarIcons.LocationPin,
                    contentDescription = "Search",
                    tint = BravaBright,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (routeState.searchQuery.isEmpty()) {
                        Text(
                            if (assignShortcutMode != null) "Search address to set as $assignShortcutMode…"
                            else "Search place, address, petrol pump, or paste link…",
                            color = TextLo,
                            fontSize = 14.sp,
                            fontFamily = GeistFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = routeState.searchQuery,
                        onValueChange = { routeViewModel.updateSearchQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextHi,
                            fontSize = 14.5.sp,
                            fontFamily = GeistFamily,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(BravaBright),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (routeState.searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = BravaBright,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (routeState.searchQuery.isNotEmpty() || assignShortcutMode != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Surf2)
                            .clickable {
                                assignShortcutMode = null
                                routeViewModel.clearSearch()
                                focusManager.clearFocus()
                            },
                    ) {
                        Icon(NorthstarIcons.X, contentDescription = "Clear", tint = TextMid, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 3. Quick Category & Shortcut Chips ────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            // Parked Bike quick chip
            parkedBike?.let { parked ->
                QuickChip(
                    label = "📍 My Bike",
                    active = false,
                    accent = OkGreen,
                    onClick = {
                        focusManager.clearFocus()
                        routeViewModel.navigateToCoordinates(
                            name = "Parked Guerrilla 450",
                            lat = parked.lat,
                            lng = parked.lng,
                            subtitle = parked.address.ifBlank { "Last parked spot" },
                        )
                    },
                )
            }

            // Home shortcut chip
            val home = routeState.homePlace
            QuickChip(
                label = if (home != null) "🏠 Home" else "🏠 Set Home",
                active = assignShortcutMode == "HOME",
                onClick = {
                    if (home != null) {
                        focusManager.clearFocus()
                        routeViewModel.selectSearchPlace(home)
                    } else {
                        assignShortcutMode = if (assignShortcutMode == "HOME") null else "HOME"
                    }
                },
            )

            // Work shortcut chip
            val work = routeState.workPlace
            QuickChip(
                label = if (work != null) "🏢 Work" else "🏢 Set Work",
                active = assignShortcutMode == "WORK",
                onClick = {
                    if (work != null) {
                        focusManager.clearFocus()
                        routeViewModel.selectSearchPlace(work)
                    } else {
                        assignShortcutMode = if (assignShortcutMode == "WORK") null else "WORK"
                    }
                },
            )

            // Rider POI Categories (Petrol, RE Service, Cafe, ATM, Hospital)
            PlaceSearchEngine.quickCategories.forEach { cat ->
                val isSelected = routeState.activeCategory == cat
                QuickChip(
                    label = "${cat.emoji} ${cat.label}",
                    active = isSelected,
                    onClick = {
                        focusManager.clearFocus()
                        routeViewModel.searchQuickCategory(cat)
                    },
                )
            }
        }

        // ── 4. Live Search Results Dropdown ───────────────────────────────────
        AnimatedVisibility(visible = routeState.searchResults.isNotEmpty()) {
            NorthstarCard(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                padding = 8.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Eyebrow(
                        if (assignShortcutMode != null) "Tap a place to save as $assignShortcutMode"
                        else "Search Results (${routeState.searchResults.size})"
                    )
                    Text(
                        "Close",
                        color = BravaBright,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { routeViewModel.clearSearch() },
                    )
                }
                routeState.searchResults.forEachIndexed { idx, place ->
                    if (idx > 0) NorthstarDivider(Modifier.padding(horizontal = 6.dp))
                    SearchResultRow(
                        place = place,
                        onClick = {
                            focusManager.clearFocus()
                            when (assignShortcutMode) {
                                "HOME" -> {
                                    routeViewModel.setHome(place.copy(name = "Home (${place.name})"))
                                    assignShortcutMode = null
                                    routeViewModel.clearSearch()
                                }
                                "WORK" -> {
                                    routeViewModel.setWork(place.copy(name = "Work (${place.name})"))
                                    assignShortcutMode = null
                                    routeViewModel.clearSearch()
                                }
                                else -> routeViewModel.selectSearchPlace(place)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 5. Interactive Map + Route Preview Card ───────────────────────────
        val dest = routeState.destination
        val destCoords = if (dest?.lat != null && dest.lng != null) dest.lat to dest.lng else dashUi.destLatLng
        val routePts = routeState.route?.geometry ?: dashUi.routePoints

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (dest != null) 250.dp else 210.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Line2, RoundedCornerShape(20.dp)),
        ) {
            NorthstarMap(
                riderLat = origin?.first ?: dashUi.riderLat,
                riderLng = origin?.second ?: dashUi.riderLng,
                dest = destCoords,
                routePoints = routePts,
                hasLocationPermission = hasLocPerm,
                fitRoute = destCoords != null && routePts.isNotEmpty(),
                modifier = Modifier.fillMaxSize(),
            )

            // Floating top-left badge on map
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xD90A1220))
                    .border(1.dp, Line2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(NorthstarIcons.Target, null, tint = if (hasLocPerm) OkGreen else Warn, modifier = Modifier.size(14.dp))
                Text(
                    if (!hasLocPerm) "Tap to grant GPS"
                    else if (routeState.routing) "Calculating route…"
                    else routeState.routingEngine?.let { "$it Routing" } ?: "Live Rider Map",
                    color = TextHi,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                    modifier = Modifier.let {
                        if (!hasLocPerm) it.clickable { permLauncher.launch(essentialPermissions) } else it
                    },
                )
            }
        }

        // ── 6. Active Route Planning Card (When a Destination is Selected) ─────
        if (dest != null) {
            Spacer(Modifier.height(12.dp))
            NorthstarCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Eyebrow("SELECTED DESTINATION")
                        Spacer(Modifier.height(3.dp))
                        Text(
                            dest.name,
                            color = TextHi,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily,
                        )
                        if (dest.lat != null && dest.lng != null) {
                            Text(
                                "%.5f, %.5f".format(dest.lat, dest.lng),
                                color = TextLo,
                                fontSize = 11.5.sp,
                                fontFamily = GeistMonoFamily,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NorthstarIconBtn(
                            NorthstarIcons.Plus,
                            onClick = {
                                saveName = dest.name
                                showSaveModal = true
                            },
                            size = 36.dp,
                        )
                        NorthstarIconBtn(
                            NorthstarIcons.X,
                            onClick = { routeViewModel.clear() },
                            size = 36.dp,
                        )
                    }
                }

                // Route alternatives pills
                if (routeState.alternatives.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        routeState.alternatives.forEachIndexed { idx, alt ->
                            val selected = idx == routeState.selectedRoute
                            val mins = (alt.totalSeconds / 60.0).toInt()
                            val dur = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                            val km = "%.1f km".format(alt.totalMeters / 1000.0)
                            QuickChip(
                                label = "Route ${idx + 1} · $dur ($km)",
                                active = selected,
                                onClick = { routeViewModel.selectRoute(idx) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Metrics strip: Distance, Duration, Arrival ETA
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Distance" to (routeState.distanceText ?: if (routeState.routing) "…" else "—"),
                        "Ride Time" to (routeState.durationText ?: if (routeState.routing) "…" else "—"),
                        "Arrival" to (routeState.etaText ?: if (routeState.routing) "…" else "—"),
                    ).forEach { (label, value) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Surf2)
                                .border(1.dp, Line, RoundedCornerShape(12.dp))
                                .padding(vertical = 9.dp, horizontal = 6.dp),
                        ) {
                            Text(
                                value,
                                color = TextHi,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistMonoFamily,
                            )
                            Text(label, color = TextLo, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }

                routeState.routingNotice?.let { notice ->
                    Spacer(Modifier.height(8.dp))
                    Text(notice, color = Warn, fontSize = 11.5.sp)
                }

                Spacer(Modifier.height(14.dp))

                // Primary Action: Send to Tripper Dash & Start Navigation
                NorthstarBtn(
                    if (dashUi.stage == ConnStage.STREAMING) "Send to Tripper Dash Now"
                    else "Start Navigation on Dash",
                    onClick = {
                        dashViewModel.setDestination(dest.name, dest.lat, dest.lng)
                        val allGranted = essentialPermissions.all {
                            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (allGranted) {
                            dashViewModel.connect()
                            onOpenDash()
                        } else {
                            permLauncher.launch(essentialPermissions)
                            onOpenDash()
                        }
                    },
                    icon = NorthstarIcons.Navi,
                    variant = BtnVariant.Primary,
                    size = BtnSize.Lg,
                    enabled = dest.lat != null && dest.lng != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── 7. JioTag 2 / Parked Bike Quick Card ──────────────────────────────
        Spacer(Modifier.height(14.dp))
        NorthstarCard(
            modifier = Modifier.fillMaxWidth(),
            padding = 14.dp,
            onClick = onOpenBikeHub,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BravaTint)
                            .border(1.dp, BravaTint2, RoundedCornerShape(12.dp)),
                    ) {
                        Icon(NorthstarIcons.Motor, null, tint = BravaBright, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "My Bike & JioTag 2 Finder",
                                color = TextHi,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            NorthstarChip("Radar", ChipTone.Gold)
                        }
                        Text(
                            parkedBike?.let { p ->
                                "Parked: ${p.address.ifBlank { "%.4f, %.4f".format(p.lat, p.lng) }}"
                            } ?: "Auto-pins parking spot on Dash disconnect · Live BLE Tag Radar",
                            color = TextMid,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Icon(NorthstarIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(20.dp))
            }
        }

        // ── 8. Saved Places & Recent Searches ─────────────────────────────────
        if (savedPlaces.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Eyebrow("SAVED PLACES", Modifier.padding(bottom = 8.dp, start = 4.dp))
            NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                savedPlaces.take(6).forEachIndexed { idx, loc ->
                    if (idx > 0) NorthstarDivider(Modifier.padding(horizontal = 6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { routeViewModel.selectSaved(loc) }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BravaTint),
                        ) {
                            Icon(NorthstarIcons.Flag, null, tint = BravaBright, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(loc.name, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (loc.note.isNotBlank()) {
                                Text(loc.note, color = TextLo, fontSize = 11.5.sp)
                            }
                        }
                        Icon(NorthstarIcons.Navi, null, tint = BravaBright, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }

        if (routeState.recents.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
            ) {
                Eyebrow("RECENT DESTINATIONS")
                Text(
                    "Clear",
                    color = TextLo,
                    fontSize = 11.5.sp,
                    modifier = Modifier.clickable { routeViewModel.clearRecents() },
                )
            }
            NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                routeState.recents.take(6).forEachIndexed { idx, place ->
                    if (idx > 0) NorthstarDivider(Modifier.padding(horizontal = 6.dp))
                    SearchResultRow(
                        place = place,
                        onClick = { routeViewModel.selectSearchPlace(place) },
                    )
                }
            }
        }
    }

    if (showSaveModal) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveModal = false },
            containerColor = Surf1,
            title = { Text("Save Place", color = TextHi, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Save this destination for 1-tap access:", color = TextMid, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surf2)
                            .border(1.dp, Line2, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        BasicTextField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            textStyle = TextStyle(color = TextHi, fontSize = 14.sp),
                            cursorBrush = SolidColor(BravaBright),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                NorthstarBtn(
                    "Save",
                    onClick = {
                        routeViewModel.saveCurrentDestination(saveName)
                        showSaveModal = false
                    },
                    variant = BtnVariant.Primary,
                    size = BtnSize.Sm,
                )
            },
            dismissButton = {
                NorthstarBtn(
                    "Cancel",
                    onClick = { showSaveModal = false },
                    variant = BtnVariant.Ghost,
                    size = BtnSize.Sm,
                )
            },
        )
    }
}

@Composable
private fun QuickChip(
    label: String,
    active: Boolean,
    accent: Color = BravaBright,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (active) Brava else Surf1)
            .border(1.dp, if (active) BravaBright else Line2, RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (active) OnBrava else TextHi,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontFamily = GeistFamily,
        )
    }
}

@Composable
private fun SearchResultRow(
    place: SearchPlace,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surf2)
                .border(1.dp, Line, RoundedCornerShape(10.dp)),
        ) {
            Icon(NorthstarIcons.LocationPin, null, tint = BravaBright, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    place.name,
                    color = TextHi,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                place.category?.takeIf { it.isNotBlank() }?.let { cat ->
                    Text(
                        cat.uppercase(),
                        color = BravaBright,
                        fontSize = 9.5.sp,
                        fontFamily = GeistMonoFamily,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(BravaTint)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            if (place.subtitle.isNotBlank()) {
                Text(
                    place.subtitle,
                    color = TextLo,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        place.distanceMeters?.let { d ->
            Spacer(Modifier.width(8.dp))
            Text(
                if (d < 1000) "${d.toInt()} m" else "%.1f km".format(d / 1000.0),
                color = TextMid,
                fontSize = 11.5.sp,
                fontFamily = GeistMonoFamily,
            )
        }
    }
}
