package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.ClockInBilling
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.theme.KKCThemeColors

/**
 * Top-bar clock control. Idle: "Clock in". While a clock-in is active (this job or another)
 * it becomes a live chip — tap opens the shared clock details card hosted by [ClockInOverlay].
 *
 * [headerVisible] must be false while the hosting top bar is hidden (e.g. alpha-faded in a
 * fullscreen viewer) so the overlay knows to fall back to its floating pill.
 */
@Composable
fun ClockInButton(
    clockInState: ClockInState,
    isClockedInHere: Boolean,
    onClockInClick: () -> Unit,
    modifier: Modifier = Modifier,
    headerVisible: Boolean = true
) {
    val snapshot = clockInState.snapshot
    val status = KKCThemeColors.statusColors

    if (!snapshot.isActive) {
        Button(
            onClick = onClockInClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = status.complete,
                contentColor = Color.White
            ),
            modifier = modifier
        ) {
            Text("Clock in", fontWeight = FontWeight.Bold)
        }
        return
    }

    // Inside a NavHost the owner is this screen's back stack entry; the overlay matches it
    // against the current entry so the pill can leave as soon as navigation starts.
    val ownerKey = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.id
        ?: ClockChipCoordinator.ROOT_OWNER
    if (headerVisible) {
        DisposableEffect(ownerKey) {
            ClockChipCoordinator.registerHeaderChip(ownerKey)
            onDispose { ClockChipCoordinator.unregisterHeaderChip(ownerKey) }
        }
    }

    val elapsedMs = rememberClockElapsedMs(clockInState)
    val statusColor = clockStatusColor(snapshot.isPaused)
    val openDetails = { ClockChipCoordinator.detailsOpen = !ClockChipCoordinator.detailsOpen }
    val padding = PaddingValues(start = 14.dp, end = 16.dp)
    // Bounds feed the overlay's pill flight; alpha lets the docking pill crossfade into this chip.
    val chipModifier = modifier
        .onGloballyPositioned { coords ->
            if (headerVisible && ClockChipCoordinator.ownsBounds(ownerKey)) {
                ClockChipCoordinator.lastChipBoundsInWindow = coords.boundsInWindow()
            }
        }
        .graphicsLayer { alpha = ClockChipCoordinator.chipAlpha }

    if (isClockedInHere) {
        // Clocked in to this job: solid status chip with the live time. The surface ring keeps it
        // distinct when the header itself is tinted green (sheet viewer on a completed sheet).
        Button(
            onClick = openDetails,
            colors = ButtonDefaults.buttonColors(
                containerColor = statusColor,
                contentColor = Color.White
            ),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
            shape = CircleShape,
            contentPadding = padding,
            modifier = chipModifier
        ) {
            ClockChipContent(
                label = null,
                elapsedMs = elapsedMs,
                dotColor = Color.White
            )
        }
    } else {
        // Clocked in somewhere else: quieter chip naming that job. Filled with surface (not
        // transparent) so it stays legible on status-tinted headers like the sheet viewer's.
        OutlinedButton(
            onClick = openDetails,
            border = BorderStroke(1.5.dp, statusColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            shape = CircleShape,
            contentPadding = padding,
            modifier = chipModifier
        ) {
            ClockChipContent(
                label = snapshot.jobNumber,
                elapsedMs = elapsedMs,
                dotColor = statusColor
            )
        }
    }
}

@Composable
private fun ClockChipContent(label: String?, elapsedMs: Long, dotColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClockStatusDot(isPaused = false, color = dotColor)
        Spacer(Modifier.width(8.dp))
        if (label != null) {
            Text(label, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            ClockInBilling.formatElapsed(elapsedMs),
            style = MaterialTheme.typography.labelLarge.merge(ClockDigits),
            fontWeight = FontWeight.Bold
        )
    }
}
