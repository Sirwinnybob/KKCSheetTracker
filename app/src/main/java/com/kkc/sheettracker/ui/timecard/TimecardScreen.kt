package com.kkc.sheettracker.ui.timecard

import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun TimecardScreen(store: TimecardStore) {
    val state by store.state.collectAsState()

    when (val s = state) {
        is TimecardUiState.Searching -> TimecardSearchingState()
        is TimecardUiState.NotFound -> TimecardNotFoundState()
        is TimecardUiState.Ready -> TimecardReadyState(store = store, ready = s)
    }
}

@Composable
private fun TimecardSearchingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Searching for timeclock server…",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun TimecardNotFoundState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Timeclock server not found",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Set the server IP in Settings to connect manually.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun TimecardReadyState(store: TimecardStore, ready: TimecardUiState.Ready) {
    val hazeState = remember { HazeState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Time row
            TimecardTimeRow()

            // Spacer to push numpad up — display card is overlaid via Box
            Spacer(modifier = Modifier.height(144.dp))

            // Numpad
            NumpadGrid(
                isEnabled = !ready.isLoading && ready.resultMessage == null,
                onDigit = { store.digitPressed(it) },
                onBackspace = { store.backspacePressed() }
            )

            // Action button
            TimecardActionButton(store = store, ready = ready)
        }

        // Display card overlaid on top so hazeEffect can blur the column behind it
        DisplayCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp), // below time row
            hazeState = hazeState,
            ready = ready
        )
    }
}

@Composable
private fun TimecardTimeRow() {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(1000)
        }
    }
    Text(
        text = currentTime.format(DateTimeFormatter.ofPattern("h:mm a")),
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .height(32.dp)
            .wrapContentHeight(Alignment.CenterVertically)
    )
}

@Composable
private fun DisplayCard(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    ready: TimecardUiState.Ready
) {
    val nameAlpha by animateFloatAsState(
        targetValue = when {
            ready.pin.length == 3 -> 1f
            else -> 0f
        },
        animationSpec = tween(180),
        label = "nameAlpha"
    )

    val nameColor = if (ready.pin.length == 3 && ready.matchedEmployee == null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val animatedNameColor by animateColorAsState(
        targetValue = nameColor,
        animationSpec = tween(180),
        label = "nameColor"
    )

    val statusText = when {
        ready.resultMessage != null -> ready.resultMessage
        ready.isLoading -> "Checking…"
        ready.punchStatus?.isClockedIn == true -> "CLOCKED IN"
        ready.punchStatus?.isClockedIn == false -> "CLOCKED OUT"
        else -> ""
    }
    val statusColor = when {
        ready.resultMessage != null && ready.resultIsClockIn -> MaterialTheme.colorScheme.primary
        ready.resultMessage != null -> MaterialTheme.colorScheme.tertiary
        ready.punchStatus?.isClockedIn == true -> MaterialTheme.colorScheme.primary
        ready.punchStatus?.isClockedIn == false -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(180),
        label = "statusColor"
    )

    Surface(
        modifier = modifier
            .height(144.dp)
            .hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    blurRadius = 20.dp
                )
            ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Dots row
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { index ->
                    val filled = index < ready.pin.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (filled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                    )
                }
            }

            // Name text (reserved space, animates in/out)
            val displayName = when {
                ready.pin.length == 3 && ready.matchedEmployee != null ->
                    ready.matchedEmployee.displayName.ifBlank { ready.matchedEmployee.name }
                ready.pin.length == 3 -> "Unknown PIN"
                else -> " " // non-breaking space to reserve height
            }
            Text(
                text = displayName,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = animatedNameColor,
                modifier = Modifier.alpha(nameAlpha)
            )

            // Status text (always takes space)
            Text(
                text = statusText,
                fontSize = 16.sp,
                color = animatedStatusColor
            )
        }
    }
}

@Composable
private fun NumpadGrid(
    isEnabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    when {
                        key.isEmpty() -> {
                            // Empty placeholder for grid alignment
                            Box(modifier = Modifier.weight(1f).height(68.dp))
                        }
                        key == "⌫" -> {
                            NumpadKey(
                                label = key,
                                modifier = Modifier.weight(1f),
                                enabled = isEnabled,
                                onClick = onBackspace
                            )
                        }
                        else -> {
                            NumpadKey(
                                label = key,
                                modifier = Modifier.weight(1f),
                                enabled = isEnabled,
                                onClick = { onDigit(key) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumpadKey(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(68.dp)
            .clickable(enabled = enabled, onClick = onClick),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(13.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

@Composable
private fun TimecardActionButton(store: TimecardStore, ready: TimecardUiState.Ready) {
    val isClockedIn = ready.punchStatus?.isClockedIn == true
    val buttonColors = if (isClockedIn) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    } else {
        ButtonDefaults.buttonColors()
    }

    Button(
        onClick = { store.punchPressed() },
        enabled = ready.pin.length == 3 &&
                ready.punchStatus?.found == true &&
                !ready.isLoading &&
                ready.resultMessage == null,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = buttonColors
    ) {
        when {
            ready.isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            ready.resultMessage != null -> Text(
                text = if (ready.resultIsClockIn) "✓ Clocked In" else "✓ Clocked Out",
                fontSize = 16.sp
            )
            isClockedIn -> Text(
                text = "CLOCK OUT",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            else -> Text(
                text = "CLOCK IN",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
