package com.kkc.sheettracker.ui.supply

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ScannerReticle(
    modifier: Modifier = Modifier,
    detectedBox: android.graphics.Rect? = null,
    reticleSize: Dp = 260.dp,
    cornerLength: Dp = 36.dp,
    strokeWidth: Dp = 4.dp,
    cornerColor: Color = Color.White
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reticle_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corner_alpha"
    )

    var viewWidth by remember { mutableStateOf(0f) }
    var viewHeight by remember { mutableStateOf(0f) }

    val defaultReticlePx = with(androidx.compose.ui.platform.LocalDensity.current) { reticleSize.toPx() }
    val defaultLeft = (viewWidth - defaultReticlePx) / 2f
    val defaultTop = (viewHeight - defaultReticlePx) / 2f
    val defaultRight = defaultLeft + defaultReticlePx
    val defaultBottom = defaultTop + defaultReticlePx

    // Target positions
    val targetLeft = detectedBox?.left?.toFloat() ?: defaultLeft
    val targetTop = detectedBox?.top?.toFloat() ?: defaultTop
    val targetRight = detectedBox?.right?.toFloat() ?: defaultRight
    val targetBottom = detectedBox?.bottom?.toFloat() ?: defaultBottom

    // Animations to target coordinates using a low-stiffness spring to absorb frame-to-frame coordinate jiggle
    val springSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
    val animLeft by animateFloatAsState(targetValue = targetLeft, animationSpec = springSpec, label = "left")
    val animTop by animateFloatAsState(targetValue = targetTop, animationSpec = springSpec, label = "top")
    val animRight by animateFloatAsState(targetValue = targetRight, animationSpec = springSpec, label = "right")
    val animBottom by animateFloatAsState(targetValue = targetBottom, animationSpec = springSpec, label = "bottom")

    // Color animation: white during scan, green when locked/detected
    val targetColor = if (detectedBox != null) Color.Green else cornerColor
    val animColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(durationMillis = 150), label = "color")

    Canvas(modifier = modifier.fillMaxSize().onGloballyPositioned {
        viewWidth = it.size.width.toFloat()
        viewHeight = it.size.height.toFloat()
    }) {
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas

        // Scrim alpha: when detected, dim the background slightly more for drama
        val scrimAlpha = if (detectedBox != null) 0.70f else 0.55f
        
        // Draw dark scrim outside the animated reticle box.
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset.Zero, size = Size(viewWidth, animTop)) // top
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset(0f, animBottom), size = Size(viewWidth, viewHeight - animBottom)) // bottom
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset(0f, animTop), size = Size(animLeft, animBottom - animTop)) // left
        drawRect(color = Color.Black.copy(alpha = scrimAlpha), topLeft = Offset(animRight, animTop), size = Size(viewWidth - animRight, animBottom - animTop)) // right

        val cLen = cornerLength.toPx()
        val sw = strokeWidth.toPx()
        val finalColor = if (detectedBox != null) animColor else animColor.copy(alpha = alpha)
        
        drawCornerBrackets(animLeft, animTop, animRight, animBottom, cLen, sw, finalColor)
    }
}

private fun DrawScope.drawCornerBrackets(
    left: Float, top: Float, right: Float, bottom: Float,
    len: Float, sw: Float, color: Color
) {
    val cap = StrokeCap.Round
    drawLine(color, Offset(left, top + len), Offset(left, top), sw, cap)
    drawLine(color, Offset(left, top), Offset(left + len, top), sw, cap)
    drawLine(color, Offset(right - len, top), Offset(right, top), sw, cap)
    drawLine(color, Offset(right, top), Offset(right, top + len), sw, cap)
    drawLine(color, Offset(left, bottom - len), Offset(left, bottom), sw, cap)
    drawLine(color, Offset(left, bottom), Offset(left + len, bottom), sw, cap)
    drawLine(color, Offset(right - len, bottom), Offset(right, bottom), sw, cap)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - len), sw, cap)
}
