package com.example.northstar.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.SharedLocation
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.PlaceSearchEngine
import com.example.northstar.dash.nav.QuickCategory
import com.example.northstar.dash.nav.Route
import com.example.northstar.dash.nav.SearchPlace
import com.example.northstar.util.Dbg
import com.example.northstar.util.LocationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouteState(
    val destination: SharedLocation? = null,
    val isResolving: Boolean = false,
    val pendingNavigate: Boolean = false,
    // Real routing results
    val route: Route? = null,
    val alternatives: List<Route> = emptyList(),
    val selectedRoute: Int = 0,
    /** Which engine served the routes ("TomTom" / "OSRM"); null before first compute. */
    val routingEngine: String? = null,
    /** Alert when the preferred engine failed (e.g. daily limit) and we fell back. */
    val routingNotice: String? = null,
    val routing: Boolean = false,
    val distanceText: String? = null,   // "218 km"
    val durationText: String? = null,   // "4h 50m"
    val etaText: String? = null,        // "13:32"
    // Built-in place search state (WayDash v2)
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchPlace> = emptyList(),
    val activeCategory: QuickCategory? = null,
    val recents: List<SearchPlace> = emptyList(),
    val homePlace: SearchPlace? = null,
    val workPlace: SearchPlace? = null,
)

class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(
        RouteState(
            recents = PlaceSearchEngine.recents(app),
            homePlace = PlaceSearchEngine.getHome(app),
            workPlace = PlaceSearchEngine.getWork(app),
        )
    )
    val state = _state.asStateFlow()

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val repo = com.example.northstar.data.SyncRepository.get(app)
    private var searchJob: Job? = null

    private val _saved = MutableStateFlow<List<com.example.northstar.data.SavedLocation>>(emptyList())
    /** Saved destinations the rider can tap to load + navigate again. */
    val saved = _saved.asStateFlow()

    init {
        reloadSaved()
        // Reflect saves made locally OR synced in from another device.
        viewModelScope.launch { repo.revision.collect { reloadSaved() } }
    }

    private fun reloadSaved() = viewModelScope.launch {
        _saved.value = withContext(Dispatchers.IO) { repo.savedLocations() }
    }

    private fun refreshShortcutsAndRecents() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                recents = PlaceSearchEngine.recents(app),
                homePlace = PlaceSearchEngine.getHome(app),
                workPlace = PlaceSearchEngine.getWork(app),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun currentOrigin(): Pair<Double, Double>? {
        val loc: Location? = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
        if (loc != null) return loc.latitude to loc.longitude
        val dashUi = DashViewModel.getInstance(getApplication()).ui.value
        if (dashUi.riderLat != null && dashUi.riderLng != null) {
            return dashUi.riderLat to dashUi.riderLng
        }
        return null
    }

    // ── Built-in In-App Place & POI Search ──────────────────────────────────

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, activeCategory = null) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(searching = false, searchResults = emptyList()) }
            return
        }
        // If user pasted a Google Maps link directly into the search bar, handle it immediately
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("geo:")) {
            _state.update { it.copy(searching = false, searchResults = emptyList()) }
            handleSharedText(trimmed, autoNavigate = false)
            return
        }
        _state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            delay(280) // debounce typing
            val origin = currentOrigin()
            val results = PlaceSearchEngine.search(
                getApplication(),
                trimmed,
                riderLat = origin?.first,
                riderLng = origin?.second,
            )
            _state.update { it.copy(searching = false, searchResults = results) }
        }
    }

    fun searchQuickCategory(category: QuickCategory) {
        searchJob?.cancel()
        val current = _state.value.activeCategory
        if (current == category) {
            // Toggle off
            _state.update { it.copy(activeCategory = null, searching = false, searchResults = emptyList(), searchQuery = "") }
            return
        }
        _state.update {
            it.copy(
                activeCategory = category,
                searchQuery = category.label,
                searching = true,
            )
        }
        searchJob = viewModelScope.launch {
            val origin = currentOrigin()
            val results = PlaceSearchEngine.search(
                getApplication(),
                category.query,
                riderLat = origin?.first,
                riderLng = origin?.second,
                limit = 10,
            )
            _state.update { it.copy(searching = false, searchResults = results) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = "",
                searching = false,
                searchResults = emptyList(),
                activeCategory = null,
            )
        }
    }

    fun selectSearchPlace(place: SearchPlace) {
        val app = getApplication<Application>()
        PlaceSearchEngine.addRecent(app, place)
        _state.update {
            it.copy(
                destination = SharedLocation(name = place.name, lat = place.lat, lng = place.lng),
                isResolving = false,
                pendingNavigate = false,
                searchQuery = "",
                searching = false,
                searchResults = emptyList(),
                activeCategory = null,
                recents = PlaceSearchEngine.recents(app),
            )
        }
        computeRoute(place.lat, place.lng)
    }

    fun navigateToCoordinates(name: String, lat: Double, lng: Double, subtitle: String = "") {
        selectSearchPlace(
            SearchPlace(
                name = name,
                subtitle = subtitle,
                lat = lat,
                lng = lng,
                category = "Pin",
            )
        )
    }

    fun setHome(place: SearchPlace?) {
        PlaceSearchEngine.setHome(getApplication(), place)
        refreshShortcutsAndRecents()
    }

    fun setWork(place: SearchPlace?) {
        PlaceSearchEngine.setWork(getApplication(), place)
        refreshShortcutsAndRecents()
    }

    fun clearRecents() {
        PlaceSearchEngine.clearRecents(getApplication())
        refreshShortcutsAndRecents()
    }

    /** Save the current resolved destination so it can be reused. No-op without coords. */
    fun saveCurrentDestination(name: String, note: String = "") {
        val d = _state.value.destination ?: return
        val lat = d.lat ?: return
        val lng = d.lng ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addSaved(name.ifBlank { d.name }, lat, lng, note) } }
    }

    fun renameSaved(loc: com.example.northstar.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.renameSaved(loc, name, note) } }

    fun deleteSaved(loc: com.example.northstar.data.SavedLocation) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteSaved(loc) } }

    /** Load a saved destination into the route preview (compute route; stay on the page). */
    fun selectSaved(loc: com.example.northstar.data.SavedLocation) {
        _state.update {
            it.copy(
                destination = SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng),
                isResolving = false,
                pendingNavigate = false,
                searchResults = emptyList(),
                searching = false,
            )
        }
        computeRoute(loc.lat, loc.lng)
    }

    fun handleSharedText(text: String, autoNavigate: Boolean = true) {
        val loc = LocationParser.parse(text)
        _state.update {
            it.copy(
                destination = loc,
                isResolving = loc.needsExpansion,
                pendingNavigate = autoNavigate,
                searchResults = emptyList(),
                searching = false,
            )
        }
        if (loc.lat != null && loc.lng != null) {
            PlaceSearchEngine.addRecent(
                getApplication(),
                SearchPlace(name = loc.name.ifBlank { "Shared Pin" }, subtitle = "Google Maps Link", lat = loc.lat, lng = loc.lng)
            )
            refreshShortcutsAndRecents()
            computeRoute(loc.lat, loc.lng)
        } else if (loc.url != null) {
            viewModelScope.launch {
                val (urlCoords, resolvedName) = LocationParser.resolve(loc.url)
                val name = when {
                    loc.name.isNotBlank() && loc.name != "Loading…" -> loc.name
                    !resolvedName.isNullOrBlank() -> resolvedName
                    urlCoords != null -> "Dropped pin"
                    else -> "Shared location"
                }
                // Named places (e.g. "Third Wave Coffee, … Hyderabad") have no coords in
                // the URL — geocode the resolved address to get lat/lng.
                val coords = urlCoords ?: resolvedName?.let { geocode(it) }
                val resolved = loc.copy(
                    name = name, lat = coords?.first, lng = coords?.second, needsExpansion = false,
                )
                _state.update { it.copy(destination = resolved, isResolving = false) }
                if (coords != null) {
                    PlaceSearchEngine.addRecent(
                        getApplication(),
                        SearchPlace(name = name, subtitle = "Shared from Maps", lat = coords.first, lng = coords.second)
                    )
                    refreshShortcutsAndRecents()
                    computeRoute(coords.first, coords.second)
                } else Log.w(TAG, "No coords for '$name' (url+geocode both empty)")
            }
        }
    }

    /** Geocode an address/place name to coordinates via the device's geocoder backend. */
    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) {
                Log.w(TAG, "No geocoder backend present")
                return@withContext null
            }
            val results = Geocoder(getApplication(), Locale.getDefault()).getFromLocationName(query, 1)
            val a = results?.firstOrNull()
            if (a != null) {
                Dbg.i(TAG) { "Geocoded '$query' → ${a.latitude},${a.longitude}" }
                return@withContext a.latitude to a.longitude
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed: ${e.message}")
        }
        // Fallback to PlaceSearchEngine (TomTom / Photon) when Android Geocoder fails or is empty
        val fallback = PlaceSearchEngine.search(getApplication(), query, limit = 1).firstOrNull()
        fallback?.let { it.lat to it.lng }
    }

    @SuppressLint("MissingPermission")
    fun computeRoute(destLat: Double, destLng: Double) {
        _state.update { it.copy(routing = true, routingNotice = null) }
        viewModelScope.launch {
            var origin = currentOrigin()
            if (origin == null) {
                // Wait briefly in case GPS is still warming up
                repeat(4) {
                    if (origin == null) {
                        delay(400)
                        origin = currentOrigin()
                    }
                }
            }
            val start = origin
            if (start == null) {
                _state.update {
                    it.copy(
                        routing = false,
                        routingNotice = "Waiting for phone GPS fix to calculate route…",
                    )
                }
                return@launch
            }

            val outcome = com.example.northstar.dash.nav.RoutingHub.routesDetailed(
                getApplication(),
                GeoPoint(start.first, start.second),
                GeoPoint(destLat, destLng),
            )
            val alts = outcome.routes
            val r = alts.firstOrNull()
            _state.update { prev ->
                if (r != null) {
                    com.example.northstar.dash.nav.RoutePlanHolder.route = r
                    prev.copy(
                        route = r,
                        alternatives = alts,
                        selectedRoute = 0,
                        routingEngine = outcome.engine,
                        routingNotice = outcome.notice,
                        routing = false,
                        distanceText = fmtDist(r.totalMeters),
                        durationText = fmtDuration(r.totalSeconds),
                        etaText = fmtEta(r.totalSeconds),
                    )
                } else prev.copy(
                    routing = false, alternatives = emptyList(),
                    routingEngine = outcome.engine, routingNotice = outcome.notice,
                )
            }
        }
    }

    /** Rider picked a different route alternative on the planning screen. */
    fun selectRoute(index: Int) {
        val alts = _state.value.alternatives
        val r = alts.getOrNull(index) ?: return
        com.example.northstar.dash.nav.RoutePlanHolder.route = r
        _state.update {
            it.copy(
                route = r,
                selectedRoute = index,
                distanceText = fmtDist(r.totalMeters),
                durationText = fmtDuration(r.totalSeconds),
                etaText = fmtEta(r.totalSeconds),
            )
        }
    }

    fun onNavigated() { _state.update { it.copy(pendingNavigate = false) } }
    fun clear() {
        val app = getApplication<Application>()
        _state.value = RouteState(
            recents = PlaceSearchEngine.recents(app),
            homePlace = PlaceSearchEngine.getHome(app),
            workPlace = PlaceSearchEngine.getWork(app),
        )
    }

    private fun fmtDist(m: Double): String {
        val useKm = com.example.northstar.data.DashPrefs.unitsKm(getApplication())
        return if (useKm) {
            val km = m / 1000.0
            if (km < 10.0) "%.1f km".format(km) else "%.0f km".format(km)
        } else {
            val mi = m / 1609.344
            if (mi < 10.0) "%.1f mi".format(mi) else "%.0f mi".format(mi)
        }
    }
    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }
    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}

