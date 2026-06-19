package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.launch

private const val DEFAULT_TOP_WEIGHT = 0.45f
private val MIN_TOP_HEIGHT = 120.dp
private val MIN_BOTTOM_HEIGHT = 320.dp
private val HANDLE_HEIGHT = 24.dp

@Composable
fun VerticalSplitLayout(
    modifier: Modifier = Modifier,
    initialTopWeight: Float = DEFAULT_TOP_WEIGHT,
    aspectRatio: Float? = null,
    fullscreen: SplitFullscreen = SplitFullscreen.NONE,
    topContent: @Composable (Modifier) -> Unit,
    bottomContent: @Composable (Modifier) -> Unit
) {
    var totalHeight by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val minTopPx = with(density) { MIN_TOP_HEIGHT.toPx() }
    val minBottomPx = with(density) { MIN_BOTTOM_HEIGHT.toPx() }
    val handlePx = with(density) { HANDLE_HEIGHT.toPx() }

    fun clampedTopPxForSize(target: Float, size: IntSize): Float {
        if (size.height <= 0) return target
        val maxTop = (size.height.toFloat() - minBottomPx - handlePx).coerceAtLeast(minTopPx)
        return target.coerceIn(minTopPx, maxTop)
    }

    fun clampedTopPx(target: Float): Float = clampedTopPxForSize(target, totalHeight)

    // Synchronous height state - avoids race conditions during dragging
    var topHeightPx by remember { mutableFloatStateOf(-1f) }
    var isAutoFitting by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val anim = remember { Animatable(0f) }

    val targetHeightPx = remember(aspectRatio, totalHeight) {
        if (aspectRatio != null && aspectRatio > 0f && totalHeight.height > 0) {
            val width = totalHeight.width.toFloat()
            clampedTopPx(width / aspectRatio)
        } else {
            null
        }
    }

    // Animate transition when aspect ratio changes
    LaunchedEffect(aspectRatio) {
        if (aspectRatio != null && aspectRatio > 0f && topHeightPx > 0f && isAutoFitting) {
            val startHeight = topHeightPx
            val targetHeight = targetHeightPx ?: clampedTopPx(totalHeight.width.toFloat() / aspectRatio)
            anim.snapTo(startHeight)
            anim.animateTo(targetHeight, tween(700)) {
                topHeightPx = value
            }
        }
    }

    Column(
        modifier = modifier.onSizeChanged {
            val sizeChanged = (totalHeight != it)
            totalHeight = it

            fun getTargetHeightForSize(size: IntSize): Float {
                val rawTarget = if (aspectRatio != null && aspectRatio > 0f) {
                    size.width.toFloat() / aspectRatio
                } else {
                    size.height * initialTopWeight
                }
                return clampedTopPxForSize(rawTarget, size)
            }

            if (topHeightPx < 0f) {
                topHeightPx = getTargetHeightForSize(it)
            } else if (sizeChanged) {
                if (isAutoFitting) {
                    topHeightPx = getTargetHeightForSize(it)
                } else {
                    topHeightPx = clampedTopPxForSize(topHeightPx, it)
                }
            }
        }
    ) {
        when (fullscreen) {
            SplitFullscreen.FIRST -> {
                topContent(Modifier.fillMaxWidth().weight(1f))
                Box(Modifier.height(0.dp).fillMaxWidth()) {
                    bottomContent(Modifier.fillMaxSize())
                }
            }
            SplitFullscreen.SECOND -> {
                Box(Modifier.height(0.dp).fillMaxWidth()) {
                    topContent(Modifier.fillMaxSize())
                }
                bottomContent(Modifier.fillMaxWidth().weight(1f))
            }
            SplitFullscreen.NONE -> {
                val topHeightDp = with(density) { topHeightPx.coerceAtLeast(minTopPx).toDp() }
                topContent(Modifier.fillMaxWidth().height(topHeightDp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HANDLE_HEIGHT)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    isAutoFitting = false
                                    coroutineScope.launch {
                                        anim.stop()
                                    }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                topHeightPx = clampedTopPx(topHeightPx + dragAmount.y)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    isAutoFitting = true
                                    val target = targetHeightPx ?: clampedTopPx(totalHeight.height * initialTopWeight)
                                    coroutineScope.launch {
                                        anim.snapTo(topHeightPx)
                                        anim.animateTo(target, tween(700)) {
                                            topHeightPx = value
                                        }
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
    }
}
