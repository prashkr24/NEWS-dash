package com.example.northstar.dash.nav

/**
 * Hands the rider-selected route alternative from the planning screen (RouteViewModel)
 * to navigation (DashViewModel), which otherwise fetches its own route at start time.
 * DashViewModel consumes it only if the destination matches; cleared after use.
 */
object RoutePlanHolder {
    @Volatile var route: Route? = null

    /** The held route, if it ends within [maxMeters] of the requested destination. */
    fun takeIfMatches(destLat: Double, destLng: Double, maxMeters: Double = 150.0): Route? {
        val r = route ?: return null
        val end = r.destination ?: return null
        val d = GeoPoint.distMeters(end, GeoPoint(destLat, destLng))
        return if (d <= maxMeters) { route = null; r } else null
    }
}
