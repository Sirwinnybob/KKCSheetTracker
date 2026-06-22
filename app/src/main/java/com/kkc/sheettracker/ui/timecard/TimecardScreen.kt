package com.kkc.sheettracker.ui.timecard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kkc.sheettracker.data.TimecardBgConfig
import com.kkc.sheettracker.data.TimecardBgStore
import com.kkc.sheettracker.ui.theme.FixedDensityWrapper
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens

private val ClockInGreen = Color(0xFF2E7D32)
private val ClockOutRed = Color(0xFFC62828)

private fun formatEmployeeName(raw: String): String {
    val comma = raw.indexOf(',')
    return if (comma > 0) "${raw.substring(comma + 1).trim()} ${raw.substring(0, comma).trim()}" else raw
}

@Composable
fun TimecardScreen(store: TimecardStore) {
    val state by store.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val bgStore = remember { TimecardBgStore(context) }
    val bgConfig by bgStore.configFlow.collectAsState(initial = TimecardBgConfig())
    val hazeState = remember { HazeState() }

    FixedDensityWrapper {
        Box(modifier = Modifier.fillMaxSize()) {
            TimeclockBackground(config = bgConfig, modifier = Modifier.fillMaxSize().hazeSource(hazeState))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                when (val s = state) {
                    is TimecardUiState.Searching -> TimecardSearchingState()
                    is TimecardUiState.NotFound -> TimecardNotFoundState()
                    is TimecardUiState.Ready -> TimecardReadyState(store = store, ready = s, hazeState = hazeState)
                }
            }
        }
    }
}

