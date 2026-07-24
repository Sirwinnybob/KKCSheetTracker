package com.kkc.sheettracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import com.kkc.sheettracker.ui.theme.KKCAlpha

/**
 * Refresh icon button that spins continuously while [loading] is true. The infinite animation
 * only exists while spinning — gating it inside the `if` keeps the topbar icon fully idle
 * (no per-frame recomposition) the rest of the time, since this button is on-screen constantly.
 */
@Composable
fun RefreshIconButton(
    loading: Boolean,
    onClick: () -> Unit,
    contentDescription: String = "Refresh"
) {
    IconButton(onClick = onClick) {
        if (loading) {
            val infiniteTransition = rememberInfiniteTransition(label = "refreshSpin")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
                label = "refreshRotation"
            )
            Icon(
                Icons.Default.Refresh,
                contentDescription = contentDescription,
                modifier = Modifier.rotate(rotation)
            )
        } else {
            Icon(Icons.Default.Refresh, contentDescription = contentDescription)
        }
    }
}

fun parseJobLabelColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color.Gray
}

data class MaterialSegmentData(
    val materialName: String,
    val counts: StatusCounts,
    val isRemake: Boolean = false
)

@Composable
fun StatusChip(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = backgroundColor,
        shape = KKCShapeTokens.pill,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = KKCSpacing.chipHorizontal, vertical = KKCSpacing.chipVertical)
        )
    }
}

@Composable
fun StatusSummaryRow(counts: StatusCounts, modifier: Modifier = Modifier) {
    val colors = KKCThemeColors.statusColors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusChip(
            "Total ${counts.total}",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatusChip("Done ${counts.complete}", colors.completeBorder, Color.White)
        StatusChip("Bad ${counts.bad}", colors.bad, Color.White)
        StatusChip("Skip ${counts.skipped}", colors.skipBorder, Color.White)
    }
}

@Composable
fun CountStatusChip(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
    forceFilled: Boolean? = null
) {
    val isFilled = forceFilled ?: (count > 0)
    val containerColor = if (isFilled) color else Color.Transparent
    val contentColor = if (isFilled) Color.White else color
    val border = if (isFilled) null else BorderStroke(1.dp, color)
    Surface(
        color = containerColor,
        contentColor = contentColor,
        border = border,
        shape = KKCShapeTokens.pill,
        modifier = modifier
    ) {
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = KKCSpacing.s, vertical = KKCSpacing.xxs)
        )
    }
}

@Composable
fun SheetStatusBadge(status: SheetStatus, modifier: Modifier = Modifier) {
    val colors = KKCThemeColors.statusColors
    val (text, color) = when (status) {
        SheetStatus.COMPLETE -> "Complete" to colors.complete
        SheetStatus.HAS_BAD_PARTS -> "Bad Parts" to colors.bad
        SheetStatus.SKIPPED -> "Skipped" to colors.skip
        SheetStatus.IN_PROGRESS -> "In Progress" to colors.inProgress
        SheetStatus.NOT_STARTED -> "Not Started" to colors.notStarted
        SheetStatus.RE_NESTED -> "Re-Nested" to colors.complete.copy(alpha = 0.5f)
    }
    Surface(
        color = color.copy(alpha = KKCAlpha.statusBadgeBg),
        shape = KKCShapeTokens.pill,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = KKCSpacing.chipHorizontal, vertical = KKCSpacing.chipVertical)
        )
    }
}

