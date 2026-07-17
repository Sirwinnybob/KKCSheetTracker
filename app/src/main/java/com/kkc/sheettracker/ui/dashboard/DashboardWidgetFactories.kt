package com.kkc.sheettracker.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.DashboardRecentMaterialItem
import com.kkc.sheettracker.data.models.DashboardUiModel
import com.kkc.sheettracker.data.models.HardwoodJobSummary
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SpecialtyJobCard
import com.kkc.sheettracker.ui.components.MarkdownText
import com.kkc.sheettracker.ui.components.RefreshIconButton
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.supply.supplyStatusColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxHeight
import kotlin.math.roundToInt

fun buildCncDashboardWidgets(
    dashboard: DashboardUiModel
): List<DashboardWidgetModel> {
    val completionFraction = safeFraction(dashboard.completedSheets, dashboard.totalSheets)
    val alertAccent = qualityAccent(
        badCount = dashboard.badPartsSheets,
        skippedCount = dashboard.skippedSheets
    )

    return listOf(
        DashboardWidgetModel.Hero(
            key = "cnc-hero",
            title = "Overall Progress",
            primaryValue = "${dashboard.completedSheets} of ${dashboard.totalSheets} sheets",
            secondaryValue = "${dashboard.totalJobs} ${pluralize("job", dashboard.totalJobs)} tracked",
            tertiaryValue = progressSummary(completionFraction),
            progressFraction = completionFraction,
            accent = alertAccent
        ),
        DashboardWidgetModel.StatsRow(
            key = "cnc-stats",
            stats = listOf(
                DashboardStatModel(
                    label = "Completed",
                    value = dashboard.completedSheets.toString(),
                    supportingText = "${dashboard.totalSheets} total",
                    accent = DashboardAccent.SUCCESS
                ),
                DashboardStatModel(
                    label = "Bad Parts",
                    value = dashboard.badPartsSheets.toString(),
                    accent = if (dashboard.badPartsSheets > 0) DashboardAccent.DANGER else DashboardAccent.NEUTRAL,
                    action = DashboardStatAction.BAD_PARTS
                ),
                DashboardStatModel(
                    label = "Skipped",
                    value = dashboard.skippedSheets.toString(),
                    accent = if (dashboard.skippedSheets > 0) DashboardAccent.WARNING else DashboardAccent.NEUTRAL,
                    action = if (dashboard.skippedSheets > 0) DashboardStatAction.SKIPPED else null
                ),
                DashboardStatModel(
                    label = "Jobs",
                    value = dashboard.totalJobs.toString(),
                    accent = DashboardAccent.INFO
                )
            )
        ),
        DashboardWidgetModel.AlertBlock(
            key = "cnc-alert",
            title = "Quality Review",
            message = buildQualityAlertMessage(
                badCount = dashboard.badPartsSheets,
                skippedCount = dashboard.skippedSheets
            ),
            supportingText = "${remainingCount(dashboard.completedSheets, dashboard.totalSheets)} ${pluralize("sheet", remainingCount(dashboard.completedSheets, dashboard.totalSheets))} remaining",
            accent = alertAccent
        ),
        DashboardWidgetModel.RecentItemsBlock(
            key = "cnc-recents",
            title = "Recent In-Progress Materials",
            items = dashboard.recentInProgressMaterials
                .sortedByDescending(DashboardRecentMaterialItem::lastTouchedAtMs)
                .map(::toRecentItemModel)
        )
    )
}

