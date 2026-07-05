package com.example.northstar.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// WayDash "Brava" palette — built around the Guerrilla 450 Brava Blue livery
// (white base, mid-blue tank/subframe graphics, black accents). The app reads
// as an instrument cluster: cool ink surfaces, Brava-blue as the live/action
// colour, amber reserved for warning-light semantics only.
// ============================================================================

// ---- Surfaces (cool blue-ink, not warm black) ----
val Bg0    = Color(0xFF0A1220)
val Bg1    = Color(0xFF0D1729)
val Surf1  = Color(0xFF152136)
val Surf2  = Color(0xFF1D2C47)
val Surf3  = Color(0xFF28395A)

// ---- Hairlines ----
val Line   = Color(0x14FFFFFF)
val Line2  = Color(0x22FFFFFF)
val Line3  = Color(0x38FFFFFF)

// ---- Text ----
val TextHi  = Color(0xFFEFF3FA)
val TextMid = Color(0xFF93A3BC)
val TextLo  = Color(0xFF64748F)
val TextDis = Color(0xFF3C4650)

// ---- Accent: Brava Blue (primary action / live state / route) ----
val Brava       = Color(0xFF2E7CF6)   // the tank-graphic blue
val BravaBright = Color(0xFF5B9DFF)   // backlight / pressed / glow highlight
val BravaDeep   = Color(0xFF1D55C4)   // deep shade / gradients
val BravaTint   = Color(0x242E6DF0)   // faint fill
val BravaTint2  = Color(0x3D2E6DF0)   // stronger faint fill
val BravaGlow   = Color(0x592E6DF0)   // glow
val OnBrava     = Color(0xFFF4F8FF)   // text/icon on a Brava-blue fill

// ---- Aliases so existing code referencing Gold* keeps compiling ----
// (Gold is retired as the brand accent; these now point at Brava so any
// not-yet-restyled component still renders on-palette. Warning semantics use
// the dedicated Warn/Alert tokens below.)
val Gold       = Brava
val GoldBright = BravaBright
val GoldDeep   = BravaDeep
val GoldTint   = BravaTint
val GoldTint2  = BravaTint2
val GoldGlow   = BravaGlow
val OnGold     = OnBrava

// ---- Warning-light amber (status only, like a real cluster) ----
val Amber   = Color(0xFFF4B740)
val Warn    = Color(0xFFF4B740)
val Alert   = Color(0xFFE5533D)
val Offline = Color(0xFF5A6673)
val OkGreen = Color(0xFF35C46B)

// ---- Map ----
val MapBase  = Color(0xFF0A111C)
val MapBase2 = Color(0xFF080E18)
