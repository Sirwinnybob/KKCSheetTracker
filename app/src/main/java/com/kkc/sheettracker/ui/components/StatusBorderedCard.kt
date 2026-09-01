package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.theme.KKCThemeColors

private data class StatusCardColors(
    val borderColor: Color,
    val topGradientColor: Color?
)

@Composable
fun StatusBorderedCard(
    status: SheetStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    useBounceClick: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 3.dp,
    leftBorderWidth: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = KKCThemeColors.statusColors
    val statusCardColors = when (status) {
        SheetStatus.NOT_STARTED -> StatusCardColors(
            borderColor = Color.Transparent,
            topGradientColor = null
        )
        SheetStatus.IN_PROGRESS -> StatusCardColors(
            borderColor = colors.inProgressBorder,
            topGradientColor = colors.inProgressBorder.copy(alpha = 0.08f)
        )
        SheetStatus.COMPLETE -> StatusCardColors(
            borderColor = colors.completeBorder.copy(alpha = 0.45f),
            topGradientColor = colors.completeBorder.copy(alpha = 0.12f)
        )
        SheetStatus.SKIPPED -> StatusCardColors(
            borderColor = colors.skipBorder,
            topGradientColor = colors.skipBorder.copy(alpha = 0.10f)
        )
        SheetStatus.HAS_BAD_PARTS -> StatusCardColors(
            borderColor = colors.bad,
            topGradientColor = colors.bad.copy(alpha = 0.12f)
        )
        SheetStatus.RE_NESTED -> StatusCardColors(
            borderColor = colors.completeBorder.copy(alpha = 0.35f),
            topGradientColor = colors.completeBorder.copy(alpha = 0.08f)
        )
    }

    val clickableModifier = if (onClick != null) {
        if (useBounceClick) {
            Modifier.bounceClick(onClick = onClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = tonalElevation + 2.dp,
            pressedElevation = tonalElevation + 4.dp
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (statusCardColors.topGradientColor != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    statusCardColors.topGradientColor,
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (leftBorderWidth > 0.dp && statusCardColors.borderColor != Color.Transparent) {
                            drawRect(
                                color = statusCardColors.borderColor,
                                size = size.copy(width = leftBorderWidth.toPx())
                            )
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                    content = content
                )
            }
        }
    }
}

@Composable
fun StatusBorderedCard(
    state: ProgressState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    useBounceClick: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 3.dp,
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
        useBounceClick = useBounceClick,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        leftBorderWidth = leftBorderWidth,
        content = content
    )
}
