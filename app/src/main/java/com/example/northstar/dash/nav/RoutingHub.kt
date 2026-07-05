package com.example.northstar.dash.nav

import android.content.Context
import android.util.Log
import com.example.northstar.data.RoutePrefs

/**
 * Single entry point for route computation. Uses the engine the rider selected in
 * Settings — TomTom (traffic-aware, free key) or OSRM (free, no setup) — and always
 * falls back to OSRM if the preferred engine fails, so routing can never be down.
 */
object RoutingHub {
    private const val TAG = "RoutingHub"

    suspend fun routes(context: Context, from: GeoPoint, to: GeoPoint): List<Route> {
        if (RoutePrefs.engine(context) == RoutePrefs.ENGINE_TOMTOM) {
            val key = RoutePrefs.tomtomKey(context)
            if (key != null) {
                val r = TomTomRouter.routes(from, to, key)
                if (r.isNotEmpty()) return r
                Log.w(TAG, "TomTom returned nothing — falling back to OSRM")
            }
        }
        return Router.routes(from, to)
    }

    suspend fun route(context: Context, from: GeoPoint, to: GeoPoint): Route? =
        routes(context, from, to).firstOrNull()
}
