package com.openminis.app.ui.clawdy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.ClawdyTokens

/**
 * Clawdy's line icon set: 24-unit grid, 1.75 stroke, round caps/joins (lucide-style geometry,
 * ISC-licensed shapes redrawn as path data). No emoji anywhere in the UI — everything is one of
 * these vectors, tinted by the caller.
 */
object ClawdyIcons {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        )
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = ClawdyTokens.IconStroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val CIRCLE_9 = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"

    val Menu by lazy { icon("menu", "M4 7h16", "M4 12h16", "M4 17h10") }
    val NewChat by lazy {
        icon("new_chat", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "M18.4 2.6a2.12 2.12 0 1 1 3 3L12 15l-4 1 1-4z")
    }
    val Send by lazy { icon("send", "M12 19V5", "M5 12l7-7 7 7") }
    val Stop by lazy { icon("stop", "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", filled = true) }
    val Mic by lazy {
        icon("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")
    }
    val Plus by lazy { icon("plus", "M12 5v14", "M5 12h14") }
    val Copy by lazy {
        icon("copy", "M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M4 16V6a2 2 0 0 1 2-2h10")
    }
    val Retry by lazy { icon("retry", "M3 12a9 9 0 1 0 9-9a9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val ThumbUp by lazy {
        icon("thumb_up", "M7 10v12",
            "M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88z")
    }
    val ThumbDown by lazy {
        icon("thumb_down", "M17 14V2",
            "M9 18.12L10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88z")
    }
    val Pause by lazy { icon("pause", "M9 5v14", "M15 5v14") }
    val Play by lazy { icon("play", "M7 4.5v15a.5.5 0 0 0 .76.43l12-7.5a.5.5 0 0 0 0-.86l-12-7.5A.5.5 0 0 0 7 4.5z") }
    val Close by lazy { icon("close", "M18 6L6 18", "M6 6l12 12") }
    val Folder by lazy {
        icon("folder", "M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2z")
    }
    val Clock by lazy { icon("clock", CIRCLE_9, "M12 7v5l3 2") }
    val Memory by lazy {
        icon("memory", "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20", "M9 7h7", "M9 11h5")
    }
    val Settings by lazy {
        icon("settings", "M12 20a8 8 0 1 0 0-16a8 8 0 0 0 0 16z", "M12 14a2 2 0 1 0 0-4a2 2 0 0 0 0 4z",
            "M12 2v2", "M12 22v-2", "M17 20.66l-1-1.73", "M11 10.27L7 3.34", "M20.66 17l-1.73-1", "M3.34 7l1.73 1",
            "M14 12h8", "M2 12h2", "M20.66 7l-1.73 1", "M3.34 17l1.73-1", "M17 3.34l-1 1.73", "M11 13.73l-4 6.93")
    }
    val Terminal by lazy { icon("terminal", "M4 17l6-6-6-6", "M12 19h8") }
    val Hand by lazy {
        icon("hand", "M18 11V6a2 2 0 0 0-4 0", "M14 10V4a2 2 0 0 0-4 0v2", "M10 10.5V6a2 2 0 0 0-4 0v8",
            "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15")
    }
    val Shield by lazy {
        icon("shield", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
    }
    val ChevronDown by lazy { icon("chev_down", "M6 9l6 6 6-6") }
    val ChevronUp by lazy { icon("chev_up", "M18 15l-6-6-6 6") }
    val ChevronRight by lazy { icon("chev_right", "M9 18l6-6-6-6") }
    val ChevronLeft by lazy { icon("chev_left", "M15 18l-6-6 6-6") }
    val Check by lazy { icon("check", "M20 6L9 17l-5-5") }
    val Search by lazy { icon("search", "M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M21 21l-4.3-4.3") }
    val Eye by lazy {
        icon("eye", "M2.06 12.35a1 1 0 0 1 0-.7a10.75 10.75 0 0 1 19.88 0a1 1 0 0 1 0 .7a10.75 10.75 0 0 1-19.88 0",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    }
    val EyeOff by lazy {
        icon("eye_off", "M3 3l18 18", "M10.6 5.1A10.7 10.7 0 0 1 21.94 11.65a1 1 0 0 1 0 .7a10.8 10.8 0 0 1-1.9 3.1",
            "M6.6 6.6a10.8 10.8 0 0 0-4.54 5.05a1 1 0 0 0 0 .7A10.75 10.75 0 0 0 17.4 17.4", "M9.9 9.9a3 3 0 0 0 4.2 4.2")
    }
    val Camera by lazy {
        icon("camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z",
            "M12 10a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    }
    val Image by lazy {
        icon("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M9 7a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M21 15l-3.09-3.09a2 2 0 0 0-2.82 0L6 21")
    }
    val File by lazy {
        icon("file", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4")
    }
    val Phone by lazy {
        icon("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")
    }
    val Link by lazy {
        icon("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    }
    val Globe by lazy { icon("globe", CIRCLE_9, "M12 3a14.5 14.5 0 0 0 0 18a14.5 14.5 0 0 0 0-18", "M3 12h18") }
    val Sparkle by lazy { icon("sparkle", "M12 3l1.9 5.8L20 11l-6.1 2.2L12 19l-1.9-5.8L4 11l6.1-2.2z") }
    val Share by lazy { icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13") }
    val External by lazy {
        icon("external", "M15 3h6v6", "M10 14L21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
    }
    val Edit by lazy {
        icon("edit", "M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z")
    }
    val Trash by lazy {
        icon("trash", "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    }
    val Pin by lazy {
        icon("pin", "M12 17v5",
            "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4a1 1 0 0 1 1 1z")
    }
    val Bell by lazy {
        icon("bell", "M10.27 21a2 2 0 0 0 3.46 0",
            "M3.26 15.33A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.67C19.41 13.96 18 12.5 18 8A6 6 0 0 0 6 8c0 4.5-1.41 5.96-2.74 7.33")
    }
    val Battery by lazy { icon("battery", "M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M22 11v2", "M6 11v2") }
    val Layers by lazy { icon("layers", "M12 2L2 7l10 5 10-5z", "M2 17l10 5 10-5", "M2 12l10 5 10-5") }
    val Key by lazy {
        icon("key", "M15.5 7.5l2.3 2.3a1 1 0 0 0 1.4 0l2.1-2.1a1 1 0 0 0 0-1.4L19 4", "M21 2l-9.6 9.6",
            "M7.5 10a5.5 5.5 0 1 0 0 11a5.5 5.5 0 1 0 0-11z")
    }
    val Zap by lazy { icon("zap", "M13 2L3 14h9l-1 8 10-12h-9z") }
    val Info by lazy { icon("info", CIRCLE_9, "M12 16v-4", "M12 8h.01") }
    val Palette by lazy {
        icon("palette", "M12 22a10 10 0 1 1 10-10c0 2.5-2 3-3.5 3H16a2 2 0 0 0-1.5 3.3A1.6 1.6 0 0 1 12 22z",
            "M7.5 10.5h.01", "M12 7.5h.01", "M16.5 10.5h.01")
    }
    val Logs by lazy { icon("logs", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01") }
    val User by lazy { icon("user", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M12 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8z") }
    val Alert by lazy {
        icon("alert", "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    }
    val More by lazy { icon("more", "M5 12h.01", "M12 12h.01", "M19 12h.01") }
    val Upload by lazy { icon("upload", "M12 3v12", "M17 8l-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4") }
    val FolderPlus by lazy {
        icon("folder_plus", "M12 10v6", "M9 13h6",
            "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z")
    }
    val Back by lazy { icon("back", "M19 12H5", "M12 19l-7-7 7-7") }
    val Wrench by lazy {
        icon("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
    }
    val Brain by lazy {
        icon("think", "M9 18h6", "M10 22h4",
            "M15.09 14c.18-.98.65-1.74 1.41-2.5A4.65 4.65 0 0 0 18 8A6 6 0 0 0 6 8c0 1 .23 2.23 1.5 3.5A4.61 4.61 0 0 1 8.91 14")
    }
    val Box by lazy {
        icon("box", "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z",
            "M3.3 7L12 12l8.7-5", "M12 22V12")
    }
    val Lock by lazy {
        icon("lock", "M6 11h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 8 0v4")
    }
    val Card by lazy {
        icon("card", "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M2 10h20", "M6 15h4")
    }
    val MapPin by lazy {
        icon("map_pin", "M20 10c0 5-5.5 10.2-7.4 11.8a1 1 0 0 1-1.2 0C9.5 20.2 4 15 4 10a8 8 0 0 1 16 0z", "M12 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    }
    val Grid by lazy {
        icon("grid", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z")
    }
    val Sliders by lazy {
        icon("sliders", "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M2 14h4", "M10 8h4", "M18 16h4")
    }
    val Puzzle by lazy {
        icon("puzzle", "M19.4 14.5a2.5 2.5 0 1 0 0-5H18V6a2 2 0 0 0-2-2h-3.5v1.4a2.5 2.5 0 1 1-5 0V4H6a2 2 0 0 0-2 2v3.5h1.4a2.5 2.5 0 1 1 0 5H4V18a2 2 0 0 0 2 2h3.5v-1.4a2.5 2.5 0 1 1 5 0V20H16a2 2 0 0 0 2-2v-3.5z")
    }
    val Plug by lazy {
        icon("plug", "M12 22v-5", "M9 8V2", "M15 8V2", "M18 8v5a6 6 0 0 1-12 0V8z")
    }
    val Variable by lazy {
        icon("variable", "M8 21s-4-3-4-9 4-9 4-9", "M16 3s4 3 4 9-4 9-4 9", "M15 9l-6 6", "M9 9l6 6")
    }
    val Chart by lazy { icon("chart", "M3 3v18h18", "M8 17v-5", "M13 17V8", "M18 17v-3") }
    val Clipboard by lazy {
        icon("clipboard", "M9 3h6a1 1 0 0 1 1 1v2H8V4a1 1 0 0 1 1-1z", "M16 5h2a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2")
    }
    val Ban by lazy { icon("ban", CIRCLE_9, "M5.6 5.6l12.8 12.8") }
}
