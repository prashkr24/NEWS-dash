package com.example.northstar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ============================================================================
// WayDash "Health" shape system — Material 3 Expressive.
// Large, soft, confident radii. Cards are containers (28dp), the sheet that
// scrolls over the themed header is extra-round at the top (32dp), stat tiles
// are chunky (24dp), and interactive pills are fully rounded.
// ============================================================================

val ShapeStat     = RoundedCornerShape(28.dp)   // metric / stat tiles
val ShapeContainer = RoundedCornerShape(28.dp)  // standard content card
val ShapeContainerLg = RoundedCornerShape(32.dp) // hero / large container
val ShapeSheetTop = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
val ShapeField    = RoundedCornerShape(20.dp)
val ShapeInner    = RoundedCornerShape(20.dp)    // nested rows inside a card
val ShapePillSm   = RoundedCornerShape(percent = 50)
