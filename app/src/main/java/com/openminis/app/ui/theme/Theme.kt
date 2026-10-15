package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight // nanoMuse
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent: iOS blue, desaturated. [T-android-accent-blue-parity]
//
// Was a teal (#2E8B8B / #4DD9D9) that predated iOS settling on blue. The hue
// now comes from iOS Assets.xcassets/AccentColor.colorset (sRGB components
// r0.212 g0.525 b0.933 -> #3686EE light, r0.329 g0.565 b0.894 -> #5490E4 dark),
// but iOS's saturation (84% / 73%) read as glaring on Android's darker
// surfaces, so SATURATION is dialled back ~30% with hue and lightness kept:
//   light  #3686EE  S84% L57%  ->  #528AD2  S59% L57%
//   dark   #5490E4  S73% L61%  ->  #6A94CE  S51% L61%
//
// Lightness is deliberately NOT raised, which is the other way to "lighten".
// It would have softened dark mode further but pushed light-mode contrast on
// white from 3.62 to 2.62 — below WCAG AA's 4.5 for text. Desaturating keeps
// dark mode at 5.93 (passing) and leaves light mode where it was.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
private val TealPrimary = Color(0xFFD9714F)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFFCE4D9)
private val TealOnPrimaryContainer = Color(0xFF5A2312)
private val TealSecondary = Color(0xFF8A6F62)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFF3E4DA)
private val TealOnSecondaryContainer = Color(0xFF2E1E16)
private val TealTertiary = Color(0xFFC27C8E)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFFBE0E6)
private val TealOnTertiaryContainer = Color(0xFF3A1520)
private val TealBackground = Color(0xFFFFFBF7)
private val TealOnBackground = Color(0xFF2B211C)
private val TealSurface = Color(0xFFFFFBF7)
private val TealOnSurface = Color(0xFF2B211C)
private val TealSurfaceVariant = Color(0xFFF3E9E0)
private val TealOnSurfaceVariant = Color(0xFF5C4A40)
private val TealOutline = Color(0xFF9A857A)

private val TealDarkPrimary = Color(0xFFF0A285)
private val TealDarkOnPrimary = Color(0xFF4A1B0C)
private val TealDarkPrimaryContainer = Color(0xFF5A3426)
private val TealDarkOnPrimaryContainer = Color(0xFFFCE4D9)
private val TealDarkSecondary = Color(0xFFD9C2B5)
private val TealDarkOnSecondary = Color(0xFF3A2A22)
private val TealDarkSecondaryContainer = Color(0xFF4A3B33)
private val TealDarkOnSecondaryContainer = Color(0xFFF3E4DA)
private val TealDarkBackground = Color(0xFF1C1816)
private val TealDarkOnBackground = Color(0xFFF1E4DA)
private val TealDarkSurface = Color(0xFF1C1816)
private val TealDarkOnSurface = Color(0xFFF1E4DA)
private val TealDarkSurfaceVariant = Color(0xFF3A312C)
private val TealDarkOnSurfaceVariant = Color(0xFFD9C8BD)
private val TealDarkOutline = Color(0xFF9A857A)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
// Light: page = #F2F2F7 gray, card = white
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFFFFBF7)
private val NeutralGroupedCard = Color(0xFFFFFFFF)
private val NeutralGroupedCardElevated = Color(0xFFFBF4EE)
private val NeutralOutline = Color(0xFFEADDD3)

private val NeutralDarkGroupedBg = Color(0xFF1C1816)
private val NeutralDarkGroupedCard = Color(0xFF2A2420)
private val NeutralDarkGroupedCardElevated = Color(0xFF352D29)
private val NeutralDarkOutline = Color(0xFF4A3F39)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedBg,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(16.dp),        // Chip, TextField
    medium = RoundedCornerShape(24.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    // Clawdy: serif everywhere (Source Serif 4 + Noto Serif SC), user feedback 2026-10.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val serifFamily = androidx.compose.runtime.remember { ClawdyFonts.serif(ctx) }
    val typography = scaledTypography(fontScale).withFamily(serifFamily)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette
    val clawdyPalette = if (darkTheme) ClawdyTokens.Dark else ClawdyTokens.Light

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(LocalChatPalette provides chatPalette, LocalClawdyPalette provides clawdyPalette, content = content)
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

// nanoMuse: Muse's type, on the system face. Material's scale carries tracking
// (0.1–0.5sp) and small labels (11–12sp) that read as "Android"; Muse sets
// everything at zero tracking, runs body at 16 and its captions at 13, and
// weights titles SemiBold. The face stays the device's default so the app
// matches Muse on the same phone (MiSans, Roboto, …) and Chinese and Latin
// never come from two fonts.
private fun museTypography(): Typography {
    val base = Typography()
    fun TextStyle.muse(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        copy(fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.muse(56, 64, FontWeight.SemiBold),
        displayMedium = base.displayMedium.muse(44, 52, FontWeight.SemiBold),
        displaySmall = base.displaySmall.muse(36, 44, FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.muse(32, 40, FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.muse(28, 36, FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.muse(24, 32, FontWeight.SemiBold),
        titleLarge = base.titleLarge.muse(22, 28, FontWeight.SemiBold),
        titleMedium = base.titleMedium.muse(17, 22, FontWeight.SemiBold),
        titleSmall = base.titleSmall.muse(15, 20, FontWeight.Medium),
        bodyLarge = base.bodyLarge.muse(16, 24),
        bodyMedium = base.bodyMedium.muse(14, 20),
        bodySmall = base.bodySmall.muse(13, 18),
        labelLarge = base.labelLarge.muse(15, 20, FontWeight.Medium),
        labelMedium = base.labelMedium.muse(13, 18, FontWeight.Medium),
        labelSmall = base.labelSmall.muse(12, 16, FontWeight.Medium),
    )
}

private fun scaledTypography(factor: Float): Typography {
    val base = museTypography() // nanoMuse: was Material's default scale
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}

private fun Typography.withFamily(f: androidx.compose.ui.text.font.FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)
