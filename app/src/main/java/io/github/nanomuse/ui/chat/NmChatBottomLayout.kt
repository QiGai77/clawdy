package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared by the composer's bottom padding and the navigation surround's upper edge.
internal val NM_CHAT_COMPOSER_BOTTOM_GAP = 8.dp

private enum class BottomSlot { Footer, Content }

/**
 * Overlays a footer above [bottomInset] without shortening the scrolling blur source.
 * The footer is measured first, so [content] receives its complete bottom obstruction
 * during the same layout pass, including tool previews and an expanded composer.
 * The caller supplies a bounded chat pane and handles its IME insets.
 */
@Composable
fun NmChatBottomLayout(
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(bottomChromeHeight: Dp) -> Unit,
    bottomContent: @Composable ColumnScope.() -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val inset = bottomInset.roundToPx().coerceIn(0, height)
        val footer = subcompose(BottomSlot.Footer) {
            Column(Modifier.fillMaxWidth(), content = bottomContent)
        }.single().measure(
            Constraints(
                minWidth = width,
                maxWidth = width,
                maxHeight = height - inset,
            ),
        )
        val bottomChromeHeight = footer.height + inset
        val bottomChromeHeightDp = bottomChromeHeight.toDp()
        val body = subcompose(BottomSlot.Content) {
            Box(Modifier.fillMaxSize()) { content(bottomChromeHeightDp) }
        }.single().measure(Constraints.fixed(width, height))

        layout(width, height) {
            body.placeRelative(0, 0)
            footer.placeRelative(0, height - bottomChromeHeight)
        }
    }
}
