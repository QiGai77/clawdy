package com.openminis.app.ui.clawdy

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyFonts
import com.openminis.app.ui.theme.ClawdyMotion
import com.openminis.app.ui.theme.ClawdyTokens
import com.openminis.app.ui.theme.LocalClawdyLook

/** The UI face: serif (Source Serif 4 + Noto Serif SC) by default, or the 外观 → 字体 choice. */
@Composable
fun serifFamily(): FontFamily {
    val ctx = LocalContext.current
    val font = LocalClawdyLook.current.font
    return remember(font) {
        when (font) {
            1 -> FontFamily.SansSerif
            2 -> FontFamily.Default
            else -> ClawdyFonts.serif(ctx)
        }
    }
}

/** Text style in the chosen face, scaled by 外观 → 字号; [line] also follows 对话密度. */
@Composable
fun serif(size: TextUnit, weight: FontWeight = FontWeight.Normal, line: TextUnit = TextUnit.Unspecified): TextStyle {
    val look = LocalClawdyLook.current
    return TextStyle(
        fontFamily = serifFamily(), fontSize = size * look.scale, fontWeight = weight,
        lineHeight = if (line.isUnspecified) line else line * (look.scale * look.lineMul),
    )
}

/** True when 外观 → 显示说明文字 is on. */
@Composable
fun showHints(): Boolean = LocalClawdyLook.current.hints

val Mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

enum class AvatarMood(@DrawableRes val res: Int) {
    IDLE(R.drawable.nm_avatar_idle),
    WORKING(R.drawable.nm_avatar_working),
    WAITING(R.drawable.nm_avatar_waiting),
    HAPPY(R.drawable.nm_avatar_happy),
    ERROR(R.drawable.nm_avatar_error),
}

/** Avatar in a white round bubble with a soft coral glow; breathes (scale 1 → 1.04). */
@Composable
fun ClawdyAvatar(
    mood: AvatarMood,
    size: Dp,
    modifier: Modifier = Modifier,
    breathe: Boolean = true,
    glow: Boolean = true,
    periodMs: Int = ClawdyMotion.Breath,
) {
    val c = Clawdy.colors
    val t = rememberInfiniteTransition(label = "breath")
    val s by t.animateFloat(
        initialValue = 1f, targetValue = if (breathe) 1.04f else 1f,
        animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse), label = "s",
    )
    val glowColor = c.accent
    Box(
        modifier
            .size(size)
            .scale(s)
            .drawBehind {
                if (glow) {
                    val r = this.size.minDimension / 2
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(glowColor.copy(alpha = 0.30f), glowColor.copy(alpha = 0f)),
                            center = center, radius = r * 1.45f,
                        ),
                        radius = r * 1.45f,
                    )
                    drawCircle(glowColor.copy(alpha = 0.10f), radius = r + 6.dp.toPx())
                }
            }
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(mood.res),
            contentDescription = "Clawdy",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size * 0.87f).clip(CircleShape),
        )
    }
}

/** Round icon button with a 48dp touch target. */
@Composable
fun ClawdyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Clawdy.colors.text,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(iconSize))
    }
}

/** Custom switch: rounded-rect track (8dp), rounded-square thumb (6dp), thumb slides 160ms. */
@Composable
fun ClawdySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Clawdy.colors
    val track by animateColorAsState(if (checked) c.accent else c.off, tween(ClawdyMotion.SwitchThumb), label = "track")
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, tween(ClawdyMotion.SwitchThumb, easing = ClawdyMotion.Standard), label = "x")
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(ClawdyTokens.SwitchTrackRadius))
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .then(
                if (onCheckedChange != null) Modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                else Modifier
            )
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .size(20.dp)
                .shadow(1.dp, RoundedCornerShape(ClawdyTokens.SwitchThumbRadius))
                .clip(RoundedCornerShape(ClawdyTokens.SwitchThumbRadius))
                .background(Color.White),
        )
    }
}

