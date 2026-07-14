package com.kkc.sheettracker.ui.supply

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ScannerReticle(
    modifier: Modifier = Modifier,
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

    Canvas(modifier = modifier.fillMaxSize()) {
        val reticlePx = reticleSize.toPx()
        val left = (size.width - reticlePx) / 2f
        val top = (size.height - reticlePx) / 2f
        val right = left + reticlePx
        val bottom = top + reticlePx

        // Dark scrim outside reticle box
        drawRect(color = Color.Black.copy(alpha = 0.55f))
        // Clear the reticle area (no actual Compose API for "erase", so overlay pattern is:
        // draw scrim, then draw a transparent rect — alpha blending handles the visual)
        drawRect(color = Color.Transparent, topLeft = Offset(left, top), size = Size(reticlePx, reticlePx))

        val cLen = cornerLength.toPx()
        val sw = strokeWidth.toPx()
        val col = cornerColor.copy(alpha = alpha)
        drawCornerBrackets(left, top, right, bottom, cLen, sw, col)
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
