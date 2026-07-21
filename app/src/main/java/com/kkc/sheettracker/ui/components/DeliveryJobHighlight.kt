package com.kkc.sheettracker.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Shared "you jumped here from the deliveries banner" effect: a slow, gentle vertical bob plus a
 * pulsing edge glow, used identically across all 4 job screens so a highlighted row looks the same
 * everywhere. Callers gate [active] off after ~1.8s (see each screen's highlight LaunchedEffect);
 * the infinite animations here only exist while [active] is true, so nothing keeps animating
 * (or recomposing) once a row settles back to normal — same idle-cost pattern as RefreshIconButton.
 *
 * No rotation, small amplitude, slow duration — shop tablets run at 0.5x system animation scale
 * and a fast/rotating effect read as a violent jiggle there; a few dp of vertical bounce reads as
 * a gentle nudge instead.
 */
fun Modifier.deliveryJobHighlight(active: Boolean): Modifier = composed {
    if (!active) return@composed this

    val transition = rememberInfiniteTransition(label = "deliveryJobHighlight")
    val bounce by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "deliveryJobBounce"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "deliveryJobGlow"
    )
    val glowColor = MaterialTheme.colorScheme.tertiary

    this
        .graphicsLayer { translationY = bounce.dp.toPx() }
        .drawWithContent {
            drawContent()
            drawRoundRect(
                color = glowColor.copy(alpha = glowAlpha),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
}
