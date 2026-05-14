package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.ClockInState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun ClockInOverlay(
    clockInState: ClockInState,
    onClockOut: () -> Unit,
    onReturnToJob: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = clockInState.snapshot
    if (!snapshot.isActive && !snapshot.pendingPrompt) return

    // Floating draggable card
    // These must be before any conditional early returns
    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(100f) }
    var elapsedSeconds by remember { mutableLongStateOf(
        (clockInState.elapsedActiveMs() / 1000L).coerceAtLeast(0L)
    ) }

    LaunchedEffect(snapshot.isActive, snapshot.isPaused) {
        while (true) {
            val live = clockInState.snapshot
            if (!live.isActive) break
            elapsedSeconds = (clockInState.elapsedActiveMs() / 1000L).coerceAtLeast(0L)
            delay(if (live.isPaused) 250L else 1_000L)
        }
    }

    if (snapshot.pendingPrompt) {
        AlertDialog(
            onDismissRequest = { clockInState.dismissPromptKeepActive() },
            title = { Text("Clock Out?") },
            text = { Text("You are clocked in to job ${snapshot.jobNumber} — ${snapshot.jobName}. Do you want to clock out?") },
            confirmButton = {
                Button(
                    onClick = onClockOut,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53E3E))
                ) { Text("Clock Out", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { clockInState.dismissPromptKeepActive() }) {
                    Text("Keep Clocked In")
                }
            }
        )
        return
    }

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    val elapsedDisplay = "%02d:%02d:%02d".format(hours, minutes, seconds)

    val statusLabel = if (snapshot.isPaused) "Paused" else "Clocked In"
    val statusColor = if (snapshot.isPaused) Color(0xFFD69E2E) else Color(0xFF38A169)

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(11f)
    ) {
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .widthIn(min = 220.dp, max = 280.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .background(statusColor, RoundedCornerShape(50))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        statusLabel,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = statusColor
                    )
                }
                Text(
                    "${snapshot.jobNumber} — ${snapshot.jobName}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Active $elapsedDisplay",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        onClick = onReturnToJob,
                        modifier = Modifier.weight(1f)
                    ) { Text("← Return", fontSize = 11.sp) }
                    Button(
                        onClick = onClockOut,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53E3E)),
                        modifier = Modifier.weight(1f)
                    ) { Text("Clock Out", fontSize = 11.sp, color = Color.White) }
                }
            }
        }
    }
}