@Composable
fun ProgressCard(
    title: String,
    subtitle: String,
    fraction: Float,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    useBounceClick: Boolean = false,
    showBottomProgressBar: Boolean = false,
    segmentedStatusCounts: StatusCounts? = null,
    materialSegments: List<MaterialSegmentData>? = null,
    hidePrimaryProgressBar: Boolean = false,
    showExpandToggle: Boolean = true,
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
    titleContent: (@Composable ColumnScope.() -> Unit)? = null,
    headerLeading: (@Composable ColumnScope.() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    inlineContent: (@Composable ColumnScope.() -> Unit)? = null,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val colors = KKCThemeColors.statusColors
    val cardStatus = inferProgressStatus(segmentedStatusCounts = segmentedStatusCounts, fraction = fraction)

    StatusBorderedCard(
        status = cardStatus,
        modifier = modifier,
        onClick = onClick,
        useBounceClick = useBounceClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .padding(KKCSpacing.cardPadding)
                .animateContentSize()
        ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (headerLeading != null) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(KKCSpacing.textLineGap),
                            horizontalAlignment = Alignment.Start,
                            content = headerLeading
                        )
                        Spacer(Modifier.width(KKCSpacing.m))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (titleContent != null) {
                            titleContent()
                        } else {
                            Text(
                                title,
                                style = titleStyle ?: MaterialTheme.typography.titleMedium,
                                fontWeight = if (titleStyle != null) titleStyle.fontWeight else FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(KKCSpacing.textLineGap))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (headerActions != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.tightSpacing),
                            verticalAlignment = Alignment.CenterVertically,
                            content = headerActions
                        )
                    }
                    if (showExpandToggle) {
                        IconButton(onClick = onToggleExpanded) {
                            Icon(
                                if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (expanded) "Collapse" else "Expand"
                            )
                        }
                    }
                }

                if (!hidePrimaryProgressBar) {
                    val validMaterialSegments = materialSegments.orEmpty().filter { it.counts.total > 0 }
                    if (validMaterialSegments.size > 1) {
                        Spacer(Modifier.height(KKCSpacing.inCardSpacing))
                        MaterialSegmentedProgressBar(
                            materialSegments = validMaterialSegments,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                    } else if (segmentedStatusCounts != null) {
                        Spacer(Modifier.height(KKCSpacing.inCardSpacing))
                        StatusCountsProgressBar(
                            counts = segmentedStatusCounts,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                    } else if (showBottomProgressBar) {
                        Spacer(Modifier.height(KKCSpacing.inCardSpacing))
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = colors.completeBorder,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = KKCAlpha.outlineTrack)
                        )
                    }
                }

                if (inlineContent != null) {
                    Spacer(Modifier.height(KKCSpacing.m))
                    inlineContent.invoke(this)
                }

                if (expanded && expandedContent != null) {
                    Spacer(Modifier.height(KKCSpacing.l))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(KKCSpacing.l))
                    expandedContent.invoke(this)
                }
        }
    }
}

