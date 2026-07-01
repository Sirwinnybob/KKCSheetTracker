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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    forceFilled: Boolean? = null,
    modifier: Modifier = Modifier
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
    showBottomProgressBar: Boolean = false,
    segmentedStatusCounts: StatusCounts? = null,
    materialSegments: List<MaterialSegmentData>? = null,
    hidePrimaryProgressBar: Boolean = false,
    showExpandToggle: Boolean = true,
    headerLeading: (@Composable ColumnScope.() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    inlineContent: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val colors = KKCThemeColors.statusColors
    val cardStatus = inferProgressStatus(segmentedStatusCounts = segmentedStatusCounts, fraction = fraction)

    StatusBorderedCard(
        status = cardStatus,
        modifier = modifier,
        onClick = onClick,
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
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
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
    dimmed: Boolean = false,
    skipped: Boolean = false,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    isSubHeader: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = KKCThemeColors.statusColors
    val safeTotal = total.coerceAtLeast(0)
    val safeDone = done.coerceAtLeast(0).coerceAtMost(safeTotal)
    val fraction = if (safeTotal <= 0) 0f else safeDone.toFloat() / safeTotal.toFloat()
    val containerColor = if (isSubHeader) {
        Color.Transparent
    } else if (dimmed) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .let { base ->
                if (onToggleExpanded != null) base.clickable { onToggleExpanded() } else base
            },
        color = containerColor,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (!isSubHeader) MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
                    else Color.Transparent
                )
        ) {
            Box(
                modifier = Modifier
                    .width(if (isSubHeader) KKCShapeTokens.statusBorderWidth else 4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
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
                        Text(
                            text = "$title • $itemCount",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (isSubHeader) FontWeight.Medium else FontWeight.Bold
                            ),
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
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
                                Icon(
                                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = if (expanded) "Collapse section" else "Expand section",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$title • $itemCount",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (isSubHeader) FontWeight.Medium else FontWeight.Bold
                            ),
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                                Icon(
                                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = if (expanded) "Collapse section" else "Expand section",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
    val completeClean = (counts.complete - bad).coerceAtLeast(0)
    val remaining = (total - (completeClean + bad + skipped)).coerceAtLeast(0)

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
                },
                shape = KKCShapeTokens.pill
            ) {}
        }
    }
}
