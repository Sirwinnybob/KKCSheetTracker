package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillAction
import com.kkc.sheettracker.ui.jobs.stationBarColor

/** One board column: Sheet Rips (items empty; rips render separately), a station, or Other. */
internal data class SpecialtyKanbanColumn(
    val id: String,
    val label: String,
    val items: List<SpecialtyResolvedItem>
)

/** Sheet Rips first (when present), then the list's station sections in order, Other last. */
internal fun buildSpecialtyKanbanColumns(
    sections: List<SpecialtyDetailSection>,
    hasSheetRips: Boolean
): List<SpecialtyKanbanColumn> = buildList {
    if (hasSheetRips) add(SpecialtyKanbanColumn(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, "Sheet Rips", emptyList()))
    sections.forEach { add(SpecialtyKanbanColumn(it.id, it.label, it.items)) }
}

/**
 * The checkbox a card shows in [columnId]: that station's toggle for station-split items, else the
 * item's single completion toggle (shared across its columns, as in the list).
 */
internal fun kanbanColumnToggle(
    toggles: List<SpecialtyChecklistToggle>,
    columnId: String
): SpecialtyChecklistToggle? =
    toggles.firstOrNull { it.completionKey == columnId } ?: toggles.singleOrNull()

internal data class KanbanStationDot(val station: SpecialtyStation, val done: Boolean)

/** The item's other stations (not [columnId]) in station order, each with its done state. */
internal fun kanbanStationDots(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    toggles: List<SpecialtyChecklistToggle>,
    stationOrder: List<SpecialtyStation>
): List<KanbanStationDot> =
    orderSpecialtyStations(resolved.item.stations, stationOrder)
        .filter { it.name != columnId }
        .map { station -> KanbanStationDot(station, kanbanColumnToggle(toggles, station.name)?.checked == true) }

/** Unchecked cards keep list order on top; checked cards keep list order at the bottom. */
internal fun orderKanbanCards(
    items: List<SpecialtyResolvedItem>,
    isDone: (SpecialtyResolvedItem) -> Boolean
): List<SpecialtyResolvedItem> {
    val (done, open) = items.partition(isDone)
    return open + done
}

/** The one-line detail under the progress bar: material, else order date. */
internal fun kanbanDetailLine(item: SpecialtyItem): String? {
    item.material?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Material: $it" }
    item.orderDate?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Order Date: $it" }
    return null
}

internal data class SpecialtyActionRowSpec(
    val actions: List<KKCPillAction>,
    val dividerAfterIndex: Int?
)

/** Specialty actions on the left, a divider, then the reference-document pills. */
internal fun specialtyActionRow(
    specialtyActions: List<KKCPillAction>,
    referenceActions: List<KKCPillAction>
): SpecialtyActionRowSpec = SpecialtyActionRowSpec(
    actions = specialtyActions + referenceActions,
    dividerAfterIndex = if (specialtyActions.isNotEmpty() && referenceActions.isNotEmpty()) {
        specialtyActions.lastIndex
    } else {
        null
    }
)

/** Header colors for the two non-station columns. */
internal val KANBAN_SHEET_RIPS_COLOR = Color(0xFF475569)
internal val KANBAN_OTHER_COLOR = Color(0xFF6B7280)

internal fun kanbanColumnColor(columnId: String): Color = when (columnId) {
    SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS -> KANBAN_SHEET_RIPS_COLOR
    SPECIALTY_VIEWER_SECTION_ID_OTHER -> KANBAN_OTHER_COLOR
    else -> stationBarColor(columnId)
}

@Composable
internal fun SpecialtyKanbanCard(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    columnColor: Color,
    toggles: List<SpecialtyChecklistToggle>,
    inFlightUpdates: Map<String, Boolean>,
    stationOrder: List<SpecialtyStation>,
    onToggle: (SpecialtyChecklistToggle, Boolean) -> Unit,
    onView: ((String) -> Unit)?,
    onEdit: (SpecialtyItem) -> Unit,
    onDelete: (String) -> Unit,
    onPatchDims: (String?, Double?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = resolved.item
    val toggle = kanbanColumnToggle(toggles, columnId)
    val done = toggle?.checked == true
    val totalSteps = toggles.size.coerceAtLeast(1)
    val completedSteps = toggles.count { it.checked }.coerceAtMost(totalSteps)
    val dots = kanbanStationDots(resolved, columnId, toggles, stationOrder)
    val detail = kanbanDetailLine(item)
    val isSawStation = SpecialtyStation.SAW in item.stations
    val showDims = item.category != SpecialtyItemCategory.TO_ORDER &&
        (isSawStation || item.dimensions != null || item.quantity != null)

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (done) 0.5f else 1f)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (toggle != null) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Checkbox(
                            checked = toggle.checked,
                            onCheckedChange = { next -> onToggle(toggle, next) },
                            enabled = isToggleEnabled(toggle.controlId, inFlightUpdates),
                            colors = CheckboxDefaults.colors(checkedColor = columnColor)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete item",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = specialtyItemTitle(item.cabinetLabel, item.cabinetNumbers, item.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$completedSteps/$totalSteps steps complete",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { completedSteps.toFloat() / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = columnColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            if (dots.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dots.forEach { dot ->
                        val dotColor = stationBarColor(dot.station.name)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (dot.done) dotColor else Color.Transparent)
                                .border(1.5.dp, dotColor, CircleShape)
                        )
                    }
                }
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.cabinetNumbers.isNotEmpty() && onView != null) {
                    Button(
                        onClick = { onView(item.cabinetNumbers.first()) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) { Text("View", style = MaterialTheme.typography.labelMedium) }
                }
                OutlinedButton(
                    onClick = { onEdit(item) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            if (showDims) {
                SpecialtyDimsSection(item = item, isSawStation = isSawStation, onPatchDims = onPatchDims)
            }
        }
    }
}

internal val KANBAN_COLUMN_WIDTH = 260.dp

@Composable
internal fun SpecialtyKanbanColumnFrame(
    label: String,
    color: Color,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    Card(
        modifier = modifier.width(KANBAN_COLUMN_WIDTH).fillMaxHeight(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = "$done/$total", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Last card clears the floating nav bar and its Add Item decoration.
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}