fun buildHardwoodsDashboardWidgets(
    totalJobs: Int,
    totalCounts: HardwoodStatusCounts,
    recentJobs: List<DashboardProgressItemModel>
): List<DashboardWidgetModel> {
    val completionFraction = safeFraction(totalCounts.donePieces, totalCounts.totalPieces)
    val remainingPieces = remainingCount(totalCounts.donePieces, totalCounts.totalPieces)
    val alertAccent = qualityAccent(
        badCount = totalCounts.badPieces,
        skippedCount = totalCounts.skippedPieces
    )

    return listOf(
        DashboardWidgetModel.Hero(
            key = "hardwoods-hero",
            title = "Hardwoods Overview",
            primaryValue = "${totalCounts.donePieces} of ${totalCounts.totalPieces} pieces",
            secondaryValue = "${totalJobs} ${pluralize("job", totalJobs)} tracked",
            tertiaryValue = progressSummary(completionFraction),
            progressFraction = completionFraction,
            accent = alertAccent
        ),
        DashboardWidgetModel.StatsRow(
            key = "hardwoods-stats",
            stats = listOf(
                DashboardStatModel(
                    label = "Done",
                    value = totalCounts.donePieces.toString(),
                    supportingText = "${totalCounts.totalPieces} total",
                    accent = DashboardAccent.SUCCESS
                ),
                DashboardStatModel(
                    label = "Bad Pieces",
                    value = totalCounts.badPieces.toString(),
                    accent = if (totalCounts.badPieces > 0) DashboardAccent.DANGER else DashboardAccent.NEUTRAL,
                    action = if (totalCounts.badPieces > 0) DashboardStatAction.BAD_PARTS else null
                ),
                DashboardStatModel(
                    label = "Skipped",
                    value = totalCounts.skippedPieces.toString(),
                    accent = if (totalCounts.skippedPieces > 0) DashboardAccent.WARNING else DashboardAccent.NEUTRAL,
                    action = if (totalCounts.skippedPieces > 0) DashboardStatAction.SKIPPED else null
                ),
                DashboardStatModel(
                    label = "Jobs",
                    value = totalJobs.toString(),
                    accent = DashboardAccent.INFO
                )
            )
        ),
        DashboardWidgetModel.AlertBlock(
            key = "hardwoods-alert",
            title = "Quality Review",
            message = buildHardwoodsQualityMessage(
                badPieces = totalCounts.badPieces,
                skippedPieces = totalCounts.skippedPieces
            ),
            supportingText = "$remainingPieces ${pluralize("piece", remainingPieces)} remaining",
            accent = alertAccent
        ),
        DashboardWidgetModel.RecentItemsBlock(
            key = "hardwoods-recents",
            title = "Recent Jobs",
            items = recentJobs,
            emptyMessage = "No recent hardwood jobs yet."
        )
    )
}

fun buildAssemblyDashboardWidgets(
    cards: List<AssemblyJobCard>,
    specialtyStatus: ScanStatus,
    specialtySummary: SpecialtySummary,
    totalCabinets: Int
): List<DashboardWidgetModel> {
    val totalCncSheets = cards.sumOf { it.cncSummary.totalSheets }
    val completedCncSheets = cards.sumOf { it.cncSummary.completedSheets }
    val completionFraction = safeFraction(completedCncSheets, totalCncSheets)
    val specialtyJobCount = specialtySummary.jobCount
    val completedSpecialtyItems = specialtySummary.completedItems
    val totalSpecialtyItems = specialtySummary.totalItems

    return listOf(
        DashboardWidgetModel.Hero(
            key = "assembly-hero",
            title = "Assembly Overview",
            primaryValue = "$totalCabinets ${pluralize("cabinet", totalCabinets)}",
            secondaryValue = "${cards.size} ${pluralize("job", cards.size)} in view",
            tertiaryValue = if (totalCncSheets > 0) {
                "$completedCncSheets of $totalCncSheets CNC sheets complete"
            } else {
                "CNC progress is ready to populate"
            },
            progressFraction = completionFraction,
            accent = if (cards.isEmpty()) DashboardAccent.NEUTRAL else DashboardAccent.INFO
        ),
        DashboardWidgetModel.AlertBlock(
            key = "assembly-specialty",
            title = "Specialty",
            message = buildSpecialtyMessage(
                specialtyStatus = specialtyStatus,
                jobCount = specialtyJobCount,
                completedItems = completedSpecialtyItems,
                totalItems = totalSpecialtyItems
            ),
            supportingText = buildSpecialtySupportingText(
                specialtyStatus = specialtyStatus,
                jobCount = specialtyJobCount
            ),
            accent = specialtyAccent(specialtyStatus, completedSpecialtyItems, totalSpecialtyItems)
        ),
        DashboardWidgetModel.JobsBlock(
            key = "assembly-jobs",
            title = "Assembly Jobs",
            items = cards.map(::toAssemblyJobItemModel),
            summary = "${cards.size} ${pluralize("job", cards.size)} • $totalCabinets ${pluralize("cabinet", totalCabinets)}"
        )
    )
}

