package com.example.northstar

import android.app.Notification
import com.example.northstar.dash.FastReconnectManager
import com.example.northstar.dash.nav.FlyoverDetector
import com.example.northstar.dash.nav.FlyoverRouteEnricher
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.Maneuver
import com.example.northstar.dash.nav.ManeuverType
import com.example.northstar.dash.nav.Route
import com.example.northstar.media.NorthstarNotificationListener
import com.example.northstar.media.NorthstarNotificationListener.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashCallAndFlyoverTest {

    @Test
    fun whatsappIncomingCallWithoutCallStyle_isDetectedAsIncoming() {
        // Real Android 12+ bug: WhatsApp custom call notifications often omit CallStyle
        // so EXTRA_CALL_TYPE defaults to 0.
        val state = NorthstarNotificationListener.classifyCallState(
            callTypeExtra = 0,
            combinedTextLower = "incoming voice call",
            actionTitlesLower = listOf("decline", "answer"),
            hasFullScreenIntent = true,
            flags = Notification.FLAG_ONGOING_EVENT,
            isCategoryCall = true,
        )
        assertEquals(CallState.INCOMING, state)
    }

    @Test
    fun whatsappIncomingVideoCallWithFullScreenIntentOnly_isDetectedAsIncoming() {
        val state = NorthstarNotificationListener.classifyCallState(
            callTypeExtra = 0,
            combinedTextLower = "whatsapp video",
            actionTitlesLower = emptyList(),
            hasFullScreenIntent = true,
            flags = Notification.FLAG_ONGOING_EVENT,
            isCategoryCall = false,
        )
        assertEquals(CallState.INCOMING, state)
    }

    @Test
    fun whatsappOngoingAndMissedCalls_doNotShowIncomingBanner() {
        val ongoing = NorthstarNotificationListener.classifyCallState(
            callTypeExtra = 0,
            combinedTextLower = "ongoing voice call 00:42",
            actionTitlesLower = listOf("hang up"),
            hasFullScreenIntent = false,
            flags = Notification.FLAG_ONGOING_EVENT,
            isCategoryCall = true,
        )
        assertEquals(CallState.ONGOING_OR_OUTGOING, ongoing)

        val missed = NorthstarNotificationListener.classifyCallState(
            callTypeExtra = 0,
            combinedTextLower = "missed voice call",
            actionTitlesLower = listOf("call back", "message"),
            hasFullScreenIntent = false,
            flags = 0,
            isCategoryCall = true,
        )
        assertEquals(CallState.ENDED_OR_MISSED, missed)
    }

    @Test
    fun flyoverEnricher_detectsUnnamedStraightFlyoverAndIgnoresCrossBridge() {
        // 1 km due-east corridor along Outer Ring Road (no "Flyover" in road name, no turn steps)
        val pts = (0..20).map { i -> GeoPoint(12.9200, 77.6400 + i * 0.00045) } // ~50 m per vertex -> ~1000 m
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(pts[i - 1], pts[i])
        val baseRoute = Route(
            geometry = pts,
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, "Head out on Outer Ring Road", pts.first(), 0.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", pts.last(), cum.last()),
            ),
            totalMeters = cum.last(),
            totalSeconds = 90.0,
            cumulative = cum,
        )

        val tileKey = FlyoverRouteEnricher.latLngToTile(12.9200, 77.6430, 14)
        // Aligned 250 m bridge from ~350 m to ~600 m along the corridor
        val alignedBridge = FlyoverRouteEnricher.BrunnelSeg(
            kind = FlyoverRouteEnricher.Kind.BRIDGE,
            majorRoad = true,
            minLat = 12.9196, maxLat = 12.9204,
            minLng = 77.6428, maxLng = 77.6460,
            aLat = 12.9200, aLng = 77.64315,
            bLat = 12.9200, bLng = 77.64540,
            bearingDeg = 90f,
        )
        // Perpendicular overpass crossing north-south at ~200 m (must NOT trigger!)
        val crossBridge = FlyoverRouteEnricher.BrunnelSeg(
            kind = FlyoverRouteEnricher.Kind.BRIDGE,
            majorRoad = true,
            minLat = 12.9190, maxLat = 12.9210,
            minLng = 77.6414, maxLng = 77.6422,
            aLat = 12.9192, aLng = 77.6418,
            bLat = 12.9208, bLng = 77.6418,
            bearingDeg = 0f,
        )

        val enriched = FlyoverRouteEnricher.enrichSingleRoute(
            baseRoute,
            mapOf(tileKey to listOf(crossBridge, alignedBridge)),
        )

        val flyovers = enriched.maneuvers.filter { it.flyover }
        assertEquals(1, flyovers.size)
        assertEquals("Take the flyover", flyovers[0].instruction)
        // Anchored ~65 m before the bridge start (~343 m -> ~278 m)
        assertTrue("Expected anchor around 240..320 m, got ${flyovers[0].cumulativeMeters}",
            flyovers[0].cumulativeMeters in 240.0..320.0)
    }

    @Test
    fun flyoverEnricher_detectsServiceRoadAlongsideParallelFlyover() {
        // Ground-level route running 16 m north (left in LHT eastbound) of an elevated flyover
        val pts = (0..20).map { i -> GeoPoint(12.920145, 77.6400 + i * 0.00045) }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(pts[i - 1], pts[i])
        val baseRoute = Route(
            geometry = pts,
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, "Head out", pts.first(), 0.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", pts.last(), cum.last()),
            ),
            totalMeters = cum.last(),
            totalSeconds = 90.0,
            cumulative = cum,
        )
        val tileKey = FlyoverRouteEnricher.latLngToTile(12.9200, 77.6430, 14)
        val parallelFlyover = FlyoverRouteEnricher.BrunnelSeg(
            kind = FlyoverRouteEnricher.Kind.BRIDGE,
            majorRoad = true,
            minLat = 12.9196, maxLat = 12.9205,
            minLng = 77.6425, maxLng = 77.6462,
            aLat = 12.9200, aLng = 77.6428,
            bLat = 12.9200, bLng = 77.6458,
            bearingDeg = 90f,
        )

        val enriched = FlyoverRouteEnricher.enrichSingleRoute(
            baseRoute,
            mapOf(tileKey to listOf(parallelFlyover)),
        )
        val elevated = enriched.maneuvers.filter { it.flyover }
        assertEquals(1, elevated.size)
        assertEquals("Keep to service road", elevated[0].instruction)
        assertFalse(elevated[0].instruction.contains("Take the flyover"))
    }

    @Test
    fun flyoverDetector_textPatterns() {
        assertEquals("Take the flyover", FlyoverDetector.hint("Hebbal ROB"))
        assertEquals("Keep to the underpass", FlyoverDetector.hint("Kundalahalli Underpass"))
        assertEquals("Keep to service road", FlyoverDetector.hint("Outer Ring Road Service Rd"))
    }

    @Test
    fun pocketAutoConnect_bootScheduleAndScanRateLimiterStayWithinAndroidQuota() {
        val checks = FastReconnectManager.buildPostIgnitionCheckDelaysSec()
        val scans = FastReconnectManager.buildWifiScanTriggerDelaysSec()

        // Must span past 30s to catch Tripper Dash Linux cold boot (~15-30s after ignition)
        assertTrue(checks.last() >= 40L)
        // Android allows at most 4 background startScan() calls per 2-minute window
        assertTrue(scans.size <= 4)

        val history = mutableListOf<Long>()
        val t0 = 1_000_000L
        for (sec in scans) {
            assertTrue(
                "Expected scan at +${sec}s to be permitted",
                FastReconnectManager.canTriggerBackgroundWifiScan(t0 + sec * 1_000L, history),
            )
        }
        // 5th scan within 2 minutes must be throttled
        assertFalse(FastReconnectManager.canTriggerBackgroundWifiScan(t0 + 40_000L, history))
        // After 126s window expires, scans are permitted again
        assertTrue(FastReconnectManager.canTriggerBackgroundWifiScan(t0 + 130_000L, history))
    }

    @Test
    fun pocketAutoConnect_cooldownGatesRespectManualDisconnectAndForegroundOverride() {
        val now = 500_000L
        // Manual disconnect blocks even foreground auto-trigger within 120s
        assertFalse(
            FastReconnectManager.shouldAllowAutoConnect(
                wirelessAutoEnabled = true,
                nowMs = now,
                lastManualDisconnectMs = now - 30_000L,
                lastSessionEndMs = 0L,
                lastAutoTriggerMs = 0L,
                fromForeground = true,
                forScanResult = false,
                minTriggerGapMs = 3_000L,
            )
        )
        // Fresh pocket trigger with no prior session end is allowed
        assertTrue(
            FastReconnectManager.shouldAllowAutoConnect(
                wirelessAutoEnabled = true,
                nowMs = now,
                lastManualDisconnectMs = 0L,
                lastSessionEndMs = 0L,
                lastAutoTriggerMs = 0L,
                fromForeground = false,
                forScanResult = true,
                minTriggerGapMs = 10_000L,
            )
        )
        // Post-ride session cooldown blocks cached background scan within 45s, but foreground bypasses it
        assertFalse(
            FastReconnectManager.shouldAllowAutoConnect(
                wirelessAutoEnabled = true,
                nowMs = now,
                lastManualDisconnectMs = 0L,
                lastSessionEndMs = now - 20_000L,
                lastAutoTriggerMs = 0L,
                fromForeground = false,
                forScanResult = true,
                minTriggerGapMs = 10_000L,
            )
        )
        assertTrue(
            FastReconnectManager.shouldAllowAutoConnect(
                wirelessAutoEnabled = true,
                nowMs = now,
                lastManualDisconnectMs = 0L,
                lastSessionEndMs = now - 20_000L,
                lastAutoTriggerMs = 0L,
                fromForeground = true,
                forScanResult = true,
                minTriggerGapMs = 10_000L,
            )
        )
    }
}
