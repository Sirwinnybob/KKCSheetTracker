package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.theme.KKCThemeColors

private data class StatusCardColors(
    val borderColor: Color,
    val backgroundTint: Color
)

@Composable
fun StatusBorderedCard(
    status: SheetStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 1.dp,
    leftBorderWidth: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = KKCThemeColors.statusColors
    val statusCardColors = when (status) {
        SheetStatus.NOT_STARTED -> StatusCardColors(
            borderColor = Color.Transparent,
            backgroundTint = Color.Transparent
        )
        SheetStatus.IN_PROGRESS -> StatusCardColors(
            borderColor = colors.inProgressBorder,
            backgroundTint = colors.inProgressBorder.copy(alpha = 0.08f)
        )
        SheetStatus.COMPLETE -> StatusCardColors(
            borderColor = colors.completeBorder,
            backgroundTint = colors.completeBgRow
        )
        SheetStatus.SKIPPED -> StatusCardColors(
            borderColor = colors.skipBorder,
            backgroundTint = colors.skipBgRow
        )
        SheetStatus.HAS_BAD_PARTS -> StatusCardColors(
            borderColor = colors.bad,
            backgroundTint = colors.badBg.copy(alpha = 0.12f)
        )
    }
    val appliedContainerColor = if (statusCardColors.backgroundTint.alpha > 0f) {
        statusCardColors.backgroundTint.compositeOver(containerColor)
    } else {
        containerColor
    }
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = appliedContainerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = tonalElevation)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(leftBorderWidth)
                    .fillMaxHeight()
                    .background(statusCardColors.borderColor)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
fun StatusBorderedCard(
    state: ProgressState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 1.dp,
    leftBorderWidth: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val status = when (state) {
        ProgressState.NOT_STARTED -> SheetStatus.NOT_STARTED
        ProgressState.IN_PROGRESS -> SheetStatus.IN_PROGRESS
        ProgressState.COMPLETE -> SheetStatus.COMPLETE
        ProgressState.SKIPPED -> SheetStatus.SKIPPED
    }
    StatusBorderedCard(
        status = status,
        modifier = modifier,
        onClick = onClick,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        leftBorderWidth = leftBorderWidth,
        content = content
    )
}
