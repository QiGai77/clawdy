package com.openminis.app.ui.theme

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clawdy 0.3.0 外观 settings, read by [MinisTheme] and every Clawdy component through
 * [LocalClawdyLook]. Stored in `clawdy_prefs`; [ClawdyLookStore.state] republishes on change so
 * the whole UI restyles at once.
 */
@Immutable
data class ClawdyLook(
    /** 0 = 衬线 (Source Serif 4 + Noto Serif SC), 1 = 无衬线 (system sans), 2 = 跟随系统 (the ROM's default face). */
    val font: Int = 0,
    /** Multiplies every text size; 0.85–1.3. */
    val scale: Float = 1f,
    val accent: String = "coral",
    /** 0 = 强调色竖条 + 浅色底, 1 = 只有竖条, 2 = 描边卡片. */
    val quote: Int = 0,
    /** 0 = 深色代码块, 1 = 浅色代码块. */
    val code: Int = 0,
    /** 0 = 紧凑, 1 = 标准, 2 = 宽松 — line height of chat text and spacing between turns. */
    val density: Int = 1,
    /** 显示说明文字: explanatory subtitles and footnotes in settings. Off by default. */
    val hints: Boolean = false,
) {
    val lineMul: Float get() = when (density) { 0 -> 0.9f; 2 -> 1.14f; else -> 1f }
    val turnGap: Float get() = when (density) { 0 -> 0.7f; 2 -> 1.35f; else -> 1f }
}

data class AccentPreset(val id: String, val label: String, val light: Color, val dark: Color)

object ClawdyLookStore {
    private const val FILE = "clawdy_prefs"
    private const val K_FONT = "look_font"
    private const val K_SCALE = "look_scale"
    private const val K_ACCENT = "look_accent"
    private const val K_QUOTE = "look_quote"
    private const val K_CODE = "look_code"
    private const val K_DENSITY = "look_density"
    private const val K_HINTS = "look_hints"

    val ACCENTS = listOf(
        AccentPreset("coral", "珊瑚", Color(0xFFE07F5F), Color(0xFFF0A285)),
        AccentPreset("amber", "琥珀", Color(0xFFD38A1E), Color(0xFFF0B65A)),
        AccentPreset("rose", "玫瑰", Color(0xFFD45A7C), Color(0xFFEE8BA6)),
        AccentPreset("teal", "青绿", Color(0xFF2A9D8F), Color(0xFF5EC4B6)),
        AccentPreset("forest", "松绿", Color(0xFF4C9A5B), Color(0xFF7CC48A)),
        AccentPreset("indigo", "靛蓝", Color(0xFF5B6CDB), Color(0xFF8E9BF0)),
        AccentPreset("graphite", "石墨", Color(0xFF5E5A57), Color(0xFFB5AFAA)),
    )

    val SCALES = listOf(0.9f to "小", 1f to "标准", 1.12f to "大", 1.25f to "特大")

    private val _state = MutableStateFlow(ClawdyLook())
    val state: StateFlow<ClawdyLook> = _state.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context): ClawdyLook {
        if (!loaded) {
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _state.value = ClawdyLook(
                font = p.getInt(K_FONT, 0),
                scale = p.getFloat(K_SCALE, 1f).coerceIn(0.85f, 1.3f),
                accent = p.getString(K_ACCENT, "coral") ?: "coral",
                quote = p.getInt(K_QUOTE, 0),
                code = p.getInt(K_CODE, 0),
                density = p.getInt(K_DENSITY, 1),
                hints = p.getBoolean(K_HINTS, false),
            )
            loaded = true
        }
        return _state.value
    }

    fun update(context: Context, block: (ClawdyLook) -> ClawdyLook) {
        load(context)
        val next = block(_state.value)
        _state.value = next
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(K_FONT, next.font).putFloat(K_SCALE, next.scale).putString(K_ACCENT, next.accent)
            .putInt(K_QUOTE, next.quote).putInt(K_CODE, next.code).putInt(K_DENSITY, next.density)
            .putBoolean(K_HINTS, next.hints).apply()
    }

    /** The base palette with the chosen accent and the tints derived from it. Coral keeps its hand-tuned tints. */
    fun palette(base: ClawdyPalette, look: ClawdyLook): ClawdyPalette {
        if (look.accent == "coral") return base
        val preset = ACCENTS.firstOrNull { it.id == look.accent } ?: return base
        val a = if (base.isDark) preset.dark else preset.light
        return base.copy(
            accent = a,
            accentPressed = lerp(a, Color.Black, 0.14f),
            taskCard = lerp(base.bg, a, if (base.isDark) 0.16f else 0.12f),
            taskBorder = lerp(base.bg, a, if (base.isDark) 0.32f else 0.28f),
            userBubble = lerp(base.bg, a, if (base.isDark) 0.24f else 0.16f),
        )
    }
}

val LocalClawdyLook = staticCompositionLocalOf { ClawdyLook() }
