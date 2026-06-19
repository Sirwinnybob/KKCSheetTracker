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

    fun clampedTopPx(target: Float): Float {
        if (totalHeight.height <= 0) return target
        val maxTop = (totalHeight.height.toFloat() - minBottomPx - handlePx).coerceAtLeast(minTopPx)
        return target.coerceIn(minTopPx, maxTop)
    }

    val coroutineScope = rememberCoroutineScope()
    val topHeightAnim = remember { Animatable(-1f) }

    val targetHeightPx = remember(aspectRatio, totalHeight) {
        if (aspectRatio != null && aspectRatio > 0f && totalHeight.height > 0) {
            val width = totalHeight.width.toFloat()
            clampedTopPx(width / aspectRatio)
        } else {
            null
        }
    }

    var lastTargetHeightPx by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(targetHeightPx) {
        if (targetHeightPx != null && targetHeightPx != lastTargetHeightPx) {
            lastTargetHeightPx = targetHeightPx
            if (topHeightAnim.value < 0f) {
                topHeightAnim.snapTo(targetHeightPx)
            } else {
                topHeightAnim.animateTo(
                    targetValue = targetHeightPx,
                    animationSpec = tween(durationMillis = 350)
                )
            }
        }
    }

    Column(
        modifier = modifier.onSizeChanged {
            totalHeight = it
            coroutineScope.launch {
                if (topHeightAnim.value < 0f) {
                    val initialTarget = targetHeightPx ?: clampedTopPx(it.height * initialTopWeight)
                    topHeightAnim.snapTo(initialTarget)
                } else {
                    topHeightAnim.snapTo(clampedTopPx(topHeightAnim.value))
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
                val topHeightDp = with(density) { topHeightAnim.value.coerceAtLeast(minTopPx).toDp() }
                topContent(Modifier.fillMaxWidth().height(topHeightDp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HANDLE_HEIGHT)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                coroutineScope.launch {
                                    topHeightAnim.snapTo(clampedTopPx(topHeightAnim.value + dragAmount.y))
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    coroutineScope.launch {
                                        val target = targetHeightPx ?: clampedTopPx(totalHeight.height * DEFAULT_TOP_WEIGHT)
                                        topHeightAnim.animateTo(
                                            targetValue = target,
                                            animationSpec = tween(durationMillis = 350)
                                        )
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
