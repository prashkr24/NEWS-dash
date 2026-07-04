package com.example.northstar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// WayDash dark scheme, anchored on Brava Blue as primary. Amber is intentionally
// NOT in the scheme — it's applied locally only for warning-light semantics.
private val WayDashColorScheme = darkColorScheme(
    primary              = Brava,
    onPrimary            = OnBrava,
    primaryContainer     = BravaDeep,
    onPrimaryContainer   = OnBrava,
    inversePrimary       = BravaBright,
    secondary            = BravaBright,
    onSecondary          = Bg0,
    secondaryContainer   = Surf2,
    onSecondaryContainer = TextHi,
    tertiary             = Amber,
    onTertiary           = Bg0,
    background           = Bg0,
    onBackground         = TextHi,
    surface              = Surf1,
    onSurface            = TextHi,
    surfaceVariant       = Surf2,
    onSurfaceVariant     = TextMid,
    surfaceContainer     = Surf1,
    surfaceContainerHigh = Surf2,
    outline              = Line2,
    outlineVariant       = Line,
    error                = Alert,
    onError              = TextHi,
    scrim                = Bg0,
)

@Composable
fun NorthstarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WayDashColorScheme,
        typography = Typography,
        content = content,
    )
}
