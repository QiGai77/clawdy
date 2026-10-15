package com.openminis.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * Clawdy design tokens (source of truth: the review prototype + design/Clawdy-交互说明.md).
 * Colours, radii and motion live here so every Clawdy screen reads the same values.
 */
@Immutable
data class ClawdyPalette(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val text2: Color,
    val border: Color,
    val accent: Color,
    val accentPressed: Color,
    val taskCard: Color,
    val taskBorder: Color,
    val userBubble: Color,
    val off: Color,
    val success: Color = Color(0xFF4CAF7A),
    val amber: Color = Color(0xFFE6A23C),
    val error: Color = Color(0xFFD9534F),
    val termux: Color = Color(0xFF7B5CD6),
    val codeBg: Color = Color(0xFF1F1A17),
    val codeText: Color = Color(0xFFF3E9E1),
    val isDark: Boolean,
)

object ClawdyTokens {
    val Light = ClawdyPalette(
        bg = Color(0xFFFFFBF7),
        surface = Color(0xFFFFFFFF),
        text = Color(0xFF2B211C),
        text2 = Color(0xFF8A7A70),
        border = Color(0xFFEFE3D9),
        accent = Color(0xFFE07F5F),
        accentPressed = Color(0xFFC9694B),
        taskCard = Color(0xFFFDEBDD),
        taskBorder = Color(0xFFF6D3BD),
        userBubble = Color(0xFFF9E1D6),
        off = Color(0xFFD9CFC7),
        isDark = false,
    )
    val Dark = ClawdyPalette(
        bg = Color(0xFF1C1816),
        surface = Color(0xFF2A2420),
        text = Color(0xFFF3E9E1),
        text2 = Color(0xFFA8988C),
        border = Color(0xFF3A322C),
        accent = Color(0xFFF0A285),
        accentPressed = Color(0xFFE07F5F),
        taskCard = Color(0xFF3A2A22),
        taskBorder = Color(0xFF5A3E30),
        userBubble = Color(0xFF4A342A),
        off = Color(0xFF4A403A),
        isDark = true,
    )

    // Radii
    val RadiusInput = 28.dp
    val RadiusCard = 20.dp
    val RadiusButton = 14.dp
    val RadiusSheet = 28.dp
    val RadiusBlock = 14.dp
    val InputShape = RoundedCornerShape(RadiusInput)
    val CardShape = RoundedCornerShape(RadiusCard)
    val ButtonShape = RoundedCornerShape(RadiusButton)
    val BlockShape = RoundedCornerShape(RadiusBlock)
    val SheetShape = RoundedCornerShape(topStart = RadiusSheet, topEnd = RadiusSheet)
    val UserBubbleShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)

    // Switch (user feedback: smaller corner radius than a pill)
    val SwitchTrackRadius = 8.dp
    val SwitchThumbRadius = 6.dp

    // Icon stroke
    const val IconStroke = 1.75f
}

/** Motion tokens (ms + easings) from the prototype. */
object ClawdyMotion {
    val Standard: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    const val Press = 120          // send button scale .92
    const val SwitchThumb = 160
    const val TabUnderline = 180
    const val MessageIn = 200
    const val CardExpand = 260
    const val SheetIn = 320
    const val SheetOut = 260
    const val Breath = 2000        // avatar breathing (home)
    const val BreathWorking = 1200
    const val Spinner = 800
    const val PressScale = 0.92f
}

val LocalClawdyPalette = staticCompositionLocalOf { ClawdyTokens.Light }

object Clawdy {
    val colors: ClawdyPalette
        @Composable @ReadOnlyComposable get() = LocalClawdyPalette.current
}

/**
 * Serif faces: Noto Serif SC (思源宋体, OFL) subset to GB2312 for Chinese, Source Serif 4 (OFL)
 * for Latin. On API 29+ both are chained in one Typeface (Latin first, CJK fallback); older
 * phones use Noto Serif SC alone, which carries its own Latin.
 */
object ClawdyFonts {
    /** Noto Serif SC only: always available, no Context needed. */
    val NotoSerif: FontFamily = FontFamily(
        Font(R.font.noto_serif_sc_regular, FontWeight.Normal),
        Font(R.font.noto_serif_sc_semibold, FontWeight.Medium),
        Font(R.font.noto_serif_sc_semibold, FontWeight.SemiBold),
        Font(R.font.noto_serif_sc_semibold, FontWeight.Bold),
    )

    @Volatile private var cached: FontFamily? = null

    fun serif(context: Context): FontFamily {
        cached?.let { return it }
        val built = runCatching { buildChained(context) }.getOrNull() ?: NotoSerif
        cached = built
        return built
    }

    private fun buildChained(context: Context): FontFamily? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val res = context.resources
        fun fam(regular: Int, semibold: Int) = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(res, regular).setWeight(400).build()
        ).addFont(
            android.graphics.fonts.Font.Builder(res, semibold).setWeight(600).build()
        ).build()
        val tf = Typeface.CustomFallbackBuilder(fam(R.font.source_serif_4_regular, R.font.source_serif_4_semibold))
            .addCustomFallback(fam(R.font.noto_serif_sc_regular, R.font.noto_serif_sc_semibold))
            .setSystemFallback("serif")
            .build()
        // Compose derives weights with Typeface.create(tf, weight, italic), which picks the
        // matching font from each chained family.
        return FontFamily(androidx.compose.ui.text.font.Typeface(tf))
    }
}

@Composable
fun clawdyIsDark(themePref: Int): Boolean = when (themePref) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}
