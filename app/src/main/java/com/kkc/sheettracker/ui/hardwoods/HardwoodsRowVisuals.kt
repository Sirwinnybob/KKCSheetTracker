package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.ui.theme.KKCThemeColors

enum class HardwoodsRowState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETE,
    SKIPPED,
    PARTIAL_SKIP
}

fun HardwoodsRowState.leftBorderWidth() = when (this) {
    HardwoodsRowState.NOT_STARTED -> 3.dp
    HardwoodsRowState.IN_PROGRESS -> 5.dp
    HardwoodsRowState.COMPLETE -> 5.dp
    HardwoodsRowState.SKIPPED -> 4.dp
    HardwoodsRowState.PARTIAL_SKIP -> 4.dp
}

@Immutable
data class HardwoodsRowVisualStyle(
    val rowState: HardwoodsRowState,
    val leftBorderColor: Color,
    val leftBorderWidth: Dp,
    val backgroundTint: Color,
    val widthBandColor: Color,
    val progressFillColor: Color,
    val progressTrackColor: Color,
    val skipOn: Boolean
)

fun deriveHardwoodsRowState(
    progress: HardwoodRowProgress,
    qty: Int,
    isMultiCab: Boolean,
    skippedCabCount: Int,
    totalCabs: Int
): HardwoodsRowState {
    val done = progress.doneCount.coerceIn(0, qty.coerceAtLeast(0))
    if (progress.skipped) return HardwoodsRowState.SKIPPED
    if (isMultiCab && skippedCabCount > 0) {
        return if (totalCabs > 0 && skippedCabCount >= totalCabs) {
            HardwoodsRowState.SKIPPED
        } else {
            HardwoodsRowState.PARTIAL_SKIP
        }
    }
    if (qty > 0 && done >= qty) return HardwoodsRowState.COMPLETE
    if (done > 0) return HardwoodsRowState.IN_PROGRESS
    return HardwoodsRowState.NOT_STARTED
}

@Composable
fun hardwoodsRowVisualStyle(
    state: HardwoodsRowState,
    widthBand: Color
): HardwoodsRowVisualStyle {
    val status = KKCThemeColors.statusColors
    val borderColor = when (state) {
        HardwoodsRowState.NOT_STARTED -> status.notStarted
        HardwoodsRowState.IN_PROGRESS -> status.inProgressBorder
        HardwoodsRowState.COMPLETE -> status.completeBorder
        HardwoodsRowState.SKIPPED -> status.skipBorder
        HardwoodsRowState.PARTIAL_SKIP -> status.skipBorder
    }
    val backgroundTint = when (state) {
        HardwoodsRowState.COMPLETE -> status.completeBgRow.copy(alpha = 0.35f)
        HardwoodsRowState.SKIPPED -> status.skipBgRow.copy(alpha = 0.32f)
        HardwoodsRowState.PARTIAL_SKIP -> status.skipBgRow.copy(alpha = 0.30f)
        HardwoodsRowState.IN_PROGRESS -> status.inProgressBorder.copy(alpha = 0.22f)
        HardwoodsRowState.NOT_STARTED -> widthBand.copy(alpha = 0.11f)
    }
    val progressFill = when (state) {
        HardwoodsRowState.COMPLETE -> status.completeBorder
        HardwoodsRowState.SKIPPED, HardwoodsRowState.PARTIAL_SKIP -> status.skipBorder
        HardwoodsRowState.IN_PROGRESS -> status.inProgressBorder
        HardwoodsRowState.NOT_STARTED -> status.notStarted
    }
    return HardwoodsRowVisualStyle(
        rowState = state,
        leftBorderColor = borderColor,
        leftBorderWidth = state.leftBorderWidth(),
        backgroundTint = backgroundTint,
        widthBandColor = widthBand.copy(alpha = if (state == HardwoodsRowState.COMPLETE) 0.40f else 0.82f),
        progressFillColor = progressFill,
        progressTrackColor = status.notStarted.copy(alpha = 0.28f),
        skipOn = state == HardwoodsRowState.SKIPPED || state == HardwoodsRowState.PARTIAL_SKIP
    )
}
