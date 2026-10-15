package io.github.nanomuse.hands

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService

/**
 * Clawdy 「截图上传」off (the default): the screen as text instead of a picture. Every visible node
 * that says something or can be acted on becomes one line with its centre on the same 0–999 grid the
 * screen model taps on, so the action format does not change:
 *
 *     [412,233] button "发送"
 *     [500,880] edit "搜索" (focused)
 *     [500,640] edit (password field)
 *
 * Password fields never carry their text, and values a 密码箱 fill typed in are blanked
 * ([redact]), so no secret goes to the model this way either. Clawdy's own windows (the capsule)
 * are left out.
 */
object ScreenText {
    private const val MAX_LINES = 220
    private const val MAX_CHARS = 9000

    data class Dump(val text: String, val lines: Int, val packageName: String?) {
        /** For the loop watch: an unchanged dump is an unchanged screen. */
        val print: IntArray get() = intArrayOf(text.hashCode() and 0xff, (text.hashCode() shr 8) and 0xff, (text.hashCode() shr 16) and 0xff, lines)
    }

    fun dump(svc: MinisAccessibilityService, ownPackage: String, redact: List<String>): Dump {
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val out = StringBuilder()
        var n = 0
        var pkg: String? = null
        try {
            for (root in svc.rootNodes()) {
                val p = root.packageName?.toString()
                if (p == ownPackage) continue
                if (pkg == null && p != null && p != "com.android.systemui") pkg = p
                walk(root, 0) { node ->
                    if (n >= MAX_LINES || out.length > MAX_CHARS) return@walk
                    line(node, w, h, redact)?.let { out.append(it).append('\n'); n++ }
                }
            }
        } catch (_: Throwable) {
            // a tree that changes while it is read: what was read so far is the screen
        }
        if (n >= MAX_LINES || out.length > MAX_CHARS) out.append("(more on the screen — scroll to see it)\n")
        return Dump(out.toString().trimEnd(), n, pkg)
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 60) return
        if (!node.isVisibleToUser) return
        visit(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1, visit) }
    }

    private fun line(node: AccessibilityNodeInfo, w: Int, h: Int, redact: List<String>): String? {
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (b.width() <= 1 || b.height() <= 1) return null
        var text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()
        val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable || node.isScrollable
        if (text.isEmpty() && desc.isEmpty() && !actionable) return null
        if (node.isPassword) text = ""
        if (text.isNotEmpty() && redact.any { it.isNotEmpty() && text.contains(it) }) text = "(filled from 密码箱)"
        val role = when {
            node.isEditable -> "edit"
            node.isCheckable -> if (node.isChecked) "check[on]" else "check[off]"
            node.isScrollable -> "scroll"
            node.isClickable -> "button"
            else -> "text"
        }
        val cx = ((b.exactCenterX() / w) * 999).toInt().coerceIn(0, 999)
        val cy = ((b.exactCenterY() / h) * 999).toInt().coerceIn(0, 999)
        val label = listOf(text, desc.takeIf { it != text }.orEmpty(), if (node.isEditable && text.isEmpty()) hint else "")
            .filter { it.isNotEmpty() }.joinToString(" · ") { "\"" + it.replace('\n', ' ').take(120) + "\"" }
        return buildString {
            append('[').append(cx).append(',').append(cy).append("] ").append(role)
            if (label.isNotEmpty()) append(' ').append(label)
            if (node.isPassword) append(" (password field)")
            if (node.isFocused) append(" (focused)")
            if (!node.isEnabled) append(" (disabled)")
        }
    }
}
