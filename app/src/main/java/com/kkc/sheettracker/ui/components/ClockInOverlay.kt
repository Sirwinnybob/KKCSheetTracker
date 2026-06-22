package com.kkc.sheettracker.ui.components

import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Round elapsed seconds to nearest 0.25 hr, return "N.NN hr" */
internal fun formatFractionalHours(elapsedSeconds: Long): String {
    val hours = elapsedSeconds / 3600.0
    val rounded = (hours * 4).roundToInt() / 4.0
    return "%.2f hr".format(rounded)
}

private const val EDGE_PREF_KEY_Y    = "edge_tab_y_fraction"
private const val EDGE_PREF_KEY_SIDE = "edge_tab_is_right"  // true = right (default)

// Matches the CLOCK IN button green
private val ClockGreen = Color(0xFF38A169)

@Composable
fun ClockInOverlay(
    clockInState: ClockInState,
    onClockOut: () -> Unit,
    onReturnToJob: () -> Unit,
    isCurrentPageActiveClockIn: Boolean = false,
    edgePrefs: SharedPreferences? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val snapshot = clockInState.snapshot
    if (!snapshot.isActive && !snapshot.pendingPrompt) return

    // Floating card offsets (session-only, not persisted)
    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(100f) }

    // Edge tab state — persisted
    var edgeTabYFraction by remember {
        mutableFloatStateOf(edgePrefs?.getFloat(EDGE_PREF_KEY_Y, 0.4f) ?: 0.4f)
    }
    var tabOnRight by remember {
        mutableStateOf(edgePrefs?.getBoolean(EDGE_PREF_KEY_SIDE, true) ?: true)
    }

    // Container + modal + tab measured sizes (px)
    var containerHeightPx by remember { mutableFloatStateOf(1f) }
    var containerWidthPx  by remember { mutableFloatStateOf(1f) }
    var modalWidthPx      by remember { mutableFloatStateOf(600f) }
    var modalHeightPx     by remember { mutableFloatStateOf(400f) }
    var tabWidthPx        by remember { mutableFloatStateOf(60f) }
    var tabHeightPx       by remember { mutableFloatStateOf(60f) }

    // Live elapsed counter
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

    // ── Clock-out prompt dialog ───────────────────────────────────────────
    if (snapshot.pendingPrompt) {
        AlertDialog(
            onDismissRequest = { clockInState.dismissPromptKeepActive() },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text("Clock Out?") },
            text = {
                Text(
                    "You are clocked in to job ${snapshot.jobNumber} — ${snapshot.jobName}. " +
                        "Do you want to clock out?"
                )
            },
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

    val fractionalDisplay = formatFractionalHours(elapsedSeconds)
    val h = elapsedSeconds / 3600
    val m = (elapsedSeconds % 3600) / 60
    val s = elapsedSeconds % 60
    val elapsedHhMmSs = "%02d:%02d:%02d".format(h, m, s)

    val statusLabel = if (snapshot.isPaused) "Paused" else "Clocked In"
    val statusColor = if (snapshot.isPaused) Color(0xFFD69E2E) else ClockGreen

    // ── Animation progress: 0 = expanded, 1 = fully minimized ─────────────
    val animProgress by animateFloatAsState(
        targetValue = if (snapshot.isMinimized) 1f else 0f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "minimizeProgress"
    )

    // ── Shrink pivot: the tab's center expressed as fraction of the modal ──
    // Tab top position
    val tabTopPx = edgeTabYFraction * containerHeightPx
    // Tab center in container coords
    val tabCenterX = if (tabOnRight) containerWidthPx - tabWidthPx / 2f
                     else tabWidthPx / 2f
    val tabCenterY = tabTopPx + tabHeightPx / 2f
    // Modal top-left in container coords
    val modalLeft = offsetX
    val modalTop  = offsetY
    // Pivot as fraction of modal size (can be outside 0..1 — that's fine)
    val pivotFx = if (modalWidthPx  > 0f) (tabCenterX - modalLeft) / modalWidthPx  else 1f
    val pivotFy = if (modalHeightPx > 0f) (tabCenterY - modalTop)  / modalHeightPx else 0.5f

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .zIndex(11f)
            .onGloballyPositioned { coords ->
                containerHeightPx = coords.size.height.toFloat().coerceAtLeast(1f)
                containerWidthPx  = coords.size.width.toFloat().coerceAtLeast(1f)
            }
    ) {
        // ── Edge Panel tab ────────────────────────────────────────────────
        if ((snapshot.isMinimized || animProgress > 0f) && !isCurrentPageActiveClockIn) {
            val infiniteTransition = rememberInfiniteTransition(label = "tabPulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.55f,
                targetValue  = 0.82f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(3000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "tabPulseAlpha"
            )

            val tabYPx = (edgeTabYFraction * containerHeightPx).roundToInt()

            // Shape: rounded on the "inner" side only
            val tabShape = if (tabOnRight)
                RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
            else
                RoundedCornerShape(topEnd   = 14.dp, bottomEnd   = 14.dp)

            val tabAlignment = if (tabOnRight) Alignment.TopEnd else Alignment.TopStart

            Box(
                modifier = Modifier
                    .align(tabAlignment)
                    .offset { IntOffset(x = 0, y = tabYPx) }
                    .onGloballyPositioned { coords ->
                        tabWidthPx  = coords.size.width.toFloat().coerceAtLeast(1f)
                        tabHeightPx = coords.size.height.toFloat().coerceAtLeast(1f)
                    }
                    // Drag to reposition vertically
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { _, dragAmount ->
                                val newY = edgeTabYFraction + dragAmount.y / containerHeightPx
                                edgeTabYFraction = newY.coerceIn(0.05f, 0.90f)
                            },
                            onDragEnd = {
                                edgePrefs?.edit()
                                    ?.putFloat(EDGE_PREF_KEY_Y, edgeTabYFraction)
                                    ?.apply()
                            }
                        )
                    }
                    // Tap = expand; long-press = swap sides
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { clockInState.setMinimized(false) },
                            onLongPress = {
                                tabOnRight = !tabOnRight
                                edgePrefs?.edit()
                                    ?.putBoolean(EDGE_PREF_KEY_SIDE, tabOnRight)
                                    ?.apply()
                            }
                        )
                    }
                    .shadow(4.dp, tabShape, clip = false)
                    .clip(tabShape)
                    .background(ClockGreen.copy(alpha = pulseAlpha))
                    .padding(horizontal = 9.dp, vertical = 11.dp)
            ) {
                // Arrow points inward (toward screen center)
                Icon(
                    imageVector = if (tabOnRight)
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    else
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Expand",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── Expanded floating modal ───────────────────────────────────────
        // Kept in composition during animation (animProgress < 1)
        if (animProgress < 0.99f) {
            val modalShape = RoundedCornerShape(16.dp)
            val frostedTokens = LocalKKCThemeTokens.current.frosted
            val modalSurfaceModifier = if (hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeDefaults.style(
                        backgroundColor = MaterialTheme.colorScheme.surface.copy(
                            alpha = frostedTokens.backgroundAlpha.coerceIn(0.72f, 0.95f)
                        ),
                        blurRadius = frostedTokens.blurDp.coerceAtLeast(1f).dp
                    )
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surface)
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .widthIn(min = 220.dp, max = 280.dp)
                    .onGloballyPositioned { coords ->
                        modalWidthPx  = coords.size.width.toFloat().coerceAtLeast(1f)
                        modalHeightPx = coords.size.height.toFloat().coerceAtLeast(1f)
                    }
                    // Shrink toward the tab's exact center — no translation needed,
                    // only the correct transformOrigin + scale + fade.
                    .graphicsLayer {
                        val scale = 1f - animProgress
                        scaleX = scale
                        scaleY = scale
                        alpha  = 1f - animProgress
                        transformOrigin = TransformOrigin(pivotFx, pivotFy)
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .shadow(10.dp, modalShape, clip = false)
                    .clip(modalShape)
                    .then(modalSurfaceModifier)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Status row + minimize button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor, RoundedCornerShape(50))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                statusLabel,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 13.sp,
                                color      = statusColor
                            )
                        }
                        IconButton(
                            onClick  = { clockInState.setMinimized(true) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Minimize",
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Job info
                    Text(
                        "${snapshot.jobNumber} — ${snapshot.jobName}",
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurface,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis
                    )

                    // Fractional hours + precise HH:MM:SS
                    Text(
                        fractionalDisplay,
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        elapsedHhMmSs,
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            onClick  = onReturnToJob,
                            modifier = Modifier.weight(1f)
                        ) { Text("← Return", fontSize = 11.sp) }
                        Button(
                            onClick  = onClockOut,
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53E3E)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Clock Out", fontSize = 11.sp, color = Color.White) }
                    }
                }
            }
        }
    }
}
