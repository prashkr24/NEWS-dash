package com.example.northstar.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.AuthViewModel
import com.example.northstar.viewmodel.ConnectionState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    conn: ConnectionState,
    onConnChange: (ConnectionState) -> Unit,
    authViewModel: AuthViewModel,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
    uiVersion: Int = 2,
    onUiVersionChange: (Int) -> Unit = {},
    onOpenDiscovery: () -> Unit = {},
) {
    val auth by authViewModel.state.collectAsState()
    val email = auth.email ?: "Not signed in"
    val initials = remember(auth.email, auth.displayName) {
        val src = auth.displayName?.takeIf { it.isNotBlank() } ?: auth.email ?: "?"
        src.split(" ", ".", "@").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    }

    val ctx = LocalContext.current
    val dashVm = remember { com.example.northstar.viewmodel.DashViewModel.getInstance(ctx) }
    val dashUi by dashVm.ui.collectAsState()

    var autoConnect by remember { mutableStateOf(com.example.northstar.data.DashPrefs.autoConnect(ctx)) }
    var wirelessAuto by remember { mutableStateOf(com.example.northstar.data.DashPrefs.wirelessAutoConnect(ctx)) }
    var unitsKm by remember { mutableStateOf(com.example.northstar.data.DashPrefs.unitsKm(ctx)) }
    var showWifiConfigModal by remember { mutableStateOf(false) }
    var ssidInput by remember(dashUi.ssid) { mutableStateOf(dashUi.ssid) }
    var passInput by remember(dashUi.wifiPassword) { mutableStateOf(dashUi.wifiPassword) }

    val voiceManager = remember { com.example.northstar.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        com.example.northstar.dash.nav.VoiceMode.OFF   -> "Off"
        com.example.northstar.dash.nav.VoiceMode.CHIME -> "Chime"
        com.example.northstar.dash.nav.VoiceMode.FULL  -> "Full TTS"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 110.dp),
    ) {
        ScreenHeader(
            title = "Settings",
            onBack = onBack,
            hint = "Wireless auto-connect, dash map, voice guidance, media/call controls, and v2/v1 layout mode.",
        )

        // Account card
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp, onClick = {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GoldTint),
                ) {
                    Text(initials, color = Gold, fontFamily = GeistMonoFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    auth.displayName?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextHi, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    }
                    Text(
                        email,
                        color = if (auth.displayName.isNullOrBlank()) TextHi else TextMid,
                        fontSize = if (auth.displayName.isNullOrBlank()) 15.5.sp else 12.5.sp,
                        fontWeight = if (auth.displayName.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = GeistFamily,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        SectionLabel("App experience (v2 / Classic v1)")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(
                options = listOf("WayDash v2", "Classic v1"),
                selected = if (uiVersion == 1) "Classic v1" else "WayDash v2",
                onSelect = { picked ->
                    val v = if (picked == "Classic v1") 1 else 2
                    com.example.northstar.data.DashPrefs.setUiVersion(ctx, v)
                    onUiVersionChange(v)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (uiVersion == 2) "v2 includes built-in place/POI search, JioTag 2 Bike Hub, and streamlined 4-tab navigation."
                else "Classic v1 restores the original 5-tab layout (Home, Route, Dash, Signals, Rides).",
                color = TextLo,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionLabel("Wireless Auto-Connect & Dash Link")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(
                NorthstarIcons.Wifi,
                "Tripper Dash Status",
                sub = when (conn) {
                    ConnectionState.Connected -> "Streaming live to ${dashUi.ssid.ifBlank { "Tripper Dash" }}"
                    ConnectionState.Searching -> "Connecting to ${dashUi.ssid.ifBlank { "RE_*" }}…"
                    ConnectionState.Offline -> if (dashUi.ssid.isNotBlank()) "Paired with ${dashUi.ssid} · Standby" else "Auto-discovers any RE_* hotspot"
                },
                control = {
                    NorthstarChip(
                        if (conn == ConnectionState.Connected) "Linked" else "Standby",
                        if (conn == ConnectionState.Connected) ChipTone.Gold else ChipTone.Off,
                        dot = true,
                    )
                },
            )
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                NorthstarIcons.Zap,
                "Wireless Auto-Connect (Background)",
                "Android Auto style · joins RE_* & streams without opening the app",
                control = {
                    NorthstarToggle(wirelessAuto) {
                        wirelessAuto = it
                        com.example.northstar.data.DashPrefs.setWirelessAutoConnect(ctx, it)
                        com.example.northstar.dash.FastReconnectManager.sync(ctx, forceReset = true)
                    }
                },
            )
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                NorthstarIcons.Sync,
                "Auto-connect when app opens",
                "Automatically links when Dash view is opened",
                control = {
                    NorthstarToggle(autoConnect) {
                        autoConnect = it
                        com.example.northstar.data.DashPrefs.setAutoConnect(ctx, it)
                    }
                },
            )
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                NorthstarIcons.Gear,
                "Dash Wi‑Fi SSID & Password",
                if (dashUi.ssid.isNotBlank()) "Paired: ${dashUi.ssid} · Tap to edit or forget"
                else "Auto-discovering RE_* prefix · Tap to configure",
                control = {
                    Icon(NorthstarIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(18.dp))
                },
                last = true,
                onClick = {
                    ssidInput = dashUi.ssid
                    passInput = dashUi.wifiPassword
                    showWifiConfigModal = true
                },
            )
        }

        SectionLabel("Media & calls on dash")
        val lifecycleOwner = LocalLifecycleOwner.current
        fun answerGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ANSWER_PHONE_CALLS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var mediaGranted by remember { mutableStateOf(com.example.northstar.media.MediaInfoProvider.isAccessGranted(ctx)) }
        var callAnswerGranted by remember { mutableStateOf(answerGranted()) }
        DisposableEffect(lifecycleOwner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    mediaGranted = com.example.northstar.media.MediaInfoProvider.isAccessGranted(ctx)
                    callAnswerGranted = answerGranted()
                    // Re-sync background Wi-Fi watcher once notification listener is granted
                    com.example.northstar.dash.FastReconnectManager.sync(ctx)
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            callAnswerGranted = it
        }
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(
                NorthstarIcons.Zap,
                "Now playing, calls & background wake",
                if (mediaGranted) "Enabled · song info, caller & 24/7 background auto-connect active"
                else "Tap to allow notification access (also enables 24/7 background auto-link)",
                control = {
                    NorthstarChip(
                        if (mediaGranted) "On" else "Enable",
                        if (mediaGranted) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                },
                onClick = if (mediaGranted) null else {
                    { runCatching { ctx.startActivity(com.example.northstar.media.MediaInfoProvider.accessSettingsIntent()) } }
                },
            )
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                NorthstarIcons.Bt,
                "Answer calls from joystick",
                if (callAnswerGranted) "Enabled · UP answers, DOWN rejects"
                else "Tap to allow answering/rejecting calls",
                control = {
                    NorthstarChip(
                        if (callAnswerGranted) "On" else "Enable",
                        if (callAnswerGranted) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                },
                onClick = if (callAnswerGranted) null else {
                    { callPermLauncher.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }
                },
            )
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            var msgAlerts by remember { mutableStateOf(com.example.northstar.data.AlertPrefs.messagesOnDash(ctx)) }
            SettingRow(
                NorthstarIcons.Bell,
                "Message alerts on dash",
                if (mediaGranted) "Sender + snippet as a card on the dash for 8s"
                else "Needs notification access (enable above)",
                control = {
                    NorthstarToggle(msgAlerts && mediaGranted) { on ->
                        msgAlerts = on
                        com.example.northstar.data.AlertPrefs.setMessagesOnDash(ctx, on)
                    }
                },
                last = true,
            )
        }

        SectionLabel("Voice & guidance")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(listOf("Off", "Chime", "Full TTS"), voice, {
                voiceManager.setMode(when (it) {
                    "Off"      -> com.example.northstar.dash.nav.VoiceMode.OFF
                    "Full TTS" -> com.example.northstar.dash.nav.VoiceMode.FULL
                    else       -> com.example.northstar.dash.nav.VoiceMode.CHIME
                })
            }, Modifier.fillMaxWidth())
        }

        SectionLabel("Dash map")
        MapProviderSection()

        SectionLabel("Routing")
        RoutingEngineSection()

        SectionLabel("Units")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(
                listOf("Kilometres", "Miles"),
                if (unitsKm) "Kilometres" else "Miles",
                {
                    unitsKm = (it == "Kilometres")
                    com.example.northstar.data.DashPrefs.setUnitsKm(ctx, unitsKm)
                },
                Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("Sync & Diagnostics")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            val (syncTitle, syncSub) = when {
                !auth.syncAvailable -> "Local only" to "Add your own Firebase project to sync across devices"
                auth.isSignedIn     -> "Synced" to (auth.email ?: "Signed in")
                else                -> "Not signed in" to "Sign in to sync across devices · data stays local until then"
            }
            SettingRow(
                NorthstarIcons.Sync, syncTitle, syncSub,
                control = {
                    NorthstarChip(
                        if (auth.isSignedIn) "On" else "Off",
                        if (auth.isSignedIn) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                },
            )
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                NorthstarIcons.Bt,
                "Protocol & Joystick Signals",
                "Inspect raw Tripper Dash UDP packets & joystick hex codes",
                control = {
                    Icon(NorthstarIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(18.dp))
                },
                last = true,
                onClick = onOpenDiscovery,
            )
        }

        Spacer(Modifier.height(22.dp))

        if (auth.isSignedIn) {
            NorthstarBtn(
                "Sign out",
                onClick = { authViewModel.signOut(); onSignedOut() },
                icon = NorthstarIcons.Power,
                variant = BtnVariant.Danger,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        TestBuildCard()

        val appVersion = remember { com.example.northstar.data.UpdateChecker.currentVersionName(ctx) }
        Text(
            "WAYDASH v$appVersion · ${if (!auth.syncAvailable) "local only" else if (auth.isSignedIn) "sync on" else "sync off"}",
            color = TextDis, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
        Text(
            "Independent community project — not affiliated with, endorsed by, or supported by " +
                "Royal Enfield. \"Royal Enfield\", \"Himalayan\" and \"Tripper\" are trademarks of " +
                "their respective owners, used here only to describe compatible hardware. The dash " +
                "link is unofficial and exists solely for interoperability with hardware you own — use at your own risk.",
            color = TextDis, fontSize = 10.sp, fontFamily = GeistFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, start = 8.dp, end = 8.dp),
        )
    }

    if (showWifiConfigModal) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWifiConfigModal = false },
            containerColor = Surf1,
            title = { Text("Tripper Dash Wi‑Fi", color = TextHi, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Leave SSID blank to auto-discover any Royal Enfield RE_* hotspot, or enter your exact SSID:",
                        color = TextMid, fontSize = 12.5.sp,
                    )
                    Column {
                        Text("SSID", color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surf2)
                                .border(1.dp, Line2, RoundedCornerShape(10.dp))
                                .padding(11.dp),
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = ssidInput,
                                onValueChange = { ssidInput = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = TextHi, fontSize = 14.sp, fontFamily = GeistMonoFamily),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(BravaBright),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Column {
                        Text("PASSWORD (DEFAULT: 12345678)", color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surf2)
                                .border(1.dp, Line2, RoundedCornerShape(10.dp))
                                .padding(11.dp),
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = passInput,
                                onValueChange = { passInput = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = TextHi, fontSize = 14.sp, fontFamily = GeistMonoFamily),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(BravaBright),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (dashUi.ssid.isNotBlank()) {
                        NorthstarBtn(
                            "Forget Paired Dash (Rediscover RE_*)",
                            onClick = {
                                dashVm.forgetDash()
                                ssidInput = ""
                                showWifiConfigModal = false
                            },
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                        )
                    }
                }
            },
            confirmButton = {
                NorthstarBtn(
                    "Save",
                    onClick = {
                        dashVm.setSsid(ssidInput)
                        dashVm.setWifiPassword(passInput.ifBlank { "12345678" })
                        showWifiConfigModal = false
                    },
                    variant = BtnVariant.Primary,
                    size = BtnSize.Sm,
                )
            },
            dismissButton = {
                NorthstarBtn(
                    "Cancel",
                    onClick = { showWifiConfigModal = false },
                    variant = BtnVariant.Ghost,
                    size = BtnSize.Sm,
                )
            },
        )
    }
}

