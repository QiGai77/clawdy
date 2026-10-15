package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * A broad scrolling scrim surrounding the composer's rounded outline.
 * Straight-edge gradients join radial corner gradients continuously. The scrim
 * fades out below the composer before navigation, rather than ending as a rectangle.
 */
@Composable
internal fun NmComposerVignette(
    color: Color,
    opacity: Float,
    spread: Dp,
    cornerRadius: Dp,
    horizontalInset: Dp,
    topPadding: Dp,
    bottomPadding: Dp,
    maxContentWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = opacity
                // Isolate the lower fade mask from the conversation behind it.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val spreadPx = spread.toPx()
                val contentWidth = minOf(size.width, maxContentWidth.toPx())
                val left = (size.width - contentWidth) / 2f + horizontalInset.toPx()
                val right = size.width - left
                val top = spreadPx + topPadding.toPx()
                val bottom = size.height - bottomPadding.toPx()
                val width = (right - left).coerceAtLeast(0f)
                val height = (bottom - top).coerceAtLeast(0f)
                val radius = minOf(cornerRadius.toPx(), width / 2f, height / 2f)
                    .coerceAtLeast(0f)
                val outerRadius = radius + spreadPx
                val x1 = left + radius
                val x2 = right - radius
                val y1 = top + radius
                val y2 = bottom - radius
                val transparent = color.copy(alpha = 0f)
                val topBrush = Brush.verticalGradient(
                    colors = listOf(transparent, color),
                    startY = top - spreadPx,
                    endY = top,
                )
                val bottomBrush = Brush.verticalGradient(
                    colors = listOf(color, transparent),
                    startY = bottom,
                    endY = bottom + spreadPx,
                )
                val leftBrush = Brush.horizontalGradient(
                    colors = listOf(transparent, color),
                    startX = left - spreadPx,
                    endX = left,
                )
                val rightBrush = Brush.horizontalGradient(
                    colors = listOf(color, transparent),
                    startX = right,
                    endX = right + spreadPx,
                )
                fun cornerBrush(x: Float, y: Float) = Brush.radialGradient(
                    0f to color,
                    radius / outerRadius to color,
                    1f to transparent,
                    center = Offset(x, y),
                    radius = outerRadius,
                )
                val corners = listOf(
                    Rect(left - spreadPx, top - spreadPx, x1, y1) to cornerBrush(x1, y1),
                    Rect(x2, top - spreadPx, right + spreadPx, y1) to cornerBrush(x2, y1),
                    Rect(left - spreadPx, y2, x1, bottom + spreadPx) to cornerBrush(x1, y2),
                    Rect(x2, y2, right + spreadPx, bottom + spreadPx) to cornerBrush(x2, y2),
                )
                // Slightly lighten only the scrim above the input. Blend back
                // to full coverage across its upper corners, keeping the halo
                // and the lower fade continuous.
                val surroundMask = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.9f),
                    (top / size.height).coerceIn(0f, 1f) to Color.White.copy(alpha = 0.9f),
                    ((top + radius) / size.height).coerceIn(0f, 1f) to Color.White,
                    (bottom / size.height).coerceIn(0f, 1f) to Color.White,
                    1f to Color.Transparent,
                )

                onDrawWithContent {
                    drawContent()
                    if (width > 0f && height > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                        if (x2 > x1) {
                            drawRect(topBrush, Offset(x1, top - spreadPx), Size(x2 - x1, spreadPx))
                            drawRect(bottomBrush, Offset(x1, bottom), Size(x2 - x1, spreadPx))
                        }
                        if (y2 > y1) {
                            drawRect(leftBrush, Offset(left - spreadPx, y1), Size(spreadPx, y2 - y1))
                            drawRect(rightBrush, Offset(right, y1), Size(spreadPx, y2 - y1))
                        }
                        corners.forEach { (rect, brush) ->
                            drawRect(brush, rect.topLeft, rect.size)
                        }
                        drawRect(surroundMask, blendMode = BlendMode.DstIn)
                    }
                }
            },
    )
}
