package com.kkc.sheettracker.ui.components

import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.ClockInBilling
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * UI-only coordination between the header clock chip ([ClockInButton]) and [ClockInOverlay].
 * While any header chip is on screen the overlay stays out of the way; when none is (tabs
 * without a job header, a hidden viewer top bar) it falls back to a floating pill.
 */
internal object ClockChipCoordinator {
    /** Visible header chips per owner (nav back stack entry id, or [ROOT_OWNER] outside a NavHost). */
    val chipOwners = mutableStateMapOf<String, Int>()
    var detailsOpen by mutableStateOf(false)

    /** Header chip alpha; dropped to 0 while the pill flies in to dock, read in a graphicsLayer. */
    var chipAlpha by mutableFloatStateOf(1f)

    // Plain fields: written by the overlay/chip, only read from effects, coroutines and callbacks.
    var pillFloating = false
    var flightsEnabled = false
    var lastChipBoundsInWindow: Rect? = null
    /**
     * The overlay's current nav entry. Only that entry's chip may update [lastChipBoundsInWindow],
     * so a screen sliding out during its exit transition can't drag the pill's launch point with it.
     */
    var currentOwnerKey: String? = null

    const val ROOT_OWNER = "root"

    fun hasChipFor(ownerKey: String?): Boolean =
        if (ownerKey == null) chipOwners.isNotEmpty() else chipOwners.containsKey(ownerKey)

    fun ownsBounds(ownerKey: String): Boolean = currentOwnerKey.let { it == null || it == ownerKey }

    fun registerHeaderChip(ownerKey: String) {
        chipOwners[ownerKey] = (chipOwners[ownerKey] ?: 0) + 1
        // Hide the incoming chip before its first draw so the docking pill can land on it.
        if (pillFloating && flightsEnabled) chipAlpha = 0f
    }

    fun unregisterHeaderChip(ownerKey: String) {
        val remaining = (chipOwners[ownerKey] ?: 1) - 1
        if (remaining <= 0) chipOwners.remove(ownerKey) else chipOwners[ownerKey] = remaining
    }
}

private enum class PillPhase { Docked, Undocking, Floating, Docking }

private const val FLIGHT_MS = 420
/** Ignores chip handoffs between two job screens so the pill doesn't pop out and straight back. */
private const val UNDOCK_DEBOUNCE_MS = 90L

/** Runs [onFrame] with eased progress 0..1 over [durationMs], once per frame. */
private suspend fun runFlight(durationMs: Int, onFrame: (Float) -> Unit) {
    val startNanos = withFrameNanos { it }
    while (true) {
        val t = withFrameNanos { now -> ((now - startNanos) / 1_000_000f / durationMs).coerceIn(0f, 1f) }
        onFrame(FastOutSlowInEasing.transform(t))
        if (t >= 1f) break
    }
}

private const val PILL_PREF_KEY_X = "clock_pill_x_fraction"
private const val PILL_PREF_KEY_Y = "clock_pill_y_fraction"

/** Tabular figures keep the ticking clock from jittering width every second. */
internal val ClockDigits = TextStyle(fontFeatureSettings = "tnum")

/** Active elapsed ms for the current clock-in, ticking once a second while composed. */
@Composable
internal fun rememberClockElapsedMs(clockInState: ClockInState): Long {
    val snapshot = clockInState.snapshot
    var elapsedMs by remember { mutableLongStateOf(clockInState.elapsedActiveMs()) }
    LaunchedEffect(snapshot.isActive, snapshot.isPaused, snapshot.startTimeMs) {
        while (true) {
            elapsedMs = clockInState.elapsedActiveMs()
            if (!clockInState.snapshot.isActive) break
            delay(1_000L)
        }
    }
    return elapsedMs
}

@Composable
internal fun clockStatusColor(isPaused: Boolean): Color =
    if (isPaused) KKCThemeColors.statusColors.skip else KKCThemeColors.statusColors.complete

