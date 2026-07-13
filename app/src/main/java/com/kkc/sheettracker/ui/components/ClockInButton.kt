package com.kkc.sheettracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.ClockInState
import kotlinx.coroutines.delay

@Composable
fun ClockInButton(
    clockInState: ClockInState,
    isClockedInHere: Boolean,
    onClockInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = clockInState.snapshot
    val isMinimized = snapshot.isActive && snapshot.isMinimized

    if (isClockedInHere && isMinimized) {
        var elapsedSeconds by remember {
            mutableLongStateOf((clockInState.elapsedActiveMs() / 1000L).coerceAtLeast(0L))
        }

        LaunchedEffect(snapshot.isActive, snapshot.isPaused) {
            while (true) {
                val live = clockInState.snapshot
                if (!live.isActive) break
                elapsedSeconds = (clockInState.elapsedActiveMs() / 1000L).coerceAtLeast(0L)
                delay(if (live.isPaused) 250L else 1_000L)
            }
        }

        val fractionalDisplay = formatFractionalHours(elapsedSeconds)
        val statusColor = if (snapshot.isPaused) Color(0xFFD69E2E) else Color(0xFF38A169)

        Button(
            onClick = { clockInState.setMinimized(false) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            shape = RoundedCornerShape(9.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = modifier.animateContentSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, RoundedCornerShape(50))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = fractionalDisplay,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else {
        Button(
            onClick = onClockInClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF38A169),
                contentColor = Color.White
            ),
            modifier = modifier
        ) {
            Text(
                if (isClockedInHere) "● CLOCKED IN" else "CLOCK IN",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}
