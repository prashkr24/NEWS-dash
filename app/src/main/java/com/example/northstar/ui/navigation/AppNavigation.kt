package com.example.northstar.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring

import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.screens.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.AppViewModel
import com.example.northstar.viewmodel.AuthViewModel
import com.example.northstar.viewmodel.ConnStage
import com.example.northstar.viewmodel.ConnectionState
import com.example.northstar.viewmodel.DashViewModel
import com.example.northstar.viewmodel.RouteViewModel

sealed class Screen(val route: String) {
    object Login    : Screen("login")
    object Home     : Screen("home")
    object Route    : Screen("route")
    object Dash     : Screen("dash")
    object BikeHub  : Screen("bike_hub")
    object Garage   : Screen("garage")
    object Rides    : Screen("rides")
    object Discovery: Screen("discovery")
    object Settings : Screen("settings")
}

private data class NavTab(val screen: Screen, val icon: ImageVector, val label: String)

private val v2BottomTabs = listOf(
    NavTab(Screen.Home,     NorthstarIcons.Navi,    "Explore"),
    NavTab(Screen.Dash,     NorthstarIcons.Dash,    "Dash"),
    NavTab(Screen.BikeHub,  NorthstarIcons.Motor,   "My Bike"),
    NavTab(Screen.Settings, NorthstarIcons.Gear,    "Settings"),
)

private val v1BottomTabs = listOf(
    NavTab(Screen.Home,      NorthstarIcons.Home,    "Home"),
    NavTab(Screen.Route,     NorthstarIcons.Navi,    "Route"),
    NavTab(Screen.Dash,      NorthstarIcons.Dash,    "Dash"),
    NavTab(Screen.Discovery, NorthstarIcons.Bt,      "Signals"),
    NavTab(Screen.Rides,     NorthstarIcons.History, "Rides"),
)

