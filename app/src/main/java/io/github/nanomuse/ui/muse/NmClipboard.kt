package io.github.nanomuse.ui.muse

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard

/**
 * Puts [text] on the clipboard as a plain-text clip labelled [label], synchronously. The screens
 * copy from a click handler, where the suspending `Clipboard.setClipEntry` would need a scope
 * for nothing; the platform clipboard does the same work in one call.
 */
fun Clipboard.setPlainText(label: String, text: String) {
    nativeClipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
