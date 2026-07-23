package com.kkc.sheettracker.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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

    val defaultColor = when {
        isCharging -> Color(0xFF2E7D32) // Green tint when charging
        level <= 15 -> MaterialTheme.colorScheme.error
        level <= 30 -> Color(0xFFE65100) // Orange tint for low
        else -> MaterialTheme.colorScheme.onSurface
    }
    val effectiveColor = contentColor ?: defaultColor

    val levelIcon = when {
        level <= 10 -> Icons.Filled.BatteryAlert
        level <= 20 -> Icons.Filled.Battery1Bar
        level <= 35 -> Icons.Filled.Battery2Bar
        level <= 50 -> Icons.Filled.Battery3Bar
        level <= 65 -> Icons.Filled.Battery4Bar
        level <= 80 -> Icons.Filled.Battery5Bar
        level <= 95 -> Icons.Filled.Battery6Bar
        else -> Icons.Filled.BatteryFull
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.padding(horizontal = 6.dp)
    ) {
        if (showPercentage) {
            Text(
                text = "$level%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = effectiveColor
            )
        }
        if (isCharging) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = "Charging",
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(16.dp)
            )
        }
        Icon(
            imageVector = levelIcon,
            contentDescription = "Battery $level%${if (isCharging) ", Charging" else ""}",
            tint = effectiveColor,
            modifier = Modifier.size(18.dp)
        )
    }
}
