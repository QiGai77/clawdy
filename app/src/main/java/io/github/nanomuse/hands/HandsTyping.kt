package io.github.nanomuse.hands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Clawdy 0.3.0: typing that survives custom text fields (WeChat, QQ, some WebViews).
 *
 * 0.2.0 found the field with `root.findFocus(FOCUS_INPUT)` on each window root and called
 * ACTION_SET_TEXT once. On the user's phone every `type into 搜索框` in WeChat failed: the
 * input-focused node there is not always the EditText itself (WeChat's search bar wraps it, and on
 * some ROMs the per-window lookup returns nothing while the global one does), and a node that is not
 * editable rejects SET_TEXT. Now the target is resolved in order —
 *   1. the service-wide input focus (`AccessibilityService.findFocus`), then each window's,
 *   2. an editable descendant / ancestor of that node when it is a wrapper,
 *   3. the editable field under the last tap, else the only editable field on screen —
 * and the text goes in by the first method that the field accepts, verified by re-reading it:
 *   a. ACTION_SET_TEXT,
 *   b. clipboard + ACTION_PASTE (after ACTION_FOCUS / ACTION_CLICK),
 *   c. clipboard + Shizuku `input keyevent 279` (KEYCODE_PASTE) when Shizuku is granted
 *      (`input text` cannot type Chinese, so the clipboard carries it).
 * The result names the method that worked, or every method that failed and why.
 */
object HandsTyping {
    private const val TAG = "HandsTyping"

    data class Target(val node: AccessibilityNodeInfo, val how: String)

    data class Outcome(val ok: Boolean, val method: String, val detail: String)

    /** The editable field Hands should type into, or null with the reason in [why]. */
    fun findTarget(svc: MinisAccessibilityService, lastTap: Pair<Int, Int>?, why: StringBuilder): Target? {
        val focused = runCatching { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: svc.rootNodes().firstNotNullOfOrNull { r -> runCatching { r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() }
        if (focused != null) {
            if (focused.isEditable) return Target(focused, "input focus")
            editableIn(focused)?.let { return Target(it, "editable inside the focused view") }
            editableAbove(focused)?.let { return Target(it, "editable around the focused view") }
            why.append("the view with input focus (${focused.className}) is not editable; ")
        } else why.append("no view has input focus; ")
        val editables = mutableListOf<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) {
            if (root.packageName?.toString() == svc.packageName) continue
            collectEditables(root, 0, editables)
        }
        if (editables.isEmpty()) { why.append("no editable field on screen"); return null }
        lastTap?.let { (x, y) ->
            val r = Rect()
            editables.firstOrNull { it.getBoundsInScreen(r); r.contains(x, y) }?.let { return Target(it, "editable under the last tap") }
            editables.minByOrNull { it.getBoundsInScreen(r); val dx = r.centerX() - x; val dy = r.centerY() - y; dx * dx + dy * dy }
                ?.takeIf { editables.size <= 3 }?.let { return Target(it, "editable nearest the last tap") }
        }
        editables.firstOrNull { it.isFocused }?.let { return Target(it, "focused editable") }
        if (editables.size == 1) return Target(editables[0], "the only editable field")
        why.append("${editables.size} editable fields and none has the cursor")
        return null
    }

    /** Puts [text] into [node], verifying by re-reading it. */
    fun type(context: Context, svc: MinisAccessibilityService, node: AccessibilityNodeInfo, text: String): Outcome {
        val tried = mutableListOf<String>()

        // a. ACTION_SET_TEXT
        val setOk = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
        }.getOrDefault(false)
        if (setOk && verify(node, text)) return Outcome(true, "set_text", "ACTION_SET_TEXT")
        tried += if (setOk) "ACTION_SET_TEXT returned true but the field still reads “${currentText(node).take(30)}”" else "ACTION_SET_TEXT refused"

        // b. clipboard + ACTION_PASTE
        val clipOk = setClipboard(context, text)
        if (!clipOk) tried += "could not set the clipboard"
        else {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            if (!node.isFocused) runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            // replace, not append: select what is there first
            val existing = currentText(node)
            if (existing.isNotEmpty()) selectAll(node, existing.length)
            Thread.sleep(120)
            val pasteOk = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            Thread.sleep(250)
            if (verify(node, text)) return Outcome(true, "paste", "clipboard + ACTION_PASTE")
            tried += if (pasteOk) "ACTION_PASTE ran but the text did not appear" else "ACTION_PASTE refused"

            // c. Shizuku KEYCODE_PASTE
            val shizuku = runCatching { com.openminis.app.offload.ShizukuBackend(context.applicationContext) }.getOrNull()
            if (shizuku != null && runCatching { shizuku.isPermissionGranted() }.getOrDefault(false)) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                val existing2 = currentText(node)
                if (existing2.isNotEmpty() && !existing2.contains(text)) selectAll(node, existing2.length)
                Thread.sleep(150)
                val r = runCatching { shizuku.runProcess(arrayOf("sh", "-c", "input keyevent 279"), null, null, 5_000) }.getOrNull()
                Thread.sleep(350)
                if (verify(node, text)) return Outcome(true, "shizuku_paste", "clipboard + Shizuku KEYCODE_PASTE")
                tried += "Shizuku keyevent 279 " + (r?.let { "exit ${it.exitCode}${it.stderr.takeIf { s -> s.isNotBlank() }?.let { s -> " (" + s.take(60) + ")" } ?: ""}" } ?: "failed") + ", text still missing"
            } else tried += "Shizuku not granted (skipped KEYCODE_PASTE)"
        }
        AppLogger.warning(TAG, "typing failed on ${node.className} in ${node.packageName}: ${tried.joinToString("; ")}")
        return Outcome(false, "none", tried.joinToString("; "))
    }

    private fun verify(node: AccessibilityNodeInfo, text: String): Boolean {
        runCatching { node.refresh() }
        val now = currentText(node)
        if (now.contains(text)) return true
        // fields that show a hint as text, or trim whitespace
        return text.isNotBlank() && now.replace(" ", "").contains(text.replace(" ", ""))
    }

    private fun currentText(node: AccessibilityNodeInfo): String {
        runCatching { node.refresh() }
        val t = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        return if (hint != null && t == hint && node.isShowingHintText) "" else t
    }

    private fun selectAll(node: AccessibilityNodeInfo, len: Int) {
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            })
        }
    }

    /** Clipboard writes go through the main thread (some ROMs throw off it). */
    private fun setClipboard(context: Context, text: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            ok = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Clawdy", text))
                true
            }.getOrDefault(false)
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun editableIn(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 6) return null
        for (i in 0 until node.childCount) {
            val ch = node.getChild(i) ?: continue
            if (ch.isEditable && ch.isVisibleToUser) return ch
            editableIn(ch, depth + 1)?.let { return it }
        }
        return null
    }

    private fun editableAbove(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        var n = 0
        while (p != null && n < 3) {
            if (p.isEditable) return p
            editableIn(p)?.let { return it }
            p = p.parent; n++
        }
        return null
    }

    private fun collectEditables(node: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > 60 || !node.isVisibleToUser) return
        if (node.isEditable && node.isEnabled) out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectEditables(it, depth + 1, out) }
    }
}
