package com.example.northstar.dash.nav

import android.content.Context
import android.util.Log
import com.example.northstar.data.RoutePrefs

/** What actually served a routing request — surfaced in the planning UI. */
data class RoutingOutcome(
    val routes: List<Route>,
    /** Engine that produced [routes]: "TomTom" (traffic-aware) or "OSRM" (free-flow). */
    val engine: String,
    /** Human alert when the preferred engine failed and we fell back (null = all good). */
    val notice: String? = null,
)

/**
 * Single entry point for route computation. Uses the engine the rider selected in
 * Settings — TomTom (traffic-aware, free key) or OSRM (free, no setup) — and always
 * falls back to OSRM if the preferred engine fails, so routing can never be down.
 */
object RoutingHub {
    private const val TAG = "RoutingHub"
    const val ENGINE_LABEL_TOMTOM = "TomTom"
    const val ENGINE_LABEL_OSRM = "OSRM"

    suspend fun routesDetailed(context: Context, from: GeoPoint, to: GeoPoint): RoutingOutcome {
        if (RoutePrefs.engine(context) == RoutePrefs.ENGINE_TOMTOM) {
            val key = RoutePrefs.tomtomKey(context)
            if (key != null) {
                val r = TomTomRouter.routes(from, to, key)
                if (r.isNotEmpty()) return RoutingOutcome(r, ENGINE_LABEL_TOMTOM)
                val code = TomTomRouter.lastFailureCode
                val notice = when (code) {
                    403, 429 -> "TomTom daily limit reached — routes from OSRM (no live traffic)"
                    -1       -> "TomTom unreachable — routes from OSRM (no live traffic)"
                    else     -> "TomTom failed (HTTP $code) — routes from OSRM (no live traffic)"
                }
                Log.w(TAG, notice)
                val fallback = Router.routes(from, to)
                return RoutingOutcome(fallback, ENGINE_LABEL_OSRM, notice)
            }
        }
        return RoutingOutcome(Router.routes(from, to), ENGINE_LABEL_OSRM)
    }

    suspend fun routes(context: Context, from: GeoPoint, to: GeoPoint): List<Route> =
        routesDetailed(context, from, to).routes

    suspend fun route(context: Context, from: GeoPoint, to: GeoPoint): Route? =
        routes(context, from, to).firstOrNull()
}
