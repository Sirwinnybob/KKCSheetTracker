package com.kkc.sheettracker.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

fun Modifier.bounceClick(
    scaleDown: Float = 0.97f,
    onClick: () -> Unit
): Modifier = composed {
    val lowEnd = LocalLowEndMode.current
    if (lowEnd.animationsDisabled) {
        return@composed this.clickable(onClick = onClick)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "bounceScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.foundation.LocalIndication.current,
            onClick = onClick
        )
}

fun Modifier.animateEntrance(
    index: Int,
    initialLoadComplete: Boolean,
    delayUnit: Int = 30
): Modifier = composed {
    val lowEnd = LocalLowEndMode.current
    if (initialLoadComplete || lowEnd.animationsDisabled) {
        return@composed this
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(8)) * delayUnit.toLong())
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "entranceAlpha"
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 16.dp,
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "entranceOffsetY"
    )
    this
        .alpha(alpha)
        .offset(y = offsetY)
}

/** Horizontal room [scrollShadowBleed] adds so child shadows aren't clipped at a scroll edge. */
val ScrollShadowBleed = 4.dp

/**
 * `horizontalScroll` / `LazyRow` clip along their scroll axis, cutting off the shadow of any
 * child at the viewport's left or right edge. Apply this BEFORE the scroll modifier: it widens
 * the viewport by [bleed] on each side without changing this node's size or position. Pair it
 * with `padding(horizontal = bleed)` after `horizontalScroll`, or
 * `contentPadding = PaddingValues(horizontal = bleed)` on a `LazyRow`, so content stays put.
 */
fun Modifier.scrollShadowBleed(bleed: Dp = ScrollShadowBleed): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx() * 2
    val widened = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else Constraints.Infinity
    )
    val placeable = measurable.measure(widened)
    val width = constraints.constrainWidth(placeable.width - extra)
    layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
}