fun buildSpecialtyDashboardWidgets(
    totalJobs: Int,
    totalItems: Int,
    completedItems: Int,
    recentJobs: List<DashboardProgressItemModel>,
    jobItems: List<DashboardProgressItemModel>,
    inProgressItems: List<DashboardProgressItemModel>
): List<DashboardWidgetModel> {
    val completionFraction = safeFraction(completedItems, totalItems)
    val remainingItems = remainingCount(completedItems, totalItems)
    return listOf(
        DashboardWidgetModel.Hero(
            key = "specialty-hero",
            title = "Specialty Overview",
            primaryValue = "$completedItems of $totalItems items",
            secondaryValue = "${totalJobs} ${pluralize("job", totalJobs)} tracked",
            tertiaryValue = progressSummary(completionFraction),
            progressFraction = completionFraction,
            accent = if (totalItems > 0 && completedItems >= totalItems) DashboardAccent.SUCCESS else DashboardAccent.INFO
        ),
        DashboardWidgetModel.StatsRow(
            key = "specialty-stats",
            stats = listOf(
                DashboardStatModel(
                    label = "Complete",
                    value = completedItems.toString(),
                    supportingText = "$totalItems total",
                    accent = DashboardAccent.SUCCESS
                ),
                DashboardStatModel(
                    label = "Remaining",
                    value = remainingItems.toString(),
                    accent = if (remainingItems > 0) DashboardAccent.WARNING else DashboardAccent.NEUTRAL
                ),
                DashboardStatModel(
                    label = "Jobs",
                    value = totalJobs.toString(),
                    accent = DashboardAccent.INFO
                )
            )
        ),
        DashboardWidgetModel.RecentItemsBlock(
            key = "specialty-in-progress",
            title = "Recent In-Progress Items",
            items = inProgressItems,
            emptyMessage = "No in-progress specialty items yet."
        ),
        DashboardWidgetModel.JobsBlock(
            key = "specialty-recents",
            title = "Recent Jobs",
            items = recentJobs,
            summary = "${recentJobs.size} ${pluralize("job", recentJobs.size)} shown",
            emptyMessage = "No specialty jobs found yet."
        ),
        DashboardWidgetModel.JobsBlock(
            key = "specialty-jobs",
            title = "All Jobs",
            items = jobItems,
            summary = "${jobItems.size} ${pluralize("job", jobItems.size)}"
        )
    )
}

