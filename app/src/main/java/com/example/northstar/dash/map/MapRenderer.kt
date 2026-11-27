package com.example.northstar.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.ManeuverType
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Draws the navigation frame for the Tripper Dash (526 × 300).
 *
 * Hardware geometry (verified on-bike): the Tripper TFT is a **526 × 526 round display**
 * (centre `(263, 263)`, radius `263`), where our `526 × 300` video occupies the **upper dome**
 * (`y = 0..300`) directly above the firmware's native gold banner (`y = 300..526`), and the
 * firmware composites its own turn-by-turn circle over the lower-left (`cx ≈ 83, cy ≈ 221, r ≈ 67`).
 *
 * Every screen-space HUD element is constrained inside `(x - 263)² + (y - 263)² ≤ (263 - inset)²`
 * so nothing is ever clipped by the physical round bezel or collides with the rider arrow.
 */
class MapRenderer(private val tiles: TileProvider) {

    data class Frame(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Int,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val headingUp: Boolean = false,
        val heading: Float = 0f,           // travel bearing, degrees
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val destName: String? = null,
        val route: List<GeoPoint> = emptyList(),
        val routeSplitIndex: Int = 0,      // segment index where rider projects onto route
        val routeSplitPoint: GeoPoint? = null, // exact projected rider point on route (dims passed tail)
        val maneuverText: String? = null,  // e.g. "Left · 120 m" or "Take the flyover · 250 m"
        val maneuverType: ManeuverType? = null,
        val maneuverFlyover: Boolean = false,
        val alertTitle: String? = null,    // e.g. "Prashant (WhatsApp)"
        val alertLine: String? = null,     // e.g. "reaching in 10 min"
        val alertBadge: Int = 0,           // unread count for the corner bubble (0 = hide)
        val remainingText: String? = null, // e.g. "186 km"
        val tilt3d: Boolean = false,       // perspective 3D view (nav heading-up only)
        val etaPrimary: String? = null,    // big glance value, e.g. "24 min" (nav only)
        val etaSecondary: String? = null,  // smaller line, e.g. "8:09 pm"
        val gpsWeak: Boolean = false,      // imprecise/slow fix → amber marker + "GPS weak" pill
        val gpsLost: Boolean = false,      // no fresh fix → grey marker + "GPS lost" pill
        val reservedTopCenterBottom: Float = 0f, // >0 when an external top-centre overlay (call card) is active
    )

