package com.kkc.sheettracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun lazySectionRowCount(total: Int, expanded: Boolean, fraction: Float, viewportRows: Int): Int = when {
    !expanded && fraction <= 0f -> 0
    fraction >= 1f && expanded -> total
    else -> minOf(total, viewportRows.coerceAtLeast(1))
}

internal class LazySectionExpansion(
    private val animation: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    private val expanded: Boolean,
    private val viewportRows: Int,
    private val snap: Boolean
) {
    private val fraction get() = if (snap) { if (expanded) 1f else 0f } else animation.value
    fun rowCount(total: Int) = lazySectionRowCount(total, expanded, fraction, viewportRows)

    // Measure only the bounded prefix at its natural height; clip and place it at the
    // shared fraction. Rows beyond a screenful join only after expansion completes.
    fun modifier(): Modifier = Modifier.clipToBounds().layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        layout(row.width, (row.height * fraction).roundToInt()) {
            row.placeRelative(0, 0)
        }
    }
}

@Composable
internal fun rememberLazySectionExpansions(
    sectionKeys: List<String>,
    expandedKeys: Set<String>,
    listState: LazyListState,
    snapKeys: Set<String> = emptySet(),
    minimumRowHeight: Dp = 36.dp
): Map<String, LazySectionExpansion> {
    val minimumRowHeightPx = with(LocalDensity.current) { minimumRowHeight.toPx() }
    // Observe only height changes, not every layout pass (which would feed back
    // through a rebuilt lazy-list provider and keep the screen drawing at idle).
    val viewportRows by remember(listState, minimumRowHeightPx) {
        derivedStateOf { ceil(listState.layoutInfo.viewportSize.height / minimumRowHeightPx).toInt() + 2 }
    }
    val animationsDisabled = LocalLowEndMode.current.animationsDisabled
    return sectionKeys.associateWith { sectionKey ->
        key(sectionKey) {
            val expanded = sectionKey in expandedKeys
            val animation = remember { Animatable(if (expanded) 1f else 0f) }
            LaunchedEffect(expanded, animationsDisabled, sectionKey in snapKeys) {
                val target = if (expanded) 1f else 0f
                if (animationsDisabled || sectionKey in snapKeys) animation.snapTo(target)
                else {
                    // Let the bounded prefix compose/measure before starting the clock,
                    // so its first composition cannot consume the visible animation.
                    if (expanded && animation.value == 0f) {
                        withFrameNanos { }
                        withFrameNanos { }
                    }
                    animation.animateTo(target, tween(220, easing = FastOutSlowInEasing))
                }
            }
            val snap = animationsDisabled || sectionKey in snapKeys
            remember(animation, expanded, viewportRows, snap) {
                LazySectionExpansion(animation, expanded, viewportRows, snap)
            }
        }
    }
}
