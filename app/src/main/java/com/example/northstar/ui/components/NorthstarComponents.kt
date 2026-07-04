package com.example.northstar.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.ui.theme.*

// ============================================================================
// WayDash component library - Google Health / Fitbit M3 Expressive idiom.
// Public API (names, enums, params) is UNCHANGED so every screen keeps working;
// only the visual language is new: filled tonal containers instead of hairline
// borders, fully-pill buttons, chunky icon tiles, bold emphasized type, springy
// press motion.
// ============================================================================

val CardShape    = ShapeContainer
val BtnShape     = ShapePillSm
val InputShape   = ShapeField
val ChipShape    = CircleShape
val IconBtnShape = RoundedCornerShape(18.dp)

// ---- Card = M3E container: filled surface, no border, generous radius ----
@Composable
fun NorthstarCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bg = if (glow) BravaTint else Surf1
    Column(
        modifier = modifier
            .clip(ShapeContainer)
            .background(bg)
            .then(if (glow) Modifier.border(1.5.dp, BravaTint2, ShapeContainer) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---- Buttons: fully-pill, filled/tonal, springy press ----
enum class BtnVariant { Primary, Secondary, Ghost, Danger }
enum class BtnSize { Lg, Md, Sm }

@Composable
fun NorthstarBtn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: BtnVariant = BtnVariant.Primary,
    size: BtnSize = BtnSize.Md,
    enabled: Boolean = true,
) {
    val (bgColor, fgColor) = when (variant) {
        BtnVariant.Primary   -> Brava to OnBrava
        BtnVariant.Secondary -> Surf2 to TextHi
        BtnVariant.Ghost     -> Color.Transparent to TextMid
        BtnVariant.Danger    -> Alert.copy(alpha = 0.14f) to Alert
    }
    val height = when (size) { BtnSize.Lg -> 62.dp; BtnSize.Md -> 54.dp; BtnSize.Sm -> 44.dp }
    val fontSize = when (size) { BtnSize.Lg -> 16.5.sp; BtnSize.Md -> 15.sp; BtnSize.Sm -> 13.5.sp }
    val iconSize = when (size) { BtnSize.Sm -> 18.dp; else -> 21.dp }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed && enabled) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    val bg = if (enabled) bgColor else Surf2.copy(alpha = 0.5f)
    val fg = if (enabled) fgColor else TextDis

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .scale(pressScale)
            .height(height)
            .clip(ShapePillSm)
            .background(bg)
            .then(if (variant == BtnVariant.Ghost) Modifier.border(1.5.dp, Line2, ShapePillSm) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label, color = fg, fontSize = fontSize, fontWeight = FontWeight.Bold,
            fontFamily = GeistFamily, letterSpacing = 0.sp, maxLines = 1,
        )
    }
}

// ---- Icon button: chunky rounded-square tile ----
@Composable
fun NorthstarIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    active: Boolean = false,
    tint: Color? = null,
) {
    val bg = if (active) Brava else Surf2
    val iconTint = tint ?: if (active) OnBrava else TextMid
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "iconbtn")

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .scale(s)
            .size(size)
            .clip(IconBtnShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(21.dp))
    }
}

// ---- Chip: soft pill ----
enum class ChipTone { Gold, Warn, Alert, Off, Neutral }

@Composable
fun NorthstarChip(
    label: String,
    tone: ChipTone = ChipTone.Neutral,
    dot: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        ChipTone.Gold    -> BravaTint to BravaBright
        ChipTone.Warn    -> Amber.copy(alpha = 0.15f) to Amber
        ChipTone.Alert   -> Alert.copy(alpha = 0.15f) to Alert
        ChipTone.Off     -> Surf2 to TextLo
        ChipTone.Neutral -> Surf2 to TextMid
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 14.dp),
    ) {
        if (dot) Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily, maxLines = 1)
    }
}

// ---- Toggle: M3E-style with springy knob ----
@Composable
fun NorthstarToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val knobOffset by animateFloatAsState(if (on) 22f else 3f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "tgl")
    val track = if (on) Brava else Surf3
    val knob  = if (on) OnBrava else TextMid
    Box(
        modifier = Modifier
            .width(52.dp).height(32.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(start = knobOffset.dp, top = 3.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(knob)
        )
    }
}

// ---- Segmented control: pill track, filled selected pill ----
@Composable
fun NorthstarSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Surf1)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(CircleShape)
                    .background(if (active) Brava else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(opt) },
            ) {
                Text(
                    opt, color = if (active) OnBrava else TextMid,
                    fontSize = 13.5.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = GeistFamily,
                )
            }
        }
    }
}

// ---- Divider ----
@Composable
fun NorthstarDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

// ---- Section label: Health uses bold sentence-case headers ----
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.replaceFirstChar { it.uppercase() },
        color = TextHi,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp,
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

// ---- List row: chunky icon tile + springy press ----
enum class IconTone { Neutral, Gold, Warn, Alert }

@Composable
fun NorthstarRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: IconTone = IconTone.Neutral,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    accentRight: Boolean = false,
    trailingIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val (iconBg, iconFg) = when (iconTone) {
        IconTone.Gold    -> BravaTint to BravaBright
        IconTone.Alert   -> Alert.copy(alpha = 0.14f) to Alert
        IconTone.Warn    -> Amber.copy(alpha = 0.14f) to Amber
        IconTone.Neutral -> Surf2 to TextMid
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(ShapeInner).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 6.dp),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(iconBg),
            ) {
                Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily, letterSpacing = (-0.1).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) Text(sub, color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (right != null || rightSub != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (right != null) {
                    Text(right, color = if (accentRight) BravaBright else TextHi,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                }
                if (rightSub != null) Text(rightSub, color = TextLo, fontSize = 12.sp)
            }
        }
        if (trailingIcon || (onClick != null && right == null)) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextLo, modifier = Modifier.size(20.dp))
        }
    }
}

// ---- Screen header: large bold title ----
@Composable
fun ScreenHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    hint: String? = null,
    wordmark: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .defaultMinSize(minHeight = 46.dp),
    ) {
        if (onBack != null) {
            NorthstarIconBtn(icon = Icons.Outlined.ChevronLeft, onClick = onBack, modifier = Modifier.padding(end = 12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (wordmark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WayDash", color = TextHi, fontFamily = GeistFamily,
                        fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp)
                    Spacer(Modifier.width(9.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Brava))
                }
            } else {
                Column {
                    if (eyebrow != null) Text(eyebrow.uppercase(), color = BravaBright, fontSize = 12.sp,
                        letterSpacing = 1.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    if (title != null) Text(title, color = TextHi, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.7).sp)
                    if (hint != null) Text(hint, color = TextMid, fontSize = 13.sp, fontFamily = GeistFamily,
                        lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                }
            }
        }
        trailing?.invoke()
    }
}

// ============================================================================
// NEW Health-idiom components (additive)
// ============================================================================

@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    tone: Color = Brava,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(ShapeStat)
            .background(Surf1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.16f)),
            ) { Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.height(14.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = TextHi, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                fontFamily = GeistFamily, letterSpacing = (-1).sp)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = TextMid, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    fontFamily = GeistFamily, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = TextMid, fontSize = 13.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
    }
}