    private val bgColor     = Color.rgb(229, 227, 223) // Google Maps land colour, behind missing tiles
    private val routeBlue   = Color.rgb(46, 124, 246)  // Vivid navigation blue (#2E7CF6)
    private val routeBorder = Color.rgb(23, 78, 166)   // Deep cobalt casing (#174EA6) — pops off white roads in sunlight
    private val arrowBlue   = Color.rgb(26, 115, 232)  // Crisp rider chevron blue (#1A73E8)
    private val googleRed   = Color.rgb(234, 67, 53)   // Google destination pin red (#EA4335)

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Gentle saturation + midpoint contrast lift tuned for the matte Tripper TFT in daylight:
        // keeps minor white streets distinct from pale land without clipping bright highlights.
        val sat = ColorMatrix().apply { setSaturation(1.28f) }
        val c = 1.06f
        val t = (1f - c) * 128f - 2f
        val contrast = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        sat.postConcat(contrast)
        colorFilter = ColorMatrixColorFilter(sat)
    }

    // Active upcoming route (deep cobalt border + bright blue core so it never washes into white streets)
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeBorder; style = Paint.Style.STROKE
        strokeWidth = 13f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeBlue; style = Paint.Style.STROKE
        strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // Already-traveled route tail behind the rider — muted so loops/intersections stay unambiguous
    private val passedCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE
        strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val passedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(142, 154, 172); style = Paint.Style.STROKE
        strokeWidth = 5.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // Screen-space white direction chevrons along the upcoming route ribbon
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255); style = Paint.Style.STROKE
        strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val puckRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 15, 23, 42); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val pinBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
        strokeJoin = Paint.Join.ROUND
    }
    private val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 64, 67); textSize = 20f; isFakeBoldText = true
    }

    // ETA / time pill (upper-right dome arc — well clear of the rider direction arrow)
    private val etaBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 15, 19, 26) }
    private val etaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(68, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val etaBigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 231, 85); textSize = 18.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val etaSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 226, 236); textSize = 11.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    // GPS-health pill (top-centre, shown only when the signal is weak/lost)
    private val gpsPillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    // Turn / flyover guidance chip (top-centre dome slot)
    private val flyoverBgPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 183, 64) }
    private val turnChipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 15, 22, 34) }
    private val turnChipBorder  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; color = Color.rgb(46, 124, 246)
    }
    private val turnIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val flyoverText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f; isFakeBoldText = true; textAlign = Paint.Align.LEFT
    }

    // Message-alert card (sender + snippet) — dark card, Brava edge, readable at a glance.
    private val alertBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 16, 26, 40) }
    private val alertBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(46, 124, 246)
    }
    private val alertTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f; isFakeBoldText = true; color = Color.rgb(239, 243, 250); textAlign = Paint.Align.LEFT
    }
    private val alertLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f; color = Color.rgb(165, 180, 202); textAlign = Paint.Align.LEFT
    }
    private val alertAvatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 124, 246) }
    private val alertAvatarText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f; isFakeBoldText = true; color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val alertBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f; isFakeBoldText = true; color = Color.WHITE; textAlign = Paint.Align.CENTER
    }

    // Provider attribution (bottom-right, inset inside the circular bezel).
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f; color = Color.WHITE; textAlign = Paint.Align.RIGHT
        setShadowLayer(2.5f, 0f, 1f, Color.argb(215, 0, 0, 0))
    }

    // Reused across frames (zero per-frame heap churn)
    private val routePath = Path()
    private val passedPath = Path()
    private val riderPath = Path()
    private val pinPath = Path()
    private val iconPath = Path()
    private val tmpRect = RectF()
    private val pillRect = RectF()
    private val tileSrc = Rect()      // sub-region of an ancestor tile when scaling up
    private val childDst = RectF()    // quadrant target when scaling children down
    private val textBounds = Rect()
    private val tiltMatrix = Matrix()
    private val worldMatrix = Matrix()
    private val tiltSrc = FloatArray(8)
    private val tiltDst = FloatArray(8)
    private val ptBuf = FloatArray(4)

    /**
     * Renders a full dash frame. With [skipBaseMap] the caller has already drawn the base
     * map (the experimental MapLibre 3D snapshot, heading + tilt baked in), so this pass
     * skips the background, canvas rotation, tiles, and geo-anchored route/destination —
     * drawing only a screen-anchored rider direction arrow and the screen-space HUD.
     */
    fun draw(canvas: Canvas, f: Frame, skipBaseMap: Boolean = false) {
        val w = canvas.width
        val h = canvas.height
        if (!skipBaseMap) canvas.drawColor(bgColor)

        val rotate = f.headingUp
        val tilt = rotate && f.tilt3d
        // Nav view: anchor the rider in the lower-centre (72% down; ~228 px on screen under 3D
        // tilt) so oncoming roads fill the dome ahead while keeping immediate junction context
        // directly below the arrow. North-up sits just below vertical centre to clear top chips.
        val pivotY = if (rotate) h * 0.72f else h * 0.52f

        val ts = Mercator.TILE_SIZE
        val cx = Mercator.lngToTileX(f.centerLng, f.zoom) * ts + f.panX
        val cy = Mercator.latToTileY(f.centerLat, f.zoom) * ts + f.panY
        val left = cx - w / 2.0
        val top  = cy - pivotY

        fun sx(lng: Double) = (Mercator.lngToTileX(lng, f.zoom) * ts - left).toFloat()
        fun sy(lat: Double) = (Mercator.latToTileY(lat, f.zoom) * ts - top).toFloat()

        worldMatrix.reset()
        if (rotate && !skipBaseMap) {
            worldMatrix.setRotate(-f.heading, w / 2f, pivotY)
            if (tilt) {
                // Perspective tilt: projects a forward-expanding trapezoid of the road
                // ahead onto the screen, so oncoming curves and junctions recede into
                // the distance (Google Maps / motorcycle GPS navigation view).
                val wNear = w.toFloat()
                val wFar = w * 2.25f
                val aheadReach = h * 2.1f
                val yFar = pivotY - aheadReach
                val yNear = h.toFloat()

                tiltSrc[0] = (w / 2f) - (wFar / 2f);  tiltSrc[1] = yFar
                tiltSrc[2] = (w / 2f) + (wFar / 2f);  tiltSrc[3] = yFar
                tiltSrc[4] = (w / 2f) + (wNear / 2f); tiltSrc[5] = yNear
                tiltSrc[6] = (w / 2f) - (wNear / 2f); tiltSrc[7] = yNear

                tiltDst[0] = 0f;          tiltDst[1] = 0f
                tiltDst[2] = w.toFloat(); tiltDst[3] = 0f
                tiltDst[4] = w.toFloat(); tiltDst[5] = h.toFloat()
                tiltDst[6] = 0f;          tiltDst[7] = h.toFloat()

                tiltMatrix.setPolyToPoly(tiltSrc, 0, tiltDst, 0, 4)
                worldMatrix.postConcat(tiltMatrix)
            }
            canvas.save()
            canvas.concat(worldMatrix)
        }

        // ── Tiles + Road route polyline (world space) ──
        if (!skipBaseMap) {
            val pad = if (tilt) (maxOf(w, h) * 1.55).toInt() else if (rotate) (maxOf(w, h) * 0.45).toInt() else 0
            val txMin = Math.floorDiv((left - pad).toInt(), ts)
            val tyMin = Math.floorDiv((top - pad).toInt(), ts)
            val txMax = Math.floorDiv((left + w + pad).toInt(), ts)
            val tyMax = Math.floorDiv((top + h + pad).toInt(), ts)
            for (tx in txMin..txMax) for (ty in tyMin..tyMax) {
                val dstL = (tx * ts - left).toFloat()
                val dstT = (ty * ts - top).toFloat()
                tmpRect.set(dstL, dstT, dstL + ts, dstT + ts)
                drawTileBestEffort(canvas, f.zoom, tx, ty, tmpRect)
            }

            if (f.route.size >= 2) {
                val splitPt = f.routeSplitPoint
                val splitIdx = f.routeSplitIndex.coerceIn(0, f.route.size - 2)
                if (splitPt != null) {
                    // Traveled tail behind the rider (muted grey)
                    passedPath.reset()
                    passedPath.moveTo(sx(f.route[0].lng), sy(f.route[0].lat))
                    for (i in 1..splitIdx) passedPath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
                    passedPath.lineTo(sx(splitPt.lng), sy(splitPt.lat))
                    canvas.drawPath(passedPath, passedCasing)
                    canvas.drawPath(passedPath, passedPaint)

                    // Active route ahead of the rider (deep cobalt border + vivid blue core)
                    routePath.reset()
                    routePath.moveTo(sx(splitPt.lng), sy(splitPt.lat))
                    for (i in (splitIdx + 1) until f.route.size) {
                        routePath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
                    }
                    canvas.drawPath(routePath, routeCasing)
                    canvas.drawPath(routePath, routePaint)
                } else {
                    routePath.reset()
                    routePath.moveTo(sx(f.route[0].lng), sy(f.route[0].lat))
                    for (i in 1 until f.route.size) routePath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
                    canvas.drawPath(routePath, routeCasing)
                    canvas.drawPath(routePath, routePaint)
                }
            }
        }

        if (rotate && !skipBaseMap) canvas.restore()

        // ── Screen-space route direction chevrons (crisp & un-squashed under 3D tilt) ──
        if (!skipBaseMap && f.route.size >= 2) {
            drawRouteChevrons(canvas, w, h, f, ::sx, ::sy)
        }

        // ── Destination pin (upright in screen space so 3D tilt never flattens it) ──
        if (!skipBaseMap && f.destLat != null && f.destLng != null) {
            ptBuf[0] = sx(f.destLng); ptBuf[1] = sy(f.destLat)
            worldMatrix.mapPoints(ptBuf, 0, ptBuf, 0, 1)
            val dx = ptBuf[0]; val dy = ptBuf[1]
            if (dx in -24f..(w + 24f) && dy in -24f..(h + 24f)) {
                drawDestinationPin(canvas, dx, dy)
            }
        }

        // ── Rider direction arrow puck (screen space: always crisp, un-warped, never covered) ──
        if (f.riderLat != null && f.riderLng != null) {
            val rxS: Float
            val ryS: Float
            if (skipBaseMap) {
                rxS = w / 2f; ryS = h / 2f
            } else {
                ptBuf[0] = sx(f.riderLng); ptBuf[1] = sy(f.riderLat)
                worldMatrix.mapPoints(ptBuf, 0, ptBuf, 0, 1)
                rxS = ptBuf[0]; ryS = ptBuf[1]
            }
            val markerColor = when {
                f.gpsLost -> Color.rgb(142, 149, 158)  // slate grey: stale position
                f.gpsWeak -> Color.rgb(245, 158, 11)   // vivid amber: imprecise
                else      -> arrowBlue                 // nav blue: good fix
            }
            val arrowDeg = if (rotate || skipBaseMap) 0f else f.heading
            drawRiderArrowPuck(canvas, rxS, ryS, arrowDeg, markerColor)
        }

        // ── Top-centre HUD stack: GPS health pill + upcoming Turn / Flyover chip ──
        var topCenterBottom = f.reservedTopCenterBottom
        if (f.gpsLost || f.gpsWeak) {
            val label = if (f.gpsLost) "GPS lost" else "GPS weak"
            gpsPillText.color = if (f.gpsLost) googleRed else Color.rgb(251, 188, 5)
            val padH = 13f; val padV = 5.5f
            val fm = gpsPillText.fontMetrics
            val textH = fm.descent - fm.ascent
            val pillH = padV * 2 + textH
            val pillT = if (topCenterBottom > 0f) topCenterBottom + 6f else 18f
            val maxW = (capsuleHalfSpan(w, pillT, pillH, inset = 15f) * 2f).coerceAtLeast(80f)
            val pillW = (gpsPillText.measureText(label) + padH * 2).coerceAtMost(maxW)
            val cxp = w / 2f
            pillRect.set(cxp - pillW / 2f, pillT, cxp + pillW / 2f, pillT + pillH)
            val r = pillH / 2f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            canvas.drawText(label, cxp, pillT + padV - fm.ascent, gpsPillText)
            topCenterBottom = pillRect.bottom
        }

        // ── Turn / flyover guidance chip (top-centre safe arc, with vector turn arrow) ──
        f.maneuverText?.let { rawLabel ->
            val isFlyover = f.maneuverFlyover
            val padH = 13f; val padV = 6.5f
            val fm = flyoverText.fontMetrics
            val textH = fm.descent - fm.ascent
            val pillH = padV * 2 + textH
            val pillT = if (topCenterBottom > 0f) topCenterBottom + 6f else 22f
            val maxW = (capsuleHalfSpan(w, pillT, pillH, inset = 15f) * 2f).coerceAtLeast(110f)
            val hasIcon = f.maneuverType != null || isFlyover
            val iconSlot = if (hasIcon) 20f else 0f
            val label = ellipsizeToWidth(rawLabel, flyoverText, maxW - padH * 2 - iconSlot)
            val textW = flyoverText.measureText(label)
            val pillW = (textW + iconSlot + padH * 2).coerceAtMost(maxW)
            val cxp = w / 2f
            pillRect.set(cxp - pillW / 2f, pillT, cxp + pillW / 2f, pillT + pillH)
            val r = pillH / 2f
            if (isFlyover) {
                flyoverText.color = Color.rgb(20, 16, 4)
                turnIconPaint.color = Color.rgb(20, 16, 4)
                canvas.drawRoundRect(pillRect, r, r, flyoverBgPaint)
            } else {
                flyoverText.color = Color.WHITE
                turnIconPaint.color = Color.rgb(96, 165, 250)
                canvas.drawRoundRect(pillRect, r, r, turnChipBgPaint)
                canvas.drawRoundRect(pillRect, r, r, turnChipBorder)
            }
            var textX = pillRect.left + padH
            if (hasIcon) {
                val icx = textX + 7f
                val icy = pillRect.centerY()
                drawManeuverGlyph(canvas, icx, icy, f.maneuverType ?: ManeuverType.CONTINUE, isFlyover, rawLabel)
                textX += iconSlot
            }
            canvas.drawText(label, textX, pillT + padV - fm.ascent, flyoverText)
            topCenterBottom = pillRect.bottom
        }

        // ── Message-alert bubble (sender + snippet) — clamped inside the round dome bezel ──
        if (f.alertTitle != null && f.reservedTopCenterBottom <= 0f) {
            val title = f.alertTitle
            val line = f.alertLine.orEmpty()
            val bubT = if (topCenterBottom > 0f) topCenterBottom + 7f else 36f
            val tfm = alertTitlePaint.fontMetrics
            val lfm = alertLinePaint.fontMetrics
            val titleH = tfm.descent - tfm.ascent
            val lineH = if (line.isNotBlank()) lfm.descent - lfm.ascent else 0f
            val padH = 12f; val padV = 7.5f; val gap = if (line.isNotBlank()) 2.5f else 0f
            val bubH = padV * 2 + titleH + gap + lineH
            val avR = 15f
            val safeHalf = domeHalfChord(w, bubT + 8f, inset = 16f)
            val maxTotalW = (safeHalf * 2f).coerceAtMost(w * 0.72f)
            val maxTextW = (maxTotalW - (avR * 2f + 7f + padH * 2)).coerceAtLeast(60f)
            val safeTitle = ellipsizeToWidth(title, alertTitlePaint, maxTextW)
            val safeLine = if (line.isNotBlank()) ellipsizeToWidth(line, alertLinePaint, maxTextW) else ""
            val textW = maxOf(
                alertTitlePaint.measureText(safeTitle),
                if (safeLine.isNotBlank()) alertLinePaint.measureText(safeLine) else 0f,
            )
            val totalW = avR * 2f + 7f + textW + padH * 2
            val startX = (w / 2f) - totalW / 2f
            val avCx = startX + avR
            val avCy = bubT + bubH / 2f
            canvas.drawCircle(avCx, avCy, avR, alertAvatarPaint)
            val initial = title.trim().take(1).uppercase().ifBlank { "•" }
            val afm = alertAvatarText.fontMetrics
            canvas.drawText(initial, avCx, avCy - (afm.ascent + afm.descent) / 2f, alertAvatarText)

            val bubL = avCx + avR + 7f
            val bubR = bubL + textW + padH * 2
            pillRect.set(bubL, bubT, bubR, bubT + bubH)
            canvas.drawRoundRect(pillRect, 14f, 14f, alertBgPaint)
            canvas.drawRoundRect(pillRect, 14f, 14f, alertBorderPaint)
            canvas.drawText(safeTitle, bubL + padH, bubT + padV - tfm.ascent, alertTitlePaint)
            if (safeLine.isNotBlank()) {
                canvas.drawText(safeLine, bubL + padH, bubT + padV + titleH + gap - lfm.ascent, alertLinePaint)
            }
            topCenterBottom = pillRect.bottom
        }

        // ── ETA / Time pill (upper-right dome arc — hugs the round bezel, completely clear
        //    of the central direction arrow & road ahead) ──
        f.etaPrimary?.let { primary ->
            val secondary = f.etaSecondary
            val isRecalc = primary.startsWith("Recalc", ignoreCase = true)
            etaBigPaint.color = if (isRecalc) Color.rgb(244, 183, 64) else Color.rgb(110, 231, 85)
            val padH = 14f; val padV = 6.5f; val gap = 1.5f
            val bigFm = etaBigPaint.fontMetrics
            val smallFm = etaSmallPaint.fontMetrics
            val bigH = bigFm.descent - bigFm.ascent
            val smallH = if (secondary != null) smallFm.descent - smallFm.ascent else 0f
            val contentW = maxOf(
                etaBigPaint.measureText(primary),
                secondary?.let { etaSmallPaint.measureText(it) } ?: 0f,
            )
            val pillW = (contentW + padH * 2).coerceAtMost(w * 0.42f)
            val pillH = padV * 2 + bigH + (if (secondary != null) gap + smallH else 0f)
            // Sit comfortably along the upper-right bezel curve; step down cleanly if a stacked
            // banner / incoming-call card occupies the upper band.
            val etaTop = maxOf(82f, topCenterBottom + 8f).coerceAtMost(h - pillH - 22f)
            val pillRight = capsuleMaxRight(w, etaTop, pillH, inset = 15f)
            val pillLeft = pillRight - pillW
            val cxp = (pillLeft + pillRight) / 2f
            pillRect.set(pillLeft, etaTop, pillRight, etaTop + pillH)
            val r = pillH / 2f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            var baseline = etaTop + padV - bigFm.ascent
            canvas.drawText(primary, cxp, baseline, etaBigPaint)
            if (secondary != null) {
                baseline += bigFm.descent + gap - smallFm.ascent
                canvas.drawText(secondary, cxp, baseline, etaSmallPaint)
            }
        }

        // ── Unread message badge (upper-left dome arc, inside bezel & above native turn circle) ──
        if (f.alertBadge > 0) {
            val badgeR = 13f
            val badgeTop = 80f
            val badgeLeft = capsuleMinLeft(w, badgeTop, badgeR * 2f, inset = 16f)
            val bx = badgeLeft + badgeR
            val by = badgeTop + badgeR
            canvas.drawCircle(bx, by, badgeR, alertAvatarPaint)
            canvas.drawCircle(bx, by, badgeR, etaBorderPaint)
            val bfm = alertBadgeText.fontMetrics
            val label = if (f.alertBadge > 9) "9+" else f.alertBadge.toString()
            canvas.drawText(label, bx, by - (bfm.ascent + bfm.descent) / 2f, alertBadgeText)
        }

        // ── Provider attribution (bottom-right, inset inside the circular bezel) ──
        canvas.drawText(tiles.source.attribution, w - 16f, h - 7f, attributionPaint)

        // ── Standby when nothing to show (dark text on the light map bg) ──
        if (f.riderLat == null && f.destLat == null) {
            val msg = "WAYDASH · waiting for GPS"
            standbyPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (w - textBounds.width()) / 2f, h / 2f, standbyPaint)
        }
    }

    /** Crisp white puck + sculpted navigation direction arrow, drawn in screen space. */
    private fun drawRiderArrowPuck(
        canvas: Canvas, rx: Float, ry: Float, arrowDeg: Float, markerColor: Int,
    ) {
        val haloColor = Color.argb(54, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
        dotPaint.color = haloColor
        canvas.drawCircle(rx, ry, 22f, dotPaint)

        // Deterministic GPU-friendly drop shadow + white navigation puck
        dotPaint.color = Color.argb(65, 0, 0, 0)
        canvas.drawCircle(rx, ry + 1.8f, 15.2f, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(rx, ry, 14.5f, dotPaint)
        canvas.drawCircle(rx, ry, 14.5f, puckRimPaint)

        // Sculpted notched chevron pointing along travel direction (UP in heading-up mode)
        riderPath.reset()
        riderPath.moveTo(0f, -10.5f)
        riderPath.lineTo(8.0f, 7.5f)
        riderPath.lineTo(0f, 3.5f)
        riderPath.lineTo(-8.0f, 7.5f)
        riderPath.close()

        canvas.save()
        canvas.translate(rx, ry)
        if (arrowDeg != 0f) canvas.rotate(arrowDeg)
        dotPaint.color = markerColor
        canvas.drawPath(riderPath, dotPaint)
        canvas.restore()
    }

    /** Upright Google-style teardrop destination marker anchored at screen point (dx, dy). */
    private fun drawDestinationPin(canvas: Canvas, dx: Float, dy: Float) {
        dotPaint.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(dx, dy, 4f, dotPaint)

        val cy = dy - 15f
        val r = 9.5f
        pinPath.reset()
        pinPath.moveTo(dx, dy)
        pinPath.lineTo(dx - r * 0.82f, cy + r * 0.52f)
        tmpRect.set(dx - r, cy - r, dx + r, cy + r)
        pinPath.arcTo(tmpRect, 148f, 244f, false)
        pinPath.close()

        dotPaint.color = googleRed
        canvas.drawPath(pinPath, dotPaint)
        canvas.drawPath(pinPath, pinBorderPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(dx, cy, 3.6f, dotPaint)
    }

    /** Stamps small directional flow chevrons along the upcoming route ribbon in screen space. */
    private fun drawRouteChevrons(
        canvas: Canvas,
        w: Int,
        h: Int,
        f: Frame,
        sx: (Double) -> Float,
        sy: (Double) -> Float,
    ) {
        val spacingPx = 74f
        var carry = 34f // start ahead of the rider puck so no chevron sits under the puck
        val startIdx = if (f.routeSplitPoint != null) f.routeSplitIndex.coerceIn(0, f.route.size - 2) + 1 else 1
        var prevLat = f.routeSplitPoint?.lat ?: f.route[0].lat
        var prevLng = f.routeSplitPoint?.lng ?: f.route[0].lng

        ptBuf[0] = sx(prevLng); ptBuf[1] = sy(prevLat)
        worldMatrix.mapPoints(ptBuf, 0, ptBuf, 0, 1)
        var x0 = ptBuf[0]; var y0 = ptBuf[1]

        for (i in startIdx until f.route.size) {
            val pt = f.route[i]
            ptBuf[0] = sx(pt.lng); ptBuf[1] = sy(pt.lat)
            worldMatrix.mapPoints(ptBuf, 0, ptBuf, 0, 1)
            val x1 = ptBuf[0]; val y1 = ptBuf[1]
            val dx = x1 - x0; val dy = y1 - y0
            val segLen = hypot(dx, dy)
            if (segLen >= 1f) {
                var d = carry
                val ux = dx / segLen; val uy = dy / segLen
                while (d <= segLen) {
                    val cx = x0 + ux * d
                    val cy = y0 + uy * d
                    if (cx in 18f..(w - 18f) && cy in 18f..(h - 18f)) {
                        val wing = 3.8f
                        // Two 135° wings trailing behind tip (cx + ux*2, cy + uy*2)
                        val tx = cx + ux * 2f; val ty = cy + uy * 2f
                        val bx = cx - ux * 2.4f; val by = cy - uy * 2.4f
                        val px = -uy * wing; val py = ux * wing
                        iconPath.reset()
                        iconPath.moveTo(bx + px, by + py)
                        iconPath.lineTo(tx, ty)
                        iconPath.lineTo(bx - px, by - py)
                        canvas.drawPath(iconPath, chevronPaint)
                    }
                    d += spacingPx
                }
                carry = d - segLen
            }
            x0 = x1; y0 = y1
        }
    }

    /** Pure vector turn-arrow icon inside the guidance chip (no OEM font-glyph dependencies). */
    private fun drawManeuverGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        type: ManeuverType,
        flyover: Boolean,
        labelText: String = "",
    ) {
        iconPath.reset()
        when {
            flyover && labelText.contains("underpass", ignoreCase = true) -> {
                // Downward ramp arrow into underpass
                iconPath.moveTo(cx - 4f, cy - 5f)
                iconPath.lineTo(cx + 3.5f, cy + 5f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 1.5f, cy + 5f)
                iconPath.lineTo(cx + 3.5f, cy + 5f)
                iconPath.lineTo(cx + 3.5f, cy)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            flyover && labelText.contains("service", ignoreCase = true) -> {
                // Slip-lane fork left onto service road
                iconPath.moveTo(cx + 3f, cy + 6f)
                iconPath.lineTo(cx + 3f, cy + 1f)
                iconPath.lineTo(cx - 4f, cy - 5f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 4f, cy - 0.5f)
                iconPath.lineTo(cx - 4f, cy - 5f)
                iconPath.lineTo(cx + 0.5f, cy - 5f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            flyover -> {
                // Upward ramp arrow onto flyover / bridge
                iconPath.moveTo(cx - 4f, cy + 6f)
                iconPath.lineTo(cx + 3f, cy - 5f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 2f, cy - 5f)
                iconPath.lineTo(cx + 3f, cy - 5f)
                iconPath.lineTo(cx + 3.5f, cy)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            type == ManeuverType.TURN_LEFT || type == ManeuverType.SHARP_LEFT -> {
                iconPath.moveTo(cx + 3f, cy + 6f)
                iconPath.lineTo(cx + 3f, cy - 2f)
                iconPath.lineTo(cx - 5f, cy - 2f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 1.5f, cy - 5.5f)
                iconPath.lineTo(cx - 5.5f, cy - 2f)
                iconPath.lineTo(cx - 1.5f, cy + 1.5f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            type == ManeuverType.TURN_RIGHT || type == ManeuverType.SHARP_RIGHT -> {
                iconPath.moveTo(cx - 3f, cy + 6f)
                iconPath.lineTo(cx - 3f, cy - 2f)
                iconPath.lineTo(cx + 5f, cy - 2f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx + 1.5f, cy - 5.5f)
                iconPath.lineTo(cx + 5.5f, cy - 2f)
                iconPath.lineTo(cx + 1.5f, cy + 1.5f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            type == ManeuverType.SLIGHT_LEFT -> {
                iconPath.moveTo(cx + 3f, cy + 6f)
                iconPath.lineTo(cx + 3f, cy + 1f)
                iconPath.lineTo(cx - 4f, cy - 5f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 4f, cy - 0.5f)
                iconPath.lineTo(cx - 4f, cy - 5f)
                iconPath.lineTo(cx + 0.5f, cy - 5f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            type == ManeuverType.SLIGHT_RIGHT -> {
                iconPath.moveTo(cx - 3f, cy + 6f)
                iconPath.lineTo(cx - 3f, cy + 1f)
                iconPath.lineTo(cx + 4f, cy - 5f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 0.5f, cy - 5f)
                iconPath.lineTo(cx + 4f, cy - 5f)
                iconPath.lineTo(cx + 4f, cy - 0.5f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            type == ManeuverType.UTURN -> {
                tmpRect.set(cx - 4.5f, cy - 6f, cx + 4.5f, cy + 2f)
                iconPath.moveTo(cx + 4.5f, cy + 6f)
                iconPath.lineTo(cx + 4.5f, cy - 2f)
                iconPath.arcTo(tmpRect, 0f, -180f, false)
                iconPath.lineTo(cx - 4.5f, cy + 4f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 7.5f, cy + 1f)
                iconPath.lineTo(cx - 4.5f, cy + 4.5f)
                iconPath.lineTo(cx - 1.5f, cy + 1f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
            type == ManeuverType.ROUNDABOUT -> {
                canvas.drawCircle(cx, cy, 4.8f, turnIconPaint)
            }
            type == ManeuverType.ARRIVE -> {
                canvas.drawCircle(cx, cy, 5f, turnIconPaint)
                dotPaint.color = turnIconPaint.color
                canvas.drawCircle(cx, cy, 2f, dotPaint)
            }
            else -> {
                iconPath.moveTo(cx, cy + 6f)
                iconPath.lineTo(cx, cy - 5.5f)
                canvas.drawPath(iconPath, turnIconPaint)
                iconPath.reset()
                iconPath.moveTo(cx - 4f, cy - 1.5f)
                iconPath.lineTo(cx, cy - 5.5f)
                iconPath.lineTo(cx + 4f, cy - 1.5f)
                canvas.drawPath(iconPath, turnIconPaint)
            }
        }
    }

    /** Half-width of the circular dome at vertical coordinate [y], inset by [inset] px. */
    private fun domeHalfChord(w: Int, y: Float, inset: Float = 15f): Float {
        val r = (w / 2f) - inset
        val dy = y - (w / 2f)
        val sq = r * r - dy * dy
        return if (sq > 0f) sqrt(sq) else 0f
    }

    /** Half-span available for a centered capsule of height [pillH] at [top] inside the round bezel. */
    private fun capsuleHalfSpan(w: Int, top: Float, pillH: Float, inset: Float = 15f): Float {
        val rCap = pillH / 2f
        val cy = top + rCap
        val rCenter = (w / 2f) - inset - rCap
        val dy = cy - (w / 2f)
        val sq = rCenter * rCenter - dy * dy
        return if (sq > 0f) sqrt(sq) + rCap else rCap
    }

    /** Rightmost outer edge X for a stadium capsule of height [pillH] at [top] inside the bezel. */
    private fun capsuleMaxRight(w: Int, top: Float, pillH: Float, inset: Float = 15f): Float =
        (w / 2f) + capsuleHalfSpan(w, top, pillH, inset)

    /** Leftmost outer edge X for a stadium capsule of height [pillH] at [top] inside the bezel. */
    private fun capsuleMinLeft(w: Int, top: Float, pillH: Float, inset: Float = 15f): Float =
        (w / 2f) - capsuleHalfSpan(w, top, pillH, inset)

    private fun ellipsizeToWidth(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var len = s.length
        while (len > 3 && paint.measureText(s, 0, len) + 10f > maxWidth) len--
        return s.substring(0, len).trimEnd() + "…"
    }

    /**
     * Draws one map tile, never leaving a blank cell on a cache miss. Slippy-map trick:
     * 1. exact tile if cached (also kicks off the async load);
     * 2. else scale UP the matching sub-region of a cached lower-zoom ancestor — the level
     *    we just left is still in memory, so a zoom-IN renders instantly and only sharpens
     *    once the real tile arrives (no blank flash, which is what made zoom feel slow);
     * 3. else scale DOWN the four cached higher-zoom children into quadrants (zoom-OUT case).
     */
    private fun drawTileBestEffort(canvas: Canvas, z: Int, tx: Int, ty: Int, dst: RectF) {
        tiles.get(z, tx, ty)?.let { canvas.drawBitmap(it, null, dst, tilePaint); return }

        var up = 1
        while (up <= 4 && z - up >= 0) {
            val anc = tiles.getCached(z - up, tx shr up, ty shr up)
            if (anc != null) {
                val cells = 1 shl up
                // Sub-region in the bitmap's OWN pixel space (512 px for hi-DPI tiles, 256 for any
                // legacy tile) — not the logical TILE_SIZE, or it would sample the wrong quadrant.
                val sub = anc.width / cells
                val sxOff = (tx and (cells - 1)) * sub
                val syOff = (ty and (cells - 1)) * sub
                tileSrc.set(sxOff, syOff, sxOff + sub, syOff + sub)
                canvas.drawBitmap(anc, tileSrc, dst, tilePaint)
                return
            }
            up++
        }

        val halfW = (dst.right - dst.left) / 2f
        val halfH = (dst.bottom - dst.top) / 2f
        for (qx in 0..1) for (qy in 0..1) {
            val child = tiles.getCached(z + 1, tx * 2 + qx, ty * 2 + qy) ?: continue
            childDst.set(
                dst.left + qx * halfW, dst.top + qy * halfH,
                dst.left + qx * halfW + halfW, dst.top + qy * halfH + halfH,
            )
            canvas.drawBitmap(child, null, childDst, tilePaint)
        }
        // Deep miss across all levels → the land-colour bg shows through (rare).
    }
}