@Composable
fun AppNavigation(
    authViewModel: AuthViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
    dashViewModel: DashViewModel = DashViewModel.getInstance(androidx.compose.ui.platform.LocalContext.current),
    routeViewModel: RouteViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var uiVersion by remember { mutableIntStateOf(com.example.northstar.data.DashPrefs.uiVersion(ctx)) }

    // Analytics screen_view per destination. A single-Activity Compose app doesn't auto-log these,
    // so feed each nav route to Analytics — that's what makes per-feature usage (and time spent on
    // each screen) visible. No-op when Firebase isn't configured.
    LaunchedEffect(currentRoute) {
        currentRoute?.let { com.example.northstar.util.Telemetry.logScreen(it) }
    }

    // Navigate to a top-level destination with ONE consistent pattern — whether the tap came from
    // the bottom bar or a Home-screen button. The Home button used to do a plain navigate() while
    // the bottom bar used saveState/restoreState; mixing them corrupted the back stack so that,
    // after opening Dash from the Home button, tapping Home did nothing (target == start
    // destination + a restoreState mismatch). Routing both through this fixes it.
    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Skip the login screen on every cold start once the rider has passed it (signed in OR chose
    // "continue locally"); cleared on sign-out so it returns. Remembered once, read here.
    val startDest = remember {
        if (com.example.northstar.data.AuthPrefs.isOnboarded(ctx)) Screen.Home.route else Screen.Login.route
    }

    val showBottomNav = currentRoute in listOf(
        Screen.Home.route, Screen.Route.route, Screen.Dash.route, Screen.BikeHub.route,
        Screen.Garage.route, Screen.Rides.route, Screen.Discovery.route, Screen.Settings.route,
    )

    val garageTab by appViewModel.garageTab.collectAsState()
    val routeState by routeViewModel.state.collectAsState()

    // Single source of truth for connection status: the real dash stage. Fixes Home
    // claiming "Connected" while the Dash screen says it isn't.
    //
    // IMPORTANT: collect WITHOUT reading the state in the composable body. While streaming,
    // dashViewModel.ui updates ~15×/s (frame counter, rider position…). Reading it directly here
    // recomposed the entire nav scaffold 15×/s, which starved/janked the bottom-nav tabs — you
    // couldn't open Home while connected. derivedStateOf re-evaluates cheaply but only recomposes
    // readers when `conn` actually changes (i.e. when the STAGE changes).
    val dashUiState = dashViewModel.ui.collectAsState()
    val conn by remember {
        derivedStateOf {
            when (dashUiState.value.stage) {
                ConnStage.STREAMING -> ConnectionState.Connected
                ConnStage.WIFI, ConnStage.AUTH -> ConnectionState.Searching
                else -> ConnectionState.Offline
            }
        }
    }

    // Prefetch map tiles the moment a destination resolves (internet still reachable here)
    LaunchedEffect(routeState.destination?.lat, routeState.destination?.lng) {
        val d = routeState.destination
        if (d?.lat != null && d.lng != null) dashViewModel.prefetchTiles(d.lat, d.lng)
    }

    // Auto-navigate when a Maps share arrives
    LaunchedEffect(routeState.pendingNavigate, currentRoute) {
        if (routeState.pendingNavigate && currentRoute != null && currentRoute != Screen.Login.route) {
            val target = if (uiVersion == 2) Screen.Home.route else Screen.Route.route
            if (currentRoute != target) {
                navigateTab(target)
            }
            routeViewModel.onNavigated()
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDest,
            ) {
                composable(Screen.Login.route) {
                    LoginScreen(
                        authViewModel = authViewModel,
                        onSignedIn = {
                            com.example.northstar.data.AuthPrefs.setOnboarded(ctx, true)
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        },
                        onSkip = {
                            com.example.northstar.data.AuthPrefs.setOnboarded(ctx, true)
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Screen.Home.route) {
                    if (uiVersion == 2) {
                        V2ExploreScreen(
                            dashViewModel = dashViewModel,
                            routeViewModel = routeViewModel,
                            onOpenDash = { navigateTab(Screen.Dash.route) },
                            onOpenBikeHub = { navigateTab(Screen.BikeHub.route) },
                            onOpenSettings = { navigateTab(Screen.Settings.route) },
                        )
                    } else {
                        HomeScreen(
                            conn = conn,
                            onNavigate = { dest ->
                                when (dest) {
                                    "route" -> navigateTab(Screen.Route.route)
                                    "dash" -> navigateTab(Screen.Dash.route)
                                    "garage" -> navigateTab(Screen.Garage.route)
                                    "settings" -> navController.navigate(Screen.Settings.route)
                                }
                            },
                            routeViewModel = routeViewModel,
                        )
                    }
                }

                composable(Screen.Route.route) {
                    RouteScreen(
                        routeViewModel = routeViewModel,
                        onBack = { navController.popBackStack() },
                        onSentToDash = { destName ->
                            dashViewModel.setDestination(
                                name = destName,
                                lat  = routeState.destination?.lat,
                                lng  = routeState.destination?.lng,
                            )
                            dashViewModel.connect()
                            navController.navigate(Screen.Dash.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
                    )
                }

                composable(Screen.Dash.route) {
                    DashScreen(
                        vm = dashViewModel,
                        onOpenSettings = { navigateTab(Screen.Settings.route) },
                    )
                }

                composable(Screen.BikeHub.route) {
                    BikeHubScreen(
                        appViewModel = appViewModel,
                        routeViewModel = routeViewModel,
                        onNavigateToBike = { navigateTab(Screen.Home.route) },
                    )
                }

                composable(Screen.Garage.route) {
                    GarageScreen(
                        tab = garageTab,
                        onTabChange = { appViewModel.setGarageTab(it) },
                    )
                }

                composable(Screen.Rides.route) {
                    RidesScreen()
                }

                composable(Screen.Discovery.route) {
                    com.example.northstar.ui.screens.DiscoveryScreen()
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        conn = conn,
                        onConnChange = { appViewModel.setConn(it) },
                        authViewModel = authViewModel,
                        onSignedOut = {
                            com.example.northstar.data.AuthPrefs.setOnboarded(ctx, false)
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                        uiVersion = uiVersion,
                        onUiVersionChange = { uiVersion = it },
                        onOpenDiscovery = { navController.navigate(Screen.Discovery.route) },
                    )
                }
            }
        }

        // Checks GitHub Releases on app open; shows an update dialog only if a newer
        // version is published. Renders nothing otherwise.
        com.example.northstar.ui.components.UpdatePrompt()

        if (showBottomNav) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                NorthstarBottomNav(
                    tabs = if (uiVersion == 2) v2BottomTabs else v1BottomTabs,
                    currentRoute = currentRoute,
                    onNavSelect = { screen -> navigateTab(screen.route) },
                )
            }
        }
    }
}

@Composable
private fun NorthstarBottomNav(
    tabs: List<NavTab>,
    currentRoute: String?,
    onNavSelect: (Screen) -> Unit,
) {
    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .shadow(16.dp, RoundedCornerShape(percent = 50), clip = false)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xF2222C35))
            .border(1.dp, Line2, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val active = currentRoute == tab.screen.route
            val pillAlpha by animateFloatAsState(
                if (active) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "nav-pill",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onNavSelect(tab.screen) }
                    .padding(horizontal = 3.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(54.dp).height(34.dp)
                        .clip(CircleShape)
                        .background(Brava.copy(alpha = pillAlpha * 0.22f)),
                ) {
                    Icon(
                        tab.icon, contentDescription = tab.label,
                        tint = if (active) BravaBright else TextLo,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Text(
                    tab.label,
                    color = if (active) BravaBright else TextLo,
                    fontSize = 10.5.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = GeistFamily,
                )
            }
        }
    }
}
