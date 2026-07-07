package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private const val DEFAULT_FIRST_WEIGHT = 0.45f
private val MIN_FIRST_SIZE = 120.dp
private val MIN_SECOND_SIZE = 220.dp
private val HANDLE_SIZE = 24.dp

enum class SplitFullscreen { NONE, FIRST, SECOND }

@Composable
fun AdaptiveSplitLayout(
    modifier: Modifier = Modifier,
    initialFirstWeight: Float = DEFAULT_FIRST_WEIGHT,
    fullscreen: SplitFullscreen = SplitFullscreen.NONE,
    firstContent: @Composable (Modifier) -> Unit,
    secondContent: @Composable (Modifier) -> Unit
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val isLandscape = containerSize.width > containerSize.height
    if (isLandscape) {
        HorizontalSplitLayout(
            modifier = modifier,
            initialLeftWeight = initialFirstWeight,
            fullscreen = fullscreen,
            leftContent = firstContent,
            rightContent = secondContent
        )
    } else {
        VerticalSplitLayout(
            modifier = modifier,
            initialTopWeight = initialFirstWeight,
            fullscreen = fullscreen,
            topContent = firstContent,
            bottomContent = secondContent
        )
    }
}

@Composable
private fun HorizontalSplitLayout(
    modifier: Modifier = Modifier,
    initialLeftWeight: Float = DEFAULT_FIRST_WEIGHT,
    fullscreen: SplitFullscreen = SplitFullscreen.NONE,
    leftContent: @Composable (Modifier) -> Unit,
    rightContent: @Composable (Modifier) -> Unit
) {
    var leftWidthPx by remember { mutableFloatStateOf(-1f) }
    var totalSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val minLeftPx = with(density) { MIN_FIRST_SIZE.toPx() }
    val minRightPx = with(density) { MIN_SECOND_SIZE.toPx() }
    val handlePx = with(density) { HANDLE_SIZE.toPx() }

    fun clampedLeftPx(target: Float): Float {
        if (totalSize.width <= 0) return target
        val maxLeft = (totalSize.width.toFloat() - minRightPx - handlePx).coerceAtLeast(minLeftPx)
        return target.coerceIn(minLeftPx, maxLeft)
    }

    Row(
        modifier = modifier.onSizeChanged {
            totalSize = it
            if (leftWidthPx < 0f) {
                leftWidthPx = it.width * initialLeftWeight
            }
            leftWidthPx = clampedLeftPx(leftWidthPx)
        }
    ) {
        when (fullscreen) {
            SplitFullscreen.FIRST -> {
                leftContent(Modifier.fillMaxHeight().weight(1f))
                Box(Modifier.width(0.dp).fillMaxHeight()) {
                    rightContent(Modifier.fillMaxSize())
                }
            }
            SplitFullscreen.SECOND -> {
                Box(Modifier.width(0.dp).fillMaxHeight()) {
                    leftContent(Modifier.fillMaxSize())
                }
                rightContent(Modifier.fillMaxHeight().weight(1f))
            }
            SplitFullscreen.NONE -> {
                val leftDp = with(density) { leftWidthPx.coerceAtLeast(minLeftPx).toDp() }
                leftContent(Modifier.fillMaxHeight().width(leftDp))

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(HANDLE_SIZE)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                leftWidthPx = clampedLeftPx(leftWidthPx + dragAmount.x)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (totalSize.width > 0) {
                                        leftWidthPx = clampedLeftPx(totalSize.width * DEFAULT_FIRST_WEIGHT)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(KKCShapeTokens.splitDividerThickness)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Box(
                        modifier = Modifier
                            .width(KKCShapeTokens.splitHandleBarThickness)
                            .height(48.dp)
                            .background(
                                MaterialTheme.colorScheme.outline.copy(alpha = KKCAlpha.handleBar),
                                shape = KKCShapeTokens.pill
                            )
                    )
                }

                rightContent(Modifier.weight(1f))
            }
        }
    }
}

