package com.kkc.sheettracker.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.models.StationProgress
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.PinButton
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.StatusSummaryRow
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import com.kkc.sheettracker.ui.hardwoods.toStatusCounts
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.components.kkcCardDepth

@Composable
fun UnifiedJobCard(
    modifier: Modifier = Modifier,
    model: UnifiedJobUiModel,
    adminMode: Boolean = false,
    sortByName: Boolean = false,
    onTogglePin: () -> Unit = {},
    onEditLabels: () -> Unit = {},
    dragModifier: Modifier = Modifier,
    // Grid view: job number, status chips and pin share the top line, the name gets its own
    // line (no marquee: it redrew the whole screen every vsync while idle), and every
    // variable-height section reserves space so all cards in a mode match height.
    gridLayout: Boolean = false,
    reservedStationRows: Int = 0,
    /** Deeper light-mode card depth (see kkcCardDepth); grid-view cards, including pinned ones. */
    lifted: Boolean = gridLayout,
    /** False when a container draws the depth instead (the pin flight's cross-faded copies). */
    showDepth: Boolean = true
) {
    val statusColors = KKCThemeColors.statusColors

    // For CNC/Hardwoods: used for segmented progress bar inside ProgressCard.
    // For Assembly/Specialty: not passed to ProgressCard (hidePrimaryProgressBar = true),
    // but computed here for count chips in headerActions.
    val statusCounts: StatusCounts? = remember(model.progressStyle) {
        when (val p = model.progressStyle) {
            is ProgressStyle.Cnc -> p.counts
            is ProgressStyle.Hardwoods -> p.counts.toStatusCounts()
            is ProgressStyle.Assembly -> StatusCounts(
                total = p.cncCounts.total + p.hardwoodCounts.totalPieces,
                complete = p.cncCounts.complete + p.hardwoodCounts.donePieces,
                bad = p.cncCounts.bad + p.hardwoodCounts.badPieces,
                skipped = p.cncCounts.skipped + p.hardwoodCounts.skippedPieces,
                notStarted = p.cncCounts.notStarted + p.hardwoodCounts.remainingPieces
            )
            is ProgressStyle.Specialty -> StatusCounts(
                total = p.totalItems.coerceAtLeast(0),
                complete = p.completedItems.coerceIn(0, p.totalItems.coerceAtLeast(0)),
                bad = 0,
                skipped = 0,
                notStarted = (p.totalItems - p.completedItems).coerceAtLeast(0)
            )
        }
    }

    val materialSegments: List<MaterialSegmentData>? = remember(model.progressStyle) {
        when (val p = model.progressStyle) {
            is ProgressStyle.Cnc -> p.materialSegments
            is ProgressStyle.Hardwoods -> p.docSegments
            else -> null
        }
    }

    val subtitle = remember(model.progressStyle) {
        when (val p = model.progressStyle) {
            is ProgressStyle.Cnc -> "${p.counts.complete}/${p.counts.total} complete"
            is ProgressStyle.Hardwoods -> "${p.counts.donePieces + p.counts.badPieces}/${p.counts.effectiveTotalPieces} pcs"
            is ProgressStyle.Assembly -> {
                if (p.bothModes) {
                    "CNC: ${p.cncCounts.complete}/${p.cncCounts.total} | HW: ${p.hardwoodCounts.donePieces}/${p.hardwoodCounts.totalPieces}"
                } else {
                    "${statusCounts?.complete ?: 0}/${statusCounts?.total ?: 0} items"
                }
            }
            is ProgressStyle.Specialty -> "${p.completedItems}/${p.totalItems} complete"
        }
    }

    val fraction = remember(model.progressStyle) {
        when (val p = model.progressStyle) {
            is ProgressStyle.Cnc -> p.fraction
            is ProgressStyle.Hardwoods -> p.fraction
            is ProgressStyle.Assembly -> {
                val total = p.cncCounts.total + p.hardwoodCounts.totalPieces
                if (total <= 0) 0f else (p.cncCounts.complete + p.hardwoodCounts.donePieces).toFloat() / total
            }
            is ProgressStyle.Specialty -> p.fraction
        }
    }

    // Assembly and Specialty use custom inline bars, not ProgressCard's built-in segmented bar
    val hidePrimary = model.progressStyle is ProgressStyle.Assembly ||
            model.progressStyle is ProgressStyle.Specialty
    val segmentedCounts = if (hidePrimary) null else statusCounts

    // CNC only: sheets still open in remake / custom (misc) materials. Re-nested sheets are
    // already excluded from counts.total, so a finished or re-nested material contributes 0
    // and its tag disappears.
    val openRemakeSheets = remember(model.progressStyle) {
        (model.progressStyle as? ProgressStyle.Cnc)?.materialSegments.orEmpty()
            .filter { it.isRemake }.sumOf { (it.counts.total - it.counts.complete).coerceAtLeast(0) }
    }
    val openCustomSheets = remember(model.progressStyle) {
        (model.progressStyle as? ProgressStyle.Cnc)?.materialSegments.orEmpty()
            .filter { it.isMisc }.sumOf { (it.counts.total - it.counts.complete).coerceAtLeast(0) }
    }
    val hasOpenTaggedSheets = openRemakeSheets > 0 || openCustomSheets > 0

    // Grid view, when the tags won't fit: hide them least important first, one more per level
    // (Renested, Done, Skipped, Bad, Custom, Remake). Level 0 shows everything.
    val statusChips: @Composable RowScope.(Int) -> Unit = { level ->
        model.labels.forEach { label ->
            StatusChip(
                text = label.name,
                backgroundColor = parseJobLabelColor(label.colorHex),
                contentColor = Color.White
            )
        }

        if (sortByName) {
            val pos = model.lineupPosition
            if (pos != null) {
                StatusChip(
                    text = "#$pos",
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (model.badges.contains(JobBadge.HIDDEN_IN_PRODUCTION)) {
            StatusChip(
                text = "Hidden in Production",
                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (openRemakeSheets > 0 && level < 6) {
            CountStatusChip("Remake", openRemakeSheets, statusColors.remakeBg, forceFilled = true)
        }
        if (openCustomSheets > 0 && level < 5) {
            CountStatusChip("Custom", openCustomSheets, statusColors.miscBg, forceFilled = true)
        }

        // Count chips only for CNC and Hardwoods — Assembly/Specialty use inline bars
        if (statusCounts != null && (model.progressStyle is ProgressStyle.Cnc || model.progressStyle is ProgressStyle.Hardwoods)) {
            if (level < 2) {
                CountStatusChip(
                    label = "Done",
                    count = statusCounts.complete,
                    color = statusColors.completeBorder,
                    forceFilled = statusCounts.total > 0 && statusCounts.complete >= statusCounts.total
                )
            }
            if (statusCounts.bad > 0 && level < 4) {
                CountStatusChip("Bad", statusCounts.bad, statusColors.bad)
            }
            if (statusCounts.skipped > 0 && level < 3) {
                CountStatusChip("Skip", statusCounts.skipped, statusColors.skipBorder)
            }
            if (model.progressStyle is ProgressStyle.Cnc && statusCounts.reNested > 0 && level < 1) {
                CountStatusChip("Renested", statusCounts.reNested, statusColors.completeBg)
            }
        }
    }

    val cardControls: @Composable RowScope.() -> Unit = {
        PinButton(isPinned = model.isPinned, onClick = onTogglePin)

        if (adminMode) {
            IconButton(onClick = onEditLabels) {
                Icon(Icons.Filled.Sell, contentDescription = "Edit Labels")
            }
            IconButton(modifier = dragModifier, onClick = {}) {
                Icon(Icons.Filled.DragHandle, contentDescription = "Reorder")
            }
        }
    }

    ProgressCard(
        modifier = modifier
            // Same corner shape as the ProgressCard inside, so the depth's edge follows the card.
            .then(
                if (showDepth) Modifier.kkcCardDepth(MaterialTheme.shapes.medium, lifted = lifted)
                else Modifier.clip(MaterialTheme.shapes.medium)
            )
            .background(MaterialTheme.colorScheme.surface),
        title = model.folderName,
        subtitle = subtitle,
        useBounceClick = true,
        fraction = fraction,
        expanded = false,
        onToggleExpanded = {},
        onClick = model.onCardClick,
        showBottomProgressBar = false,
        hidePrimaryProgressBar = hidePrimary,
        segmentedStatusCounts = segmentedCounts,
        materialSegments = materialSegments,
        showExpandToggle = false,
        titleContent = {
            val jobNumberStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (gridLayout) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = model.jobNumber, style = jobNumberStyle, maxLines = 1)
                    if (hasOpenTaggedSheets) {
                        FitChips(
                            modifier = Modifier.weight(1f),
                            maxLevel = 6,
                            content = { level -> statusChips(level) }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            statusChips(0)
                        }
                    }
                    cardControls()
                }
                // Always one line, even when blank, so every card in a mode keeps the same height.
                Text(
                    text = model.jobName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = model.jobNumber, style = jobNumberStyle, maxLines = 1)
                    if (model.jobName.isNotBlank()) {
                        Text(
                            text = "– ${model.jobName}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        headerActions = if (gridLayout) null else {
            {
                statusChips(0)
                cardControls()
            }
        },
        inlineContent = {
            // Assembly: dual-mode labeled progress bars (CNC blue | Hardwoods segmented)
            if (model.progressStyle is ProgressStyle.Assembly) {
                val p = model.progressStyle
                val cncStatusCounts = p.cncCounts
                val hwStatusCounts = StatusCounts(
                    total = p.hardwoodCounts.totalPieces,
                    complete = p.hardwoodCounts.donePieces,
                    bad = p.hardwoodCounts.badPieces,
                    skipped = p.hardwoodCounts.skippedPieces,
                    notStarted = p.hardwoodCounts.remainingPieces
                )
                Spacer(Modifier.height(4.dp))
                DualModeProgressBars(cncCounts = cncStatusCounts, hardwoodCounts = hwStatusCounts)
                Spacer(Modifier.height(4.dp))
            }

            // Specialty: per-station bars or single purple bar
            if (model.progressStyle is ProgressStyle.Specialty) {
                val p = model.progressStyle
                Spacer(Modifier.height(4.dp))
                Box {
                    // Invisible placeholder rows keep grid cards as tall as the job with the most stations.
                    if (gridLayout && reservedStationRows > 0) {
                        Box(Modifier.alpha(0f)) {
                            StationProgressBars(List(reservedStationRows) { StationProgress(station = "SAW", completed = 0, total = 0) })
                        }
                    }
                    when {
                        p.stationProgress.isNotEmpty() -> StationProgressBars(p.stationProgress)
                        p.totalItems > 0 -> {
                            val frac = p.fraction.coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = Color(0xFF7C3AED),
                                trackColor = Color(0xFF7C3AED).copy(alpha = 0.20f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Action chips row (Cover Sheet, View 3D, History)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (model.onViewCoverSheetClick != null || model.badges.contains(JobBadge.HAS_DELIVERY_SHEET)) {
                        FilterChip(
                            selected = false,
                            onClick = { model.onViewCoverSheetClick?.invoke() },
                            label = { Text("Delivery") }
                        )
                    }
                }

                val hasHistory = model.onHistoryClick != null || model.badges.contains(JobBadge.HAS_HISTORY)
                TextButton(
                    onClick = { model.onHistoryClick?.invoke(model.folderName) },
                    enabled = hasHistory,
                    modifier = Modifier.alpha(if (hasHistory) 1f else 0f)
                ) {
                    Text("History")
                }
            }
        }
    ) {
        if (model.progressStyle is ProgressStyle.Hardwoods) {
            val h = model.progressStyle
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusSummaryRow(statusCounts ?: h.counts.toStatusCounts())
                Text(
                    text = "Cutlists: ${h.docCount} + Rip Cut List",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Renders [content] at the lowest level (0..[maxLevel], higher = fewer chips) whose width fits;
 * anything still too wide at [maxLevel] is clipped.
 */
@Composable
private fun FitChips(
    modifier: Modifier = Modifier,
    maxLevel: Int,
    content: @Composable RowScope.(level: Int) -> Unit
) {
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        var level = 0
        var placeables = emptyList<androidx.compose.ui.layout.Placeable>()
        while (true) {
            val current = level
            placeables = subcompose(current) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { content(current) }
            }.map { it.measure(Constraints()) }
            val fits = (placeables.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth
            if (fits || level >= maxLevel) break
            level++
        }
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceAtMost(constraints.maxWidth)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.placeRelative(0, 0) } }
    }
}

// ─── Assembly: Dual-mode labeled progress bars ───────────────────────────────

@Composable
private fun DualModeProgressBars(
    cncCounts: StatusCounts,
    hardwoodCounts: StatusCounts
) {
    val cncColor = Color(0xFF2B6CB0)
    val hardwoodDone = Color(0xFF2F855A)
    val hardwoodBad = Color(0xFFC05621)
    val hardwoodSkipped = Color(0xFF718096)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CNC",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = {
                    if (cncCounts.total <= 0) 0f
                    else cncCounts.complete.toFloat() / cncCounts.total.toFloat()
                },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = cncColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Hardwoods",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            AssemblySegmentedHardwoodsBar(
                counts = hardwoodCounts,
                doneColor = hardwoodDone,
                badColor = hardwoodBad,
                skippedColor = hardwoodSkipped
            )
        }
    }
}

@Composable
private fun AssemblySegmentedHardwoodsBar(
    counts: StatusCounts,
    doneColor: Color,
    badColor: Color,
    skippedColor: Color
) {
    val total = counts.total.coerceAtLeast(0)
    val done = counts.complete.coerceIn(0, total)
    val bad = counts.bad.coerceIn(0, (total - done).coerceAtLeast(0))
    val skipped = counts.skipped.coerceIn(0, (total - done - bad).coerceAtLeast(0))
    val remaining = (total - done - bad - skipped).coerceAtLeast(0)

    if (total <= 0) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(999.dp)
        ) {}
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        if (done > 0) Surface(Modifier.weight(done.toFloat()).fillMaxHeight(), color = doneColor) {}
        if (bad > 0) Surface(Modifier.weight(bad.toFloat()).fillMaxHeight(), color = badColor) {}
        if (skipped > 0) Surface(Modifier.weight(skipped.toFloat()).fillMaxHeight(), color = skippedColor) {}
        if (remaining > 0) Surface(
            Modifier.weight(remaining.toFloat()).fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ) {}
    }
}

// ─── Specialty: Per-station progress bars ────────────────────────────────────

@Composable
private fun StationProgressBars(stationProgress: List<StationProgress>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        stationProgress.forEach { sp ->
            val frac = if (sp.total <= 0) 0f else sp.completed.toFloat() / sp.total.toFloat()
            val barColor = stationBarColor(sp.station)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${stationDisplayName(sp.station)} · ${sp.completed}/${sp.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp)
                )
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(8.dp),
                    color = barColor,
                    trackColor = barColor.copy(alpha = 0.20f)
                )
            }
        }
    }
}

private fun stationDisplayName(station: String): String = when (station.uppercase()) {
    "SAW" -> "Saw"
    "ASSEMBLY", "ASSM" -> "Assembly"
    "HARDWOODS", "HW" -> "Hardwoods"
    "SPECIALTY", "SPEC" -> "Specialty"
    "CNC" -> "CNC"
    "EDGE_BANDER", "EDGE" -> "Edge Bander"
    "DELIVERY" -> "Delivery"
    else -> station
}

internal fun stationBarColor(station: String): Color = when (station.uppercase()) {
    "SAW" -> Color(0xFFD97706)
    "ASSEMBLY", "ASSM" -> Color(0xFF2563EB)
    "HARDWOODS", "HW" -> Color(0xFF16A34A)
    "SPECIALTY", "SPEC" -> Color(0xFF7C3AED)
    "CNC" -> Color(0xFF6366F1)
    "EDGE_BANDER", "EDGE" -> Color(0xFF0891B2)
    "DELIVERY" -> Color(0xFF16A34A)
    else -> Color(0xFF6366F1)
}