fun buildSupplyCategoryWidgets(
    category: SupplyCategory,
    items: List<SupplyItem>,
    isSubscribed: Boolean,
    notificationCount: Int,
    onAddItem: (() -> Unit)? = null
): List<DashboardWidgetModel> {
    val sortedItems = items.sortedWith(
        compareBy<SupplyItem>(
            { SUPPLY_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE },
            { it.name.lowercase() }
        )
    )
    val urgentCount = sortedItems.count { (SUPPLY_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE) <= 3 }
    val categoryAccent = when {
        urgentCount > 0 -> DashboardAccent.WARNING
        isSubscribed -> DashboardAccent.INFO
        else -> DashboardAccent.NEUTRAL
    }

    return listOf(
        DashboardWidgetModel.Hero(
            key = "supply-hero-${category.id}",
            title = "${category.name} Overview",
            primaryValue = "${sortedItems.size} ${pluralize("item", sortedItems.size)}",
            secondaryValue = if (isSubscribed) "Subscribed for updates" else "Not subscribed for updates",
            tertiaryValue = if (notificationCount > 0) {
                "$notificationCount ${pluralize("notification", notificationCount)} waiting"
            } else {
                "No active notifications"
            },
            accent = categoryAccent
        ),
        DashboardWidgetModel.InventoryBlock(
            key = "supply-inventory-${category.id}",
            title = category.name,
            subtitle = if (isSubscribed) "Watching this category" else "Category overview",
            items = sortedItems.map(::toInventoryItemModel),
            summary = buildSupplySummary(
                itemCount = sortedItems.size,
                urgentCount = urgentCount,
                notificationCount = notificationCount
            ),
            onHeaderAction = onAddItem
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardShell(
    title: String,
    subtitle: String? = null,
    loading: Boolean,
    errorMessage: String? = null,
    onRefresh: (() -> Unit)? = null,
    emptyMessage: String? = null,
    hasContent: Boolean = true,
    scrollable: Boolean = true,
    topBarActions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    val displayTitle = if (!subtitle.isNullOrBlank()) {
                        "KKC Dashboard - $subtitle"
                    } else {
                        title
                    }
                    Text(
                        displayTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    if (onRefresh != null) {
                        RefreshIconButton(loading = loading, onClick = onRefresh)
                    }
                    topBarActions()
                    TopBarClock()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = floatingActionButton
    ) { padding ->
        val backgroundBrush = if (isSystemInDarkTheme()) {
            androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(
                    androidx.compose.ui.graphics.Color.Black,
                    androidx.compose.ui.graphics.Color.Black
                )
            )
        } else {
            androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(
                        if (scrollable) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (loading) {
                    LinearProgressIndicator(modifier = androidx.compose.ui.Modifier.fillMaxWidth())
                }
                if (!errorMessage.isNullOrBlank()) {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(
                            modifier = androidx.compose.ui.Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Unable to load dashboard", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(errorMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (!loading && !hasContent && emptyMessage != null) {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(
                            modifier = androidx.compose.ui.Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardWidgetRenderer(
    widgets: List<DashboardWidgetModel>,
    onStatAction: (DashboardStatAction) -> Unit = {},
    onItemClick: (DashboardItemModel) -> Unit = {},
    onItemLongPress: ((DashboardItemModel) -> Unit)? = null,
    onAlertAction: (() -> Unit)? = null
) {
    if (widgets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        widgets.forEach { widget ->
            when (widget) {
                is DashboardWidgetModel.Hero -> DashboardHeroSurface(accent = widget.accent) {
                    Text(
                        widget.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        widget.primaryValue,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    widget.secondaryValue?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    widget.tertiaryValue?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    widget.progressFraction?.let { fraction ->
                        val animatedFraction by animateFloatAsState(
                            targetValue = fraction,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessLow,
                                dampingRatio = Spring.DampingRatioNoBouncy
                            ),
                            label = "widgetProgressFraction"
                        )
                        LinearProgressIndicator(progress = { animatedFraction }, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
                    }
                }

                is DashboardWidgetModel.StatsRow -> DashboardSurfaceCard {
                    DashboardStatRowSurface {
                        widget.stats.forEach { stat ->
                            val statModifier = if (stat.action != null) {
                                androidx.compose.ui.Modifier
                                    .weight(1f)
                                    .clickable { onStatAction(stat.action) }
                            } else {
                                androidx.compose.ui.Modifier.weight(1f)
                            }
                            Surface(
                                shape = DashboardSurfaceDefaults.sectionShape,
                                modifier = statModifier,
                                color = DashboardSurfaceDefaults.accentWash(stat.accent)
                            ) {
                                Column(
                                    modifier = androidx.compose.ui.Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        stat.value,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = DashboardSurfaceDefaults.accentColor(stat.accent)
                                    )
                                    Text(
                                        stat.label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    stat.supportingText?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                is DashboardWidgetModel.AlertBlock -> DashboardSurfaceCard(accent = widget.accent) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(widget.title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(widget.message)
                        widget.supportingText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (onAlertAction != null) {
                            TextButton(onClick = onAlertAction) { Text("Open") }
                        }
                    }
                }

                is DashboardWidgetModel.RecentItemsBlock -> DashboardSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashboardSectionHeader(title = widget.title)
                        if (widget.items.isEmpty()) {
                            Text(widget.emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            widget.items.forEach { item ->
                                DashboardSurfaceCard(
                                    accent = item.accent,
                                    modifier = androidx.compose.ui.Modifier.combinedClickable(
                                        onClick = { onItemClick(item) },
                                        onLongClick = { onItemLongPress?.invoke(item) }
                                    ),
                                    contentPadding = PaddingValues(14.dp)
                                ) {
                                    Text(item.title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    Text(
                                        item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    item.supportingText?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                is DashboardWidgetModel.JobsBlock -> DashboardSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashboardSectionHeader(title = widget.title, subtitle = widget.summary)
                        if (widget.items.isEmpty()) {
                            Text(widget.emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            widget.items.forEach { item ->
                                DashboardSurfaceCard(
                                    accent = item.accent,
                                    modifier = androidx.compose.ui.Modifier.combinedClickable(
                                        onClick = { onItemClick(item) },
                                        onLongClick = { onItemLongPress?.invoke(item) }
                                    ),
                                    contentPadding = PaddingValues(14.dp)
                                ) {
                                    Text(item.title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    Text(
                                        item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    item.supportingText?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                is DashboardWidgetModel.InventoryBlock -> DashboardSurfaceCard(contentPadding = PaddingValues(0.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                DashboardSectionHeader(title = widget.title, subtitle = widget.subtitle)
                                widget.summary?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            widget.onHeaderAction?.let { action ->
                                TextButton(
                                    onClick = action,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Item", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        if (widget.items.isEmpty()) {
                            Text(widget.emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            widget.items.forEach { item ->
                                HighDensityInventoryItemRow(
                                    item = item,
                                    onClick = { onItemClick(item) },
                                    onLongClick = { onItemLongPress?.invoke(item) }
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}

private fun toRecentItemModel(item: DashboardRecentMaterialItem): DashboardProgressItemModel {
    val fraction = if (item.completionFraction > 0f) {
        item.completionFraction.coerceIn(0f, 1f)
    } else {
        safeFraction(item.counts.complete, item.counts.total)
    }
    return DashboardProgressItemModel(
        id = "${item.jobFolderName}|${item.pdfFilename}|${item.fileFingerprint}",
        title = item.materialName,
        subtitle = "${item.jobFolderName} • Next sheet ${item.nextIncompletePage}",
        supportingText = buildCountsSummary(item.counts),
        progressLabel = "${item.counts.complete}/${item.counts.total}",
        progressFraction = fraction,
        accent = countsAccent(item.counts)
    )
}

private fun toAssemblyJobItemModel(card: AssemblyJobCard): DashboardProgressItemModel {
    val cncFraction = safeFraction(card.cncSummary.completedSheets, card.cncSummary.totalSheets)
    val hardwoodFraction = safeFraction(card.hardwoodsSummary.donePieces, card.hardwoodsSummary.totalPieces)
    val progressFraction = when {
        card.hasBothModes -> listOf(cncFraction, hardwoodFraction).average().toFloat()
        card.cncSummary.totalSheets > 0 -> cncFraction
        card.hardwoodsSummary.totalPieces > 0 -> hardwoodFraction
        else -> 0f
    }.coerceIn(0f, 1f)
    val title = card.jobName.ifBlank { card.folderName }
    val subtitle = buildString {
        append(card.jobNumber.ifBlank { card.folderName })
        if (card.hasBothModes) append(" • CNC + Hardwoods")
    }
    val supportingText = buildString {
        append("CNC ${card.cncSummary.completedSheets}/${card.cncSummary.totalSheets}")
        if (card.hardwoodsSummary.totalPieces > 0) {
            append(" • HW ${card.hardwoodsSummary.donePieces}/${card.hardwoodsSummary.totalPieces}")
        }
    }
    return DashboardProgressItemModel(
        id = card.folderName,
        title = title,
        subtitle = subtitle,
        supportingText = supportingText,
        progressLabel = if (card.hasBothModes) "Dual mode" else "Assembly",
        progressFraction = progressFraction,
        accent = when {
            progressFraction >= 1f -> DashboardAccent.SUCCESS
            progressFraction > 0f -> DashboardAccent.INFO
            else -> DashboardAccent.NEUTRAL
        }
    )
}

private fun toInventoryItemModel(item: SupplyItem): DashboardInventoryItemModel {
    val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
    val sku = item.fields["sku"]?.takeIf { it.isNotBlank() }
    val supportingText = listOfNotNull(
        quantity?.let { "Qty $it" },
        item.notes?.takeIf { it.isNotBlank() }
    ).joinToString("\n").ifBlank { null }
    return DashboardInventoryItemModel(
        id = item.id,
        title = item.name,
        subtitle = "",
        supportingText = supportingText,
        badge = item.status,
        accent = supplyAccent(item.status),
        sku = sku,
        quantity = quantity,
        notes = item.notes
    )
}

private fun buildQualityAlertMessage(
    badCount: Int,
    skippedCount: Int
): String = when {
    badCount > 0 && skippedCount > 0 ->
        "$badCount bad-part ${pluralize("sheet", badCount)} and $skippedCount skipped ${pluralize("sheet", skippedCount)} need review"
    badCount > 0 ->
        "$badCount bad-part ${pluralize("sheet", badCount)} need review"
    skippedCount > 0 ->
        "$skippedCount skipped ${pluralize("sheet", skippedCount)} need review"
    else -> "No active bad-part or skipped sheet alerts."
}

private fun buildHardwoodsQualityMessage(
    badPieces: Int,
    skippedPieces: Int
): String = when {
    badPieces > 0 && skippedPieces > 0 ->
        "$badPieces bad ${pluralize("piece", badPieces)} and $skippedPieces skipped ${pluralize("piece", skippedPieces)} need review"
    badPieces > 0 ->
        "$badPieces bad ${pluralize("piece", badPieces)} need review"
    skippedPieces > 0 ->
        "$skippedPieces skipped ${pluralize("piece", skippedPieces)} need review"
    else -> "No active bad-piece or skipped-piece alerts."
}

private fun buildSpecialtyMessage(
    specialtyStatus: ScanStatus,
    jobCount: Int,
    completedItems: Int,
    totalItems: Int
): String = when (specialtyStatus) {
    ScanStatus.LOADING -> "Specialty scan is in progress."
    ScanStatus.ERROR -> "Specialty scan failed. Review the latest scan state."
    ScanStatus.IDLE -> "Specialty tracking is waiting for a refresh."
    ScanStatus.READY -> "$completedItems / $totalItems items complete across $jobCount ${pluralize("job", jobCount)}"
}

private fun buildSpecialtySupportingText(
    specialtyStatus: ScanStatus,
    jobCount: Int
): String? = when (specialtyStatus) {
    ScanStatus.READY -> "$jobCount ${pluralize("job", jobCount)} currently contribute specialty work"
    ScanStatus.LOADING -> "Counts will settle when the specialty scan finishes"
    ScanStatus.ERROR -> "Retrying or refreshing should repopulate this block"
    ScanStatus.IDLE -> null
}

private fun buildSupplySummary(
    itemCount: Int,
    urgentCount: Int,
    notificationCount: Int
): String = buildString {
    append("$itemCount ${pluralize("item", itemCount)}")
    append(" • ")
    append("$urgentCount urgent")
    append(" • ")
    append("$notificationCount ${pluralize("notification", notificationCount)}")
}

private fun buildCountsSummary(counts: StatusCounts): String = buildString {
    append("${counts.complete}/${counts.total} complete")
    if (counts.bad > 0) append(" • ${counts.bad} bad")
    if (counts.skipped > 0) append(" • ${counts.skipped} skipped")
    if (counts.notStarted > 0) append(" • ${counts.notStarted} remaining")
}

private fun progressSummary(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}% complete"

private fun qualityAccent(
    badCount: Int,
    skippedCount: Int
): DashboardAccent = when {
    badCount > 0 -> DashboardAccent.DANGER
    skippedCount > 0 -> DashboardAccent.WARNING
    else -> DashboardAccent.SUCCESS
}

private fun countsAccent(counts: StatusCounts): DashboardAccent = qualityAccent(
    badCount = counts.bad,
    skippedCount = counts.skipped
).takeUnless { counts.complete <= 0 && counts.bad <= 0 && counts.skipped <= 0 } ?: DashboardAccent.NEUTRAL

private fun specialtyAccent(
    specialtyStatus: ScanStatus,
    completedItems: Int,
    totalItems: Int
): DashboardAccent = when (specialtyStatus) {
    ScanStatus.ERROR -> DashboardAccent.DANGER
    ScanStatus.LOADING -> DashboardAccent.INFO
    ScanStatus.IDLE -> DashboardAccent.NEUTRAL
    ScanStatus.READY -> if (totalItems > 0 && completedItems >= totalItems) DashboardAccent.SUCCESS else DashboardAccent.INFO
}

internal fun supplyAccent(status: String): DashboardAccent = when (status.uppercase()) {
    "OUT", "ASAP", "MALFUNCTIONING", "NEED" -> DashboardAccent.DANGER
    "LOW" -> DashboardAccent.WARNING
    "ORDERED", "IN PROCESS", "ACKNOWLEDGED" -> DashboardAccent.INFO
    "IN STOCK", "COMPLETE", "RECEIVED" -> DashboardAccent.SUCCESS
    else -> DashboardAccent.NEUTRAL
}

private fun safeFraction(completed: Int, total: Int): Float {
    if (total <= 0) return 0f
    return (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private fun remainingCount(completed: Int, total: Int): Int = (total - completed).coerceAtLeast(0)

private fun pluralize(word: String, count: Int): String = if (count == 1) word else "${word}s"

@Composable
fun getSoftStatusColors(status: String, baseColor: Color): Pair<Color, Color> {
    val statusUpper = status.uppercase()
    return when {
        statusUpper in setOf("OUT", "ASAP", "MALFUNCTIONING", "NEED") -> {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
        statusUpper == "LOW" -> {
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        }
        statusUpper in setOf("ORDERED", "IN PROCESS", "ACKNOWLEDGED") -> {
            if (statusUpper == "ORDERED" && (baseColor == Color(0xFF388E3C) || baseColor == Color(0xFF2E7D32))) {
                Color(0xFFE8F5E9) to Color(0xFF2E7D32)
            } else {
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            }
        }
        statusUpper in setOf("IN STOCK", "COMPLETE", "RECEIVED") -> {
            Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        }
        statusUpper in setOf("NOT ORDERED", "OPEN") -> {
            Color(0xFFFFF8E1) to Color(0xFFEF6C00)
        }
        else -> {
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighDensityInventoryItemRow(
    item: DashboardInventoryItemModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val tier = item.badge?.let { SUPPLY_STATUS_PRIORITY[it] } ?: 99
    val statusColor = supplyStatusColor(tier)
    val shape = RoundedCornerShape(9.dp)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = shape, clip = false)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        // Content Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
                // Left Column: Title & Metadata
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Metadata row: SKU, notes, etc.
                    val metadataParts = remember(item.sku, item.notes) {
                        buildList {
                            if (!item.sku.isNullOrBlank()) {
                                add("SKU: ${item.sku}")
                            }
                            if (!item.notes.isNullOrBlank()) {
                                val cleanNotes = item.notes.replace("\n", " ").trim()
                                if (cleanNotes.length > 50) {
                                    add(cleanNotes.take(47) + "...")
                                } else {
                                    add(cleanNotes)
                                }
                            }
                        }
                    }
                    if (metadataParts.isNotEmpty()) {
                        Text(
                            text = metadataParts.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Right Column: Status chip & Quantity
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item.badge?.let { badge ->
                        val (chipBgColor, chipTextColor) = getSoftStatusColors(badge, statusColor)
                        StatusChip(
                            text = badge,
                            backgroundColor = chipBgColor,
                            contentColor = chipTextColor,
                            modifier = Modifier.border(
                                BorderStroke(0.5.dp, chipTextColor.copy(alpha = 0.25f)),
                                shape = CircleShape
                            )
                        )
                    }
                    
                    if (!item.quantity.isNullOrBlank()) {
                        Text(
                            text = "Qty: ${item.quantity}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
    }
}