@Composable
fun SectionProgressHeader(
    title: String,
    itemCount: Int,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    skipped: Boolean = false,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    isSubHeader: Boolean = false
) {
    val colors = KKCThemeColors.statusColors
    val safeTotal = total.coerceAtLeast(0)
    val safeDone = done.coerceAtLeast(0).coerceAtMost(safeTotal)
    val fraction = if (safeTotal <= 0) 0f else safeDone.toFloat() / safeTotal.toFloat()
    val isComplete = safeDone >= safeTotal && safeTotal > 0 && !skipped
    val isInProgress = safeDone > 0 && !isComplete && !skipped

    val titleColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val progressColor = if (dimmed) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    } else {
        colors.completeBorder
    }
    val skippedBarColor = colors.completeBorder.copy(alpha = 0.52f)

    val textStyleFraction = if (isSubHeader) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium
    val progressBarHeight = if (isSubHeader) KKCSpacing.progressBarHeightThin else KKCSpacing.progressBarHeightStandard
    val verticalPadding = if (isSubHeader) KKCSpacing.xs else KKCSpacing.inCardSpacing
    val horizontalPadding = if (isSubHeader) KKCSpacing.m else KKCSpacing.l

    val headerShape = if (expanded && onToggleExpanded != null) {
        RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(8.dp)
    }
    val headerBorderColor = when {
        isComplete -> colors.completeBorder.copy(alpha = 0.25f)
        isInProgress -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }
    val gradientColors = when {
        skipped || dimmed -> listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
        isComplete -> listOf(
            colors.completeBorder.copy(alpha = 0.04f),
            colors.completeBorder.copy(alpha = 0.01f)
        )
        isInProgress -> listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.02f)
        )
        isSubHeader -> listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
        )
        else -> listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    }

    val accentBarColors = when {
        skipped -> listOf(colors.skipBorder, colors.skipBorder.copy(alpha = 0.6f))
        isComplete -> listOf(colors.completeBorder.copy(alpha = 0.65f), colors.completeBorder.copy(alpha = 0.35f))
        isInProgress -> listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        else -> listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "chevronRotation"
    )

    val elevation = if (expanded && onToggleExpanded != null) 0.dp else 2.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = if (expanded && onToggleExpanded != null) 0.dp else 4.dp)
            .let { base ->
                if (onToggleExpanded != null) base.clickable { onToggleExpanded() } else base
            },
        color = MaterialTheme.colorScheme.surface,
        shape = headerShape,
        shadowElevation = elevation,
        border = BorderStroke(1.dp, headerBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(gradientColors))
        ) {
            Box(
                modifier = Modifier
                    .width(if (isSubHeader) KKCShapeTokens.statusBorderWidth else 5.dp)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(accentBarColors))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            ) {
                if (isSubHeader) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "$itemCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = KKCSpacing.inCardSpacing),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.tightSpacing)
                        ) {
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(progressBarHeight),
                                color = if (skipped) skippedBarColor else progressColor,
                                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = KKCAlpha.outlineTrack)
                            )
                            Text(
                                text = "$safeDone/$safeTotal",
                                style = textStyleFraction,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xxs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (headerActions != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xxs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    content = headerActions
                                )
                            }
                            ProgressPill(
                                done = safeDone,
                                total = safeTotal,
                                state = if (skipped) ProgressState.SKIPPED else ProgressState.from(safeDone, safeTotal),
                                skippedFillColor = skippedBarColor
                            )
                            if (onToggleExpanded != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = if (expanded) "Collapse section" else "Expand section",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.rotate(chevronRotation)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "$itemCount ${if (itemCount == 1) "part" else "parts"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (headerActions != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xxs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    content = headerActions
                                )
                            }
                            ProgressPill(
                                done = safeDone,
                                total = safeTotal,
                                state = if (skipped) ProgressState.SKIPPED else ProgressState.from(safeDone, safeTotal),
                                skippedFillColor = skippedBarColor
                            )
                            if (onToggleExpanded != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = if (expanded) "Collapse section" else "Expand section",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.rotate(chevronRotation)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(KKCSpacing.tightSpacing))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(progressBarHeight),
                            color = if (skipped) skippedBarColor else progressColor,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = KKCAlpha.outlineTrack)
                        )
                        Spacer(Modifier.width(KKCSpacing.inCardSpacing))
                        Text(
                            text = "$safeDone/$safeTotal",
                            style = textStyleFraction,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun inferProgressStatus(
    segmentedStatusCounts: StatusCounts?,
    fraction: Float
): SheetStatus {
    if (segmentedStatusCounts == null) {
        return when {
            fraction >= 1f -> SheetStatus.COMPLETE
            fraction <= 0f -> SheetStatus.NOT_STARTED
            else -> SheetStatus.IN_PROGRESS
        }
    }

    val total = segmentedStatusCounts.total.coerceAtLeast(0)
    if (total <= 0) return SheetStatus.NOT_STARTED

    return when {
        segmentedStatusCounts.bad > 0 -> SheetStatus.HAS_BAD_PARTS
        segmentedStatusCounts.skipped >= total -> SheetStatus.SKIPPED
        segmentedStatusCounts.complete >= total -> SheetStatus.COMPLETE
        (segmentedStatusCounts.complete + segmentedStatusCounts.skipped) <= 0 -> SheetStatus.NOT_STARTED
        else -> SheetStatus.IN_PROGRESS
    }
}

@Composable
private fun StatusCountsProgressBar(
    counts: StatusCounts,
    modifier: Modifier = Modifier
) {
    val colors = KKCThemeColors.statusColors
    val total = counts.total.coerceAtLeast(0)
    val bad = counts.bad.coerceAtLeast(0)
    val skipped = counts.skipped.coerceAtLeast(0)
    val reNested = counts.reNested.coerceAtLeast(0)
    val completeClean = (counts.complete - bad).coerceAtLeast(0)
    val remaining = (total - (completeClean + bad + skipped + reNested)).coerceAtLeast(0)

    if (total <= 0) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = KKCShapeTokens.pill
        ) {}
        return
    }

    Row(
        modifier = modifier
            .clip(KKCShapeTokens.pill)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (completeClean > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(completeClean.toFloat())
                    .background(colors.completeBg)
            )
        }
        if (bad > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(bad.toFloat())
                    .background(colors.badBg)
            )
        }
        if (reNested > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(reNested.toFloat())
                    .background(colors.completeBg.copy(alpha = 0.35f))
            )
        }
        if (skipped > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(skipped.toFloat())
                    .background(colors.skipBg)
            )
        }
        if (remaining > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(remaining.toFloat())
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun MaterialSegmentedProgressBar(
    materialSegments: List<MaterialSegmentData>,
    modifier: Modifier = Modifier
) {
    val colors = KKCThemeColors.statusColors
    val validSegments = materialSegments.filter { it.counts.total > 0 }
    if (validSegments.isEmpty()) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = KKCShapeTokens.pill
        ) {}
        return
    }

    Row(
        modifier = modifier
            .clip(KKCShapeTokens.pill)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        validSegments.forEachIndexed { index, segment ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.5.dp)
                        .background(MaterialTheme.colorScheme.surface)
                )
            }

            val total = segment.counts.total.coerceAtLeast(0)
            val bad = segment.counts.bad.coerceAtLeast(0)
            val skipped = segment.counts.skipped.coerceAtLeast(0)
            val completeClean = (segment.counts.complete - bad).coerceAtLeast(0)
            val remaining = (total - segment.counts.complete - skipped).coerceAtLeast(0)
            val isRemakeIncomplete = segment.isRemake && remaining > 0
            val remainingColor = if (isRemakeIncomplete) colors.remakeBg else MaterialTheme.colorScheme.outlineVariant

            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(total.toFloat())
            ) {
                if (completeClean > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(completeClean.toFloat())
                            .background(colors.completeBg)
                    )
                }
                if (bad > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(bad.toFloat())
                            .background(colors.badBg)
                    )
                }
                if (skipped > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(skipped.toFloat())
                            .background(colors.skipBg)
                    )
                }
                if (remaining > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(remaining.toFloat())
                            .background(remainingColor)
                    )
                }
            }
        }
    }
}

@Composable
fun PinButton(
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        Icon(
            imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = if (isPinned) "Unpin job" else "Pin job",
            tint = if (isPinned) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PageStatusBar(
    pageCount: Int,
    getStatus: (Int) -> SheetStatus,
    modifier: Modifier = Modifier
) {
    val colors = KKCThemeColors.statusColors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.textLineGap)
    ) {
        (1..pageCount).forEach { page ->
            Surface(
                modifier = Modifier
                    .height(8.dp)
                    .weight(1f),
                color = when (getStatus(page)) {
                    SheetStatus.COMPLETE -> colors.completeBg
                    SheetStatus.HAS_BAD_PARTS -> colors.badBg
                    SheetStatus.SKIPPED -> colors.skipBg
                    SheetStatus.IN_PROGRESS -> colors.inProgress
                    SheetStatus.NOT_STARTED -> MaterialTheme.colorScheme.outlineVariant
                    SheetStatus.RE_NESTED -> colors.completeBg.copy(alpha = 0.35f)
                },
                shape = KKCShapeTokens.pill
            ) {}
        }
    }
}
