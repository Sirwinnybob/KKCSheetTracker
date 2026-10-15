package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.ProgressPill
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.theme.KKCThemeColors

/** Shared cut-list and door-assembly tally, View and Skip controls. */
@Composable
internal fun HardwoodsRowActions(
    qty: Int,
    done: Int,
    rowState: HardwoodsRowState,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onComplete: () -> Unit,
    onZero: () -> Unit,
    onSkipToggle: () -> Unit,
    onJump: () -> Unit,
    isMultiCab: Boolean = false,
    skippedCabCount: Int = 0,
    totalCabs: Int = 0,
    viewEnabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val statusColors = KKCThemeColors.statusColors
    val skipped = rowState == HardwoodsRowState.SKIPPED || rowState == HardwoodsRowState.PARTIAL_SKIP
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TallyStepButton(
            icon = Icons.Default.Remove,
            contentDescription = "Done -",
            containerColor = statusColors.bad,
            enabled = true,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDecrement()
            },
            onLongClick = {
                when (hardwoodsTallyHoldTarget(skipped, done, qty, isIncrement = false)) {
                    HardwoodsTallyHoldTarget.ZERO -> {
                        onZero()
                        true
                    }
                    HardwoodsTallyHoldTarget.COMPLETE,
                    HardwoodsTallyHoldTarget.NONE -> false
                }
            }
        )
        ProgressPill(
            done = done,
            total = qty,
            state = rowState.asProgressState(),
            skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f),
            modifier = Modifier
        )
        TallyStepButton(
            icon = Icons.Default.Add,
            contentDescription = "Done +",
            containerColor = statusColors.completeBorder,
            enabled = true,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onIncrement()
            },
            onLongClick = {
                when (hardwoodsTallyHoldTarget(skipped, done, qty, isIncrement = true)) {
                    HardwoodsTallyHoldTarget.COMPLETE -> {
                        onComplete()
                        true
                    }
                    HardwoodsTallyHoldTarget.ZERO,
                    HardwoodsTallyHoldTarget.NONE -> false
                }
            }
        )
        Button(
            enabled = viewEnabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onJump()
            },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 32.dp)
        ) { Text("View", style = MaterialTheme.typography.labelSmall) }
        if (skipped) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSkipToggle()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = statusColors.skipBorder,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                if (isMultiCab) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Skip icon", modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "$skippedCabCount/$totalCabs",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text("SKIPPED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSkipToggle()
                },
                border = BorderStroke(1.dp, statusColors.skipBorder.copy(alpha = 0.85f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = statusColors.skipBorder),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                Text("Skip", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun HardwoodsRowState.asProgressState(): ProgressState = when (this) {
    HardwoodsRowState.NOT_STARTED -> ProgressState.NOT_STARTED
    HardwoodsRowState.IN_PROGRESS -> ProgressState.IN_PROGRESS
    HardwoodsRowState.COMPLETE -> ProgressState.COMPLETE
    HardwoodsRowState.SKIPPED,
    HardwoodsRowState.PARTIAL_SKIP -> ProgressState.SKIPPED
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TallyStepButton(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val backgroundColor = if (enabled) containerColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val iconTint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = modifier
            .size(hardwoodsTallyButtonSize())
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    if (onLongClick()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(14.dp))
        }
    }
}


internal enum class HardwoodsTallyHoldTarget {
    NONE,
    ZERO,
    COMPLETE
}

internal fun hardwoodsTallyHoldTarget(
    isSkipped: Boolean,
    done: Int,
    total: Int,
    isIncrement: Boolean
): HardwoodsTallyHoldTarget {
    if (isSkipped || total <= 0) return HardwoodsTallyHoldTarget.NONE
    val safeDone = done.coerceIn(0, total)
    return if (isIncrement) {
        if (safeDone < total) HardwoodsTallyHoldTarget.COMPLETE else HardwoodsTallyHoldTarget.NONE
    } else {
        if (safeDone > 0) HardwoodsTallyHoldTarget.ZERO else HardwoodsTallyHoldTarget.NONE
    }
}

internal fun hardwoodsTallyButtonSize() = 32.dp
