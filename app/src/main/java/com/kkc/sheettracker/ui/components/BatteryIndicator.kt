package com.kkc.sheettracker.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.ui.theme.KKCThemeColors

data class BatteryStatus(
    val level: Int = 100,
    val isCharging: Boolean = false
)

@Composable
fun rememberBatteryStatus(): State<BatteryStatus> {
    val context = LocalContext.current
    val batteryState = remember { mutableStateOf(BatteryStatus()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let { parseBatteryIntent(it, batteryState) }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialIntent = context.registerReceiver(receiver, filter)
        initialIntent?.let { parseBatteryIntent(it, batteryState) }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }
    return batteryState
}

private fun parseBatteryIntent(intent: Intent, state: MutableState<BatteryStatus>) {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    state.value = BatteryStatus(level = pct, isCharging = isCharging)
}

@Composable
fun BatteryIndicator(
    modifier: Modifier = Modifier,
    showPercentage: Boolean = true,
    contentColor: Color? = null
) {
    val batteryStatus by rememberBatteryStatus()
    val level = batteryStatus.level
    val isCharging = batteryStatus.isCharging

    val statusColors = KKCThemeColors.statusColors
    val outlineColor = contentColor ?: MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .padding(horizontal = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Battery $level%${if (isCharging) ", Charging" else ""}"
            }
    ) {
        if (isCharging) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = statusColors.complete,
                modifier = Modifier.size(16.dp)
            )
        }
        BatteryGlyph(
            level = level,
            outlineColor = outlineColor,
            fillColor = batteryFillColor(level),
            showPercentage = showPercentage
        )
    }
}

/**
 * Charge color computed from [level]: hue sweeps continuously from red (0%) through
 * amber and yellow to green (100%). The low end is held a little brighter so a
 * nearly-empty battery still reads as an alert rather than a dark smear.
 */
internal fun batteryFillColor(level: Int): Color {
    val t = level.coerceIn(0, 100) / 100f
    val hue = 120f * t
    val saturation = 0.85f - 0.15f * t
    val value = 0.95f - 0.20f * t
    return Color.hsv(hue, saturation, value)
}

/**
 * Procedural horizontal battery: square-shouldered casing with a terminal cap, fill
 * proportional to [level], and the percentage centered inside. The text is drawn twice —
 * once in [outlineColor] over the empty part, then clipped to the filled part in a color
 * that contrasts with [fillColor] — so each glyph flips color exactly where the fill
 * passes under it and stays readable at any level.
 */
@Composable
private fun BatteryGlyph(
    level: Int,
    outlineColor: Color,
    fillColor: Color,
    showPercentage: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val fillTextColor = if (fillColor.luminance() > 0.45f) Color.Black else Color.White
    val textStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    )
    val label = "$level%"

    Canvas(modifier = modifier.size(width = 48.dp, height = 22.dp)) {
        val stroke = 2.dp.toPx()
        val capWidth = 3.5.dp.toPx()
        val capHeight = size.height * 0.46f
        val inset = 1.5.dp.toPx()
        val bodyWidth = size.width - capWidth
        val bodyCorner = 3.dp.toPx()

        // Casing (stroke is centered on the rect edge, so pull it in by half).
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = CornerRadius(bodyCorner),
            style = Stroke(width = stroke)
        )

        // Terminal cap: flush against the casing, rounded only on its outer corners.
        val capCorner = CornerRadius(capWidth * 0.6f)
        val capPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = bodyWidth - stroke / 2f,
                    top = (size.height - capHeight) / 2f,
                    right = size.width,
                    bottom = (size.height + capHeight) / 2f,
                    topLeftCornerRadius = CornerRadius.Zero,
                    topRightCornerRadius = capCorner,
                    bottomRightCornerRadius = capCorner,
                    bottomLeftCornerRadius = CornerRadius.Zero
                )
            )
        }
        drawPath(capPath, color = outlineColor)

        // Charge fill.
        val innerLeft = stroke + inset
        val innerTop = stroke + inset
        val innerWidth = bodyWidth - 2f * (stroke + inset)
        val innerHeight = size.height - 2f * (stroke + inset)
        val fillWidth = innerWidth * (level.coerceIn(0, 100) / 100f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(innerLeft, innerTop),
                size = Size(fillWidth, innerHeight),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
        }

        if (showPercentage) {
            val layout = textMeasurer.measure(label, textStyle)
            val textTopLeft = Offset(
                x = (bodyWidth - layout.size.width) / 2f,
                y = (size.height - layout.size.height) / 2f
            )
            val split = innerLeft + fillWidth
            // Empty part: text in the casing color.
            clipRect(left = split, top = 0f, right = size.width, bottom = size.height) {
                drawText(layout, color = outlineColor, topLeft = textTopLeft)
            }
            // Filled part: text in the color that contrasts with the fill.
            clipRect(left = 0f, top = 0f, right = split, bottom = size.height) {
                drawText(layout, color = fillTextColor, topLeft = textTopLeft)
            }
        }
    }
}