/** Indeterminate ring spinner (accent head over a faint ring). */
@Composable
fun ClawdySpinner(size: Dp = 16.dp, color: Color = Clawdy.colors.accent, stroke: Dp = 2.dp, spinning: Boolean = true) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(ClawdyMotion.Spinner, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size).rotate(if (spinning) angle else 0f)) {
        val sw = stroke.toPx()
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(sw))
        drawArc(color, -90f, 90f, false, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Hollow pending circle. */
@Composable
fun HollowDot(size: Dp = 14.dp, color: Color = Clawdy.colors.text2) {
    Box(Modifier.size(size).border(1.5.dp, color, CircleShape))
}

@Composable
fun StatusDot(on: Boolean, size: Dp = 7.dp) {
    val c = Clawdy.colors
    if (on) Box(Modifier.size(size).clip(CircleShape).background(c.success))
    else Box(Modifier.size(size).border(1.dp, c.text2, CircleShape))
}

/** A filled check in a circle. */
@Composable
fun CheckBadge(color: Color, size: Dp = 18.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(ClawdyIcons.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

/** Press-scale modifier helper (.92, 120ms). */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, scaleTo: Float = ClawdyMotion.PressScale): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, tween(ClawdyMotion.Press), label = "press")
    return this.scale(s)
}

enum class ButtonKind { PRIMARY, SECONDARY, DANGER, GHOST }

@Composable
fun ClawdyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val c = Clawdy.colors
    val src = remember { MutableInteractionSource() }
    val (bg, fg, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(c.accent, Color.White, Color.Transparent)
        ButtonKind.SECONDARY -> Triple(c.surface, c.text, c.border)
        ButtonKind.DANGER -> Triple(c.error, Color.White, Color.Transparent)
        ButtonKind.GHOST -> Triple(Color.Transparent, c.accent, Color.Transparent)
    }
    Row(
        modifier
            .pressScale(src, 0.96f)
            .heightIn(min = height)
            .clip(ClawdyTokens.ButtonShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .border(1.dp, border, ClawdyTokens.ButtonShape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = serif(15.sp, FontWeight.SemiBold), maxLines = 1)
    }
}

/** Top bar: back/menu on the left, serif title, optional trailing actions. */
@Composable
fun ClawdyTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = ClawdyIcons.ChevronLeft,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Clawdy.colors
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) ClawdyIconButton(leadingIcon, "返回", onBack) else Spacer(Modifier.width(12.dp))
        Text(
            title, color = c.text, style = serif(18.sp, FontWeight.SemiBold),
            modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Grouped card (20dp radius, hairline border). */
@Composable
fun ClawdyCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(0.dp), content: @Composable ColumnScope.() -> Unit) {
    val c = Clawdy.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ClawdyTokens.CardShape)
            .background(c.surface)
            .border(1.dp, c.border, ClawdyTokens.CardShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Clawdy.colors.text2, style = serif(13.sp, FontWeight.Medium),
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * A settings-style row: icon tile, title + [subtitle] (data — always shown), [hint] (explanation —
 * shown only with 外观 → 显示说明文字), trailing content (chevron by default). The text column always
 * keeps at least ~45% of the row, and wide trailing content should go in [below] instead.
 */
@Composable
fun ClawdyRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    hint: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Clawdy.colors.accent,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = onClick != null && trailing == null,
    below: (@Composable () -> Unit)? = null,
) {
    val c = Clawdy.colors
    val hints = showHints()
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(iconTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = c.text, style = serif(16.sp))
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = c.text2, style = serif(13.sp, line = 18.sp))
                }
                if (hints && !hint.isNullOrBlank()) {
                    Text(hint, color = c.text2, style = serif(12.sp, line = 17.sp))
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.widthIn(max = 180.dp)) { trailing() }
            }
            if (showChevron) Icon(ClawdyIcons.ChevronRight, null, tint = c.text2, modifier = Modifier.size(18.dp))
        }
        if (below != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().padding(start = if (icon != null) 48.dp else 0.dp)) { below() }
        }
    }
}

@Composable
fun RowDivider(startInset: Dp = 64.dp) {
    Box(Modifier.padding(start = startInset).fillMaxWidth().height(1.dp).background(Clawdy.colors.border))
}

/** Small pill label (status badges). */
@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(
        text,
        color = fg,
        style = if (mono) Mono.copy(fontWeight = FontWeight.SemiBold) else serif(12.sp, FontWeight.SemiBold),
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Sheet grab handle. */
@Composable
fun SheetGrab() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Clawdy.colors.off))
    }
}

/** A thin progress bar (4dp). */
@Composable
fun ThinProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "p")
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(f).height(4.dp).clip(CircleShape).background(color))
    }
}

@Suppress("unused")
private fun Offset.noop() = this