@Composable
private fun TimecardSearchingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(text = "Searching for timeclock server…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TimecardNotFoundState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            Text(text = "Timeclock server not found", style = MaterialTheme.typography.titleMedium)
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
private fun TimecardReadyState(store: TimecardStore, ready: TimecardUiState.Ready, hazeState: HazeState) {
    val frostedTokens = LocalKKCThemeTokens.current.frosted
    val isClockedIn = ready.punchStatus?.isClockedIn == true
    val isActionEnabled = ready.pin.length == 3 &&
        ready.punchStatus?.found == true &&
        !ready.isLoading &&
        ready.resultMessage == null

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.widthIn(max = 460.dp)) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimecardTimeRow()

                Spacer(modifier = Modifier.height(168.dp))

                NumpadGrid(
                    isEnabled = !ready.isLoading && ready.resultMessage == null,
                    onDigit = { store.digitPressed(it) },
                    onBackspace = { store.backspacePressed() },
                    hazeState = hazeState,
                    actionContent = {
                        val bgColor = if (isClockedIn) ClockOutRed else ClockInGreen
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (isPressed) 0.94f else 1f,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMedium,
                                dampingRatio = Spring.DampingRatioMediumBouncy
                            ),
                            label = "actionScale"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(84.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .shadow(
                                    elevation = if (isActionEnabled) 6.dp else 0.dp,
                                    shape = RoundedCornerShape(13.dp),
                                    clip = false
                                )
                                .clip(RoundedCornerShape(13.dp))
                                .hazeEffect(
                                    state = hazeState,
                                    style = HazeDefaults.style(
                                        backgroundColor = if (isActionEnabled) bgColor
                                                          else bgColor.copy(alpha = 0.35f),
                                        blurRadius = frostedTokens.blurDp.coerceAtLeast(1f).dp
                                    )
                                )
                                .clickable(
                                    enabled = isActionEnabled,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { store.punchPressed() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                ready.isLoading -> CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                ready.resultMessage != null -> Text(
                                    text = "✓",
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                isClockedIn -> Text(
                                    text = "OUT",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                else -> Text(
                                    text = "IN",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                )
            }

            DisplayCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp)
                    .padding(top = 48.dp),
                hazeState = hazeState,
                ready = ready
            )
        }
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
    val frostedTokens = LocalKKCThemeTokens.current.frosted
    val nameAlpha by animateFloatAsState(
        targetValue = if (ready.pin.length == 3) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "nameAlpha"
    )
    val nameColor = if (ready.pin.length == 3 && ready.matchedEmployee == null)
        MaterialTheme.colorScheme.error
    else
        MaterialTheme.colorScheme.onSurface
    val animatedNameColor by animateColorAsState(
        targetValue = nameColor,
        animationSpec = tween(260),
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
        ready.resultMessage != null && ready.resultIsClockIn -> ClockInGreen
        ready.resultMessage != null -> ClockOutRed
        ready.punchStatus?.isClockedIn == true -> ClockInGreen
        ready.punchStatus?.isClockedIn == false -> ClockOutRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(325),
        label = "statusColor"
    )

    Surface(
        modifier = modifier
            .height(160.dp)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = frostedTokens.backgroundAlpha.coerceIn(0.72f, 0.95f)),
                    blurRadius = (frostedTokens.blurDp * 1.7f).coerceAtLeast(1f).dp
                )
            ),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top group: digits + name close together
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Digit display — each digit slides up when entered, fades out on backspace
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) { index ->
                        val targetChar = if (index < ready.pin.length) ready.pin[index].toString() else "—"
                        AnimatedContent(
                            targetState = targetChar,
                            transitionSpec = {
                                if (targetState != "—") {
                                    (slideInVertically(tween(208)) { it } + fadeIn(tween(208))) togetherWith
                                        (slideOutVertically(tween(130)) { -it } + fadeOut(tween(104)))
                                } else {
                                    fadeIn(tween(104)) togetherWith
                                        (slideOutVertically(tween(169)) { it / 2 } + fadeOut(tween(143)))
                                }
                            },
                            label = "digit_$index"
                        ) { char ->
                            Text(
                                text = char,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = if (char != "—") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                modifier = Modifier.width(28.dp)
                            )
                        }
                    }
                }

                // Employee name — "Last, First" reversed to "First Last"
                val rawName = when {
                    ready.pin.length == 3 && ready.matchedEmployee != null ->
                        ready.matchedEmployee.displayName.ifBlank { ready.matchedEmployee.name }
                    ready.pin.length == 3 -> "Unknown PIN"
                    else -> " "
                }
                val displayName = if (rawName != "Unknown PIN" && rawName.isNotBlank())
                    formatEmployeeName(rawName) else rawName
                Text(
                    text = displayName,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = animatedNameColor,
                    modifier = Modifier.alpha(nameAlpha)
                )
            }

            // Clock status — fades between states, supports 2-line messages
            AnimatedContent(
                targetState = statusText,
                transitionSpec = {
                    fadeIn(tween(286)) togetherWith fadeOut(tween(195))
                },
                label = "statusText"
            ) { text ->
                Text(
                    text = text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    color = animatedStatusColor
                )
            }
        }
    }
}

@Composable
private fun NumpadGrid(
    isEnabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    hazeState: HazeState,
    actionContent: @Composable RowScope.() -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "ACTION")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEachIndexed { cellIndex, key ->
                    when {
                        rowIndex == 3 && cellIndex == 2 -> actionContent()
                        key == "⌫" -> NumpadKey(
                            label = key,
                            modifier = Modifier.weight(1f),
                            enabled = isEnabled,
                            hazeState = hazeState,
                            onClick = onBackspace
                        )
                        else -> NumpadKey(
                            label = key,
                            modifier = Modifier.weight(1f),
                            enabled = isEnabled,
                            hazeState = hazeState,
                            onClick = { onDigit(key) }
                        )
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
    hazeState: HazeState,
    onClick: () -> Unit
) {
    val frostedTokens = LocalKKCThemeTokens.current.frosted
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "keyScale"
    )
    Box(
        modifier = modifier
            .height(84.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(13.dp), clip = false)
            .clip(RoundedCornerShape(13.dp))
            .hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = frostedTokens.backgroundAlpha.coerceIn(0.5f, 0.95f)),
                    blurRadius = frostedTokens.blurDp.coerceAtLeast(1f).dp
                )
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