@Composable
fun ClockInOverlay(
    clockInState: ClockInState,
    onClockOut: () -> Unit,
    onReturnToJob: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentPageActiveClockIn: Boolean = false,
    edgePrefs: SharedPreferences? = null,
    hazeState: HazeState? = null,
    /**
     * Current nav back stack entry id. When given, only that entry's header chip counts, so the
     * pill detaches the moment navigation starts instead of after the old screen's exit transition.
     */
    headerOwnerKey: String? = null
) {
    val snapshot = clockInState.snapshot
    if (!snapshot.isActive && !snapshot.pendingPrompt) {
        LaunchedEffect(Unit) { ClockChipCoordinator.detailsOpen = false }
        return
    }

    // ── Clock-out prompt dialog ───────────────────────────────────────────
    if (snapshot.pendingPrompt) {
        LaunchedEffect(Unit) { ClockChipCoordinator.detailsOpen = false }
        AlertDialog(
            onDismissRequest = { clockInState.dismissPromptKeepActive() },
            shape = RoundedCornerShape(17.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text("Clock out?") },
            text = {
                Text(
                    "You're clocked in to ${snapshot.jobNumber} — ${snapshot.jobName}. " +
                        "Do you want to clock out?"
                )
            },
            confirmButton = {
                Button(
                    onClick = onClockOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Clock out") }
            },
            dismissButton = {
                TextButton(onClick = { clockInState.dismissPromptKeepActive() }) {
                    Text("Keep clocked in")
                }
            }
        )
        return
    }

    val elapsedMs = rememberClockElapsedMs(clockInState)
    val headerChipVisible = ClockChipCoordinator.hasChipFor(headerOwnerKey)
    val detailsOpen = ClockChipCoordinator.detailsOpen

    // Pill position as fractions of the free space so it always stays on screen.
    var pillXFraction by remember {
        mutableFloatStateOf(edgePrefs?.getFloat(PILL_PREF_KEY_X, 1f) ?: 1f)
    }
    var pillYFraction by remember {
        mutableFloatStateOf(edgePrefs?.getFloat(PILL_PREF_KEY_Y, 0.12f) ?: 0.12f)
    }
    var containerWidthPx by remember { mutableFloatStateOf(1f) }
    var containerHeightPx by remember { mutableFloatStateOf(1f) }
    var pillWidthPx by remember { mutableFloatStateOf(0f) }
    var pillHeightPx by remember { mutableFloatStateOf(0f) }
    // Before the pill's first measure (e.g. first undock after launch) use its nominal height.
    val pillHeightEstimatePx = with(LocalDensity.current) { 48.dp.toPx() }
    val freeWidthPx = (containerWidthPx - pillWidthPx).coerceAtLeast(1f)
    val freeHeightPx = (containerHeightPx - pillHeightPx).coerceAtLeast(1f)
    val pillX = pillXFraction * freeWidthPx
    val pillY = pillYFraction * freeHeightPx

    // ── Pill ⇄ header chip flight ─────────────────────────────────────────
    // null phase = not settled yet (first frame), so launching onto a job page never flies.
    val flightsEnabled = !LocalLowEndMode.current.animationsDisabled
    var phase by remember { mutableStateOf<PillPhase?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    // Pill center in overlay coords while flying; null = sitting at its saved spot.
    var flightCenter by remember { mutableStateOf<Offset?>(null) }
    var flightScale by remember { mutableFloatStateOf(1f) }
    var flightAlpha by remember { mutableFloatStateOf(1f) }

    SideEffect {
        ClockChipCoordinator.flightsEnabled = flightsEnabled
        ClockChipCoordinator.pillFloating = phase == PillPhase.Floating || phase == PillPhase.Undocking
        ClockChipCoordinator.currentOwnerKey = headerOwnerKey
    }

    val latestPillRestCenter by rememberUpdatedState(
        Offset(pillX + pillWidthPx / 2f, pillY + (pillHeightPx.takeIf { it > 0f } ?: pillHeightEstimatePx) / 2f)
    )
    val latestPillHeight by rememberUpdatedState(pillHeightPx.takeIf { it > 0f } ?: pillHeightEstimatePx)
    LaunchedEffect(headerChipVisible, flightsEnabled) {
        fun chipCenter(chip: Rect): Offset = chip.center - overlayOrigin
        fun chipScale(chip: Rect): Float = chip.height / latestPillHeight
        fun resetFlight() {
            flightCenter = null
            flightScale = 1f
            flightAlpha = 1f
        }

        if (phase == null) {
            withFrameNanos { }
            phase = if (headerChipVisible) PillPhase.Docked else PillPhase.Floating
            ClockChipCoordinator.chipAlpha = 1f
            return@LaunchedEffect
        }

        if (headerChipVisible) {
            val from = phase
            try {
                if (flightsEnabled && (from == PillPhase.Floating || from == PillPhase.Undocking) && !detailsOpen) {
                    phase = PillPhase.Docking
                    ClockChipCoordinator.chipAlpha = 0f
                    val startCenter = flightCenter ?: latestPillRestCenter
                    val startScale = flightScale
                    // Let the incoming screen lay out its chip before reading the target.
                    withFrameNanos { }
                    // Target is re-read every frame so the pill tracks a chip that slides in.
                    runFlight(FLIGHT_MS) { p ->
                        val chip = ClockChipCoordinator.lastChipBoundsInWindow
                        val targetCenter = chip?.let(::chipCenter) ?: startCenter
                        val targetScale = chip?.let(::chipScale) ?: startScale
                        flightCenter = lerp(startCenter, targetCenter, p)
                        flightScale = startScale + (targetScale - startScale) * p
                        // Crossfade into the chip over the last third of the flight.
                        val fade = ((p - 0.66f) / 0.34f).coerceIn(0f, 1f)
                        flightAlpha = 1f - fade
                        ClockChipCoordinator.chipAlpha = fade
                    }
                }
            } finally {
                ClockChipCoordinator.chipAlpha = 1f
                resetFlight()
                phase = PillPhase.Docked
            }
        } else {
            // Snapshot where the chip sat on screen before the debounce, while the old page is
            // still in place; the pill launches from exactly there.
            val launchBounds = ClockChipCoordinator.lastChipBoundsInWindow
            if (phase == PillPhase.Docked) delay(UNDOCK_DEBOUNCE_MS)
            if (flightsEnabled && phase == PillPhase.Docked && launchBounds != null) {
                phase = PillPhase.Undocking
                val startCenter = chipCenter(launchBounds)
                val startScale = chipScale(launchBounds)
                flightCenter = startCenter
                flightScale = startScale
                flightAlpha = 0.4f
                try {
                    runFlight(FLIGHT_MS) { p ->
                        // Destination is re-read every frame: the pill's measured width lands
                        // after its first frame and shifts the saved spot slightly.
                        flightCenter = lerp(startCenter, latestPillRestCenter, p)
                        flightScale = startScale + (1f - startScale) * p
                        flightAlpha = 0.4f + 0.6f * (p / 0.25f).coerceAtMost(1f)
                    }
                } finally {
                    resetFlight()
                }
            }
            phase = PillPhase.Floating
        }
    }
    val pillShown = !detailsOpen && phase != null && phase != PillPhase.Docked

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .zIndex(11f)
            .onGloballyPositioned { coords ->
                containerWidthPx = coords.size.width.toFloat().coerceAtLeast(1f)
                containerHeightPx = coords.size.height.toFloat().coerceAtLeast(1f)
                overlayOrigin = coords.positionInWindow()
            }
    ) {
        if (pillShown) {
            val flying = phase == PillPhase.Docking || phase == PillPhase.Undocking
            FloatingClockPill(
                jobNumber = snapshot.jobNumber,
                isPaused = snapshot.isPaused,
                elapsedMs = elapsedMs,
                hazeState = hazeState,
                onClick = { if (!flying) ClockChipCoordinator.detailsOpen = true },
                modifier = Modifier
                    .offset {
                        val center = flightCenter
                        if (center == null) {
                            IntOffset(pillX.roundToInt(), pillY.roundToInt())
                        } else {
                            val h = pillHeightPx.takeIf { it > 0f } ?: pillHeightEstimatePx
                            IntOffset(
                                (center.x - pillWidthPx / 2f).roundToInt(),
                                (center.y - h / 2f).roundToInt()
                            )
                        }
                    }
                    .graphicsLayer {
                        scaleX = flightScale
                        scaleY = flightScale
                        alpha = flightAlpha
                    }
                    .onGloballyPositioned { coords ->
                        pillWidthPx = coords.size.width.toFloat()
                        pillHeightPx = coords.size.height.toFloat()
                    }
                    .pointerInput(freeWidthPx, freeHeightPx) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                pillXFraction = (pillXFraction + drag.x / freeWidthPx).coerceIn(0f, 1f)
                                pillYFraction = (pillYFraction + drag.y / freeHeightPx).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                // Dock to the nearer side so the pill never parks over the middle of a sheet.
                                pillXFraction = if (pillXFraction < 0.5f) 0f else 1f
                                edgePrefs?.edit()
                                    ?.putFloat(PILL_PREF_KEY_X, pillXFraction)
                                    ?.putFloat(PILL_PREF_KEY_Y, pillYFraction)
                                    ?.apply()
                            }
                        )
                    }
            )
        }

        if (detailsOpen) {
            // Transparent tap-catcher: tapping anywhere outside the card closes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { ClockChipCoordinator.detailsOpen = false }
            )
            val cardModifier = if (headerChipVisible) {
                // Drops down from the header chip at the top-right.
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp)
            } else {
                val density = LocalDensity.current
                val cardWidthPx = with(density) { ClockDetailsWidth.toPx() }
                val x = if (pillXFraction < 0.5f) 0f else (containerWidthPx - cardWidthPx).coerceAtLeast(0f)
                Modifier.offset { IntOffset(x.roundToInt(), pillY.roundToInt()) }
            }
            ClockDetailsCard(
                jobNumber = snapshot.jobNumber,
                jobName = snapshot.jobName,
                isPaused = snapshot.isPaused,
                elapsedMs = elapsedMs,
                showGoToJob = !isCurrentPageActiveClockIn,
                onGoToJob = {
                    ClockChipCoordinator.detailsOpen = false
                    onReturnToJob()
                },
                onClockOut = {
                    ClockChipCoordinator.detailsOpen = false
                    onClockOut()
                },
                onClose = { ClockChipCoordinator.detailsOpen = false },
                modifier = cardModifier
            )
        }
    }
}

