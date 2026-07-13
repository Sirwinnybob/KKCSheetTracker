package com.kkc.sheettracker.ui.timecard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

@Composable
fun HsvColorPicker(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onBack: () -> Unit
) {
    val hsv = remember(initialColor) {
        FloatArray(3).also {
            android.graphics.Color.RGBToHSV(
                (initialColor.red * 255).roundToInt(),
                (initialColor.green * 255).roundToInt(),
                (initialColor.blue * 255).roundToInt(),
                it
            )
        }
    }

    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }

    val currentColor by remember(hue, saturation, value) {
        derivedStateOf {
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(currentColor)
        )

        Text("Hue", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GradientSlider(
            fraction = hue / 360f,
            colors = hueGradientColors,
            onFraction = { hue = it * 360f }
        )

        Text("Saturation", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GradientSlider(
            fraction = saturation,
            colors = listOf(
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, value.coerceAtLeast(0.15f)))),
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value.coerceAtLeast(0.15f))))
            ),
            onFraction = { saturation = it }
        )

        Text("Brightness", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GradientSlider(
            fraction = value,
            colors = listOf(
                Color.Black,
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation.coerceAtLeast(0.15f), 1f)))
            ),
            onFraction = { value = it }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(
                onClick = { onColorSelected(currentColor) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = currentColor)
            ) {
                Text(
                    "Choose",
                    color = if (value > 0.55f && saturation < 0.4f) Color.Black else Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GradientSlider(
    fraction: Float,
    colors: List<Color>,
    onFraction: (Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                onFraction((change.position.x / widthPx).coerceIn(0f, 1f))
                                change.consume()
                            }
                        }
                    }
                }
        ) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(brush = Brush.horizontalGradient(colors), cornerRadius = radius)
            val cx = fraction * size.width
            val cy = size.height / 2f
            val thumbR = size.height / 2f - 1f
            drawCircle(Color.White, radius = thumbR, center = Offset(cx, cy))
            drawCircle(
                Color.Black.copy(alpha = 0.25f),
                radius = thumbR,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
        }
    }
}

private val hueGradientColors: List<Color> = buildList {
    for (h in 0..360 step 30) {
        add(Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f))))
    }
}
