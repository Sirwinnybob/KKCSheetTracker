package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private const val DEFAULT_TOP_WEIGHT = 0.45f
private val MIN_TOP_HEIGHT = 120.dp
private val MIN_BOTTOM_HEIGHT = 320.dp
private val HANDLE_HEIGHT = 24.dp

@Composable
fun VerticalSplitLayout(
    modifier: Modifier = Modifier,
    initialTopWeight: Float = DEFAULT_TOP_WEIGHT,
    topContent: @Composable (Modifier) -> Unit,
    bottomContent: @Composable (Modifier) -> Unit
) {
    var topHeightPx by remember { mutableFloatStateOf(-1f) }
    var totalHeight by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val minTopPx = with(density) { MIN_TOP_HEIGHT.toPx() }
    val minBottomPx = with(density) { MIN_BOTTOM_HEIGHT.toPx() }
    val handlePx = with(density) { HANDLE_HEIGHT.toPx() }

    fun clampedTopPx(target: Float): Float {
        if (totalHeight.height <= 0) return target
        val maxTop = (totalHeight.height.toFloat() - minBottomPx - handlePx).coerceAtLeast(minTopPx)
        return target.coerceIn(minTopPx, maxTop)
    }

    Column(
        modifier = modifier.onSizeChanged {
            totalHeight = it
            if (topHeightPx < 0f) {
                topHeightPx = it.height * initialTopWeight
            }
            topHeightPx = clampedTopPx(topHeightPx)
        }
    ) {
        val topHeightDp = with(density) { topHeightPx.coerceAtLeast(minTopPx).toDp() }
        topContent(Modifier.fillMaxWidth().height(topHeightDp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_HEIGHT)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        topHeightPx = clampedTopPx(topHeightPx + dragAmount.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (totalHeight.height > 0) {
                                topHeightPx = clampedTopPx(totalHeight.height * DEFAULT_TOP_WEIGHT)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KKCShapeTokens.splitDividerThickness)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(KKCShapeTokens.splitHandleBarThickness)
                    .background(
                        MaterialTheme.colorScheme.outline.copy(alpha = KKCAlpha.handleBar),
                        shape = KKCShapeTokens.pill
                    )
            )
        }

        bottomContent(Modifier.weight(1f))
    }
}