/**
 * Test-build channel: installs a freshly pushed APK WITHOUT a version bump. Compares the running
 * APK's CHECKSUM against the published one (Firestore `meta/test_build`) and, if they differ,
 * offers a one-tap download+install (same signing key → installs in place, keeping data). Checksum
 * means it's correct however the build was installed, and the card clears itself once the matching
 * APK is running. Invisible when up to date / no build published / Firebase off.
 */
@Composable
private fun TestBuildCard() {
    // Debug-channel only: the test-build installer must never appear in a public release build.
    if (!com.example.northstar.BuildConfig.DEBUG) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var build by remember { mutableStateOf<com.example.northstar.data.TestBuildChecker.TestBuild?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }

    suspend fun refresh() {
        checking = true
        build = com.example.northstar.data.TestBuildChecker.fetchLatest(ctx)
        checking = false
    }
    LaunchedEffect(Unit) { refresh() }

    val b = build
    // Nothing published, or the running APK already matches the published checksum → no card.
    if (b == null || !com.example.northstar.data.TestBuildChecker.needsInstall(ctx, b)) return

    fun installNow() {
        busy = true; status = "Downloading…"
        scope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.northstar.data.UpdateChecker.download(ctx, b.url) { p ->
                    status = "Downloading… ${(p * 100).toInt()}%"
                }
            }
            if (file == null) { status = "Download failed — try again"; busy = false; return@launch }
            status = "Opening installer…"
            // No bookkeeping needed: once the new APK is running its checksum matches the published
            // one, so needsInstall() returns false and this card disappears on its own.
            val started = com.example.northstar.data.UpdateChecker.install(ctx, file)
            if (!started) status = "Allow “install unknown apps”, then tap again"
            busy = false
        }
    }

    NorthstarCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("New test build", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(b.builtAt.ifBlank { b.buildId })
                        if (b.sizeBytes > 0) append(" · ${b.sizeBytes / (1024 * 1024)} MB")
                    },
                    color = TextMid, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp),
                )
                if (b.notes.isNotBlank())
                    Text(b.notes, color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                if (status.isNotBlank())
                    Text(status, color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(top = 4.dp))
            }
            NorthstarBtn(
                if (busy) "…" else "Install",
                onClick = { if (!busy) installNow() },
                icon = NorthstarIcons.Wifi,
                variant = BtnVariant.Primary,
                size = BtnSize.Sm,
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Eyebrow(label, Modifier.padding(top = 22.dp, bottom = 9.dp, start = 4.dp))
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    control: @Composable () -> Unit,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 13.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = TextMid, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (sub != null) Text(sub, color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
}