@Composable
private fun FloatingClockPill(
    jobNumber: String,
    isPaused: Boolean,
    elapsedMs: Long,
    hazeState: HazeState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = CircleShape
    val frostedTokens = LocalKKCThemeTokens.current.frosted
    // Frosted pattern from CLAUDE.md: external shadow only, then clip, then a hazeEffect fill.
    val surfaceModifier = if (hazeState != null && !LocalLowEndMode.current.blurDisabled) {
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
    Row(
        modifier = modifier
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .then(surfaceModifier)
            .clickable(onClick = onClick)
            .height(48.dp)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClockStatusDot(isPaused)
        Spacer(Modifier.width(8.dp))
        Text(
            jobNumber,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        Text(
            ClockInBilling.formatElapsed(elapsedMs),
            style = MaterialTheme.typography.titleMedium.merge(ClockDigits),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Clock details",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun ClockStatusDot(isPaused: Boolean, color: Color = clockStatusColor(isPaused)) {
    Box(
        Modifier
            .size(9.dp)
            .background(color, CircleShape)
    )
}

private val ClockDetailsWidth = 320.dp

@Composable
private fun ClockDetailsCard(
    jobNumber: String,
    jobName: String,
    isPaused: Boolean,
    elapsedMs: Long,
    showGoToJob: Boolean,
    onGoToJob: () -> Unit,
    onClockOut: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = KKCThemeColors.statusColors
    val shape = RoundedCornerShape(17.dp)
    val billedHours = ClockInBilling.billedHours(elapsedMs)
    val counted = elapsedMs >= ClockInBilling.MIN_COUNTED_MS
    val untilNext = ClockInBilling.formatCountdown(ClockInBilling.msUntilNextChange(elapsedMs))
    val hint = if (counted) {
        "Goes to ${ClockInBilling.formatHours(billedHours + 0.25)} in $untilNext"
    } else {
        "Under 7 min won't count. Counts in $untilNext"
    }

    Column(
        modifier = modifier
            .width(ClockDetailsWidth)
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            // Swallow taps so they don't reach the dismiss layer behind the card.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClockStatusDot(isPaused)
            Spacer(Modifier.width(8.dp))
            Text(
                if (isPaused) "Paused" else "Clocked in",
                style = MaterialTheme.typography.labelLarge,
                color = clockStatusColor(isPaused),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                jobNumber,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                jobName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ClockFigure(label = "Elapsed", value = ClockInBilling.formatElapsed(elapsedMs))
                ClockFigure(
                    label = "Counts as",
                    value = ClockInBilling.formatHours(billedHours),
                    valueColor = if (counted) status.complete else status.skip
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (counted) MaterialTheme.colorScheme.onSurfaceVariant else status.skip
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showGoToJob) {
                    OutlinedButton(
                        onClick = onGoToJob,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) { Text("Go to job") }
                }
                Button(
                    onClick = onClockOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) { Text("Clock out", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ClockFigure(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.merge(ClockDigits),
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}
