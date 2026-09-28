package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.LookaheadScope
import com.kkc.sheettracker.ui.supply.CategoryColumnLayout
import com.kkc.sheettracker.ui.supply.SupplyBoardState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillAction
import com.kkc.sheettracker.ui.components.KKCPillContainer
import com.kkc.sheettracker.ui.components.KKCSlidingTabRow
import com.kkc.sheettracker.ui.components.KKCTabItem
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.rememberKKCPillStyle
import com.kkc.sheettracker.ui.jobs.stationBarColor
import com.kkc.sheettracker.ui.supply.rememberSupplyBoardState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/**
 * Everything a card shows about its checkboxes, computed once per board pass. It is compared by
 * value, so a card whose toggles didn't change skips recomposition when another card is ticked
 * (a fresh List parameter would be compared by identity and never let a card skip).
 */
@Immutable
internal data class KanbanCardToggleState(
    val toggle: SpecialtyChecklistToggle?,
    val enabled: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val dots: List<KanbanStationDot>
) {
    val done: Boolean get() = toggle?.checked == true
}

internal fun kanbanCardToggleState(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    toggles: List<SpecialtyChecklistToggle>,
    stationOrder: List<SpecialtyStation>,
    inFlightUpdates: Map<String, Boolean>
): KanbanCardToggleState {
    val toggle = kanbanColumnToggle(toggles, columnId)
    val totalSteps = toggles.size.coerceAtLeast(1)
    return KanbanCardToggleState(
        toggle = toggle,
        enabled = toggle == null || isToggleEnabled(toggle.controlId, inFlightUpdates),
        completedSteps = toggles.count { it.checked }.coerceAtMost(totalSteps),
        totalSteps = totalSteps,
        dots = kanbanStationDots(resolved, columnId, toggles, stationOrder)
    )
}

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
    val leading: List<KKCPillAction>,
    val trailing: List<KKCPillAction>
)

/** Specialty actions grouped on the left, reference-document pills grouped on the right. */
internal fun specialtyActionRow(
    specialtyActions: List<KKCPillAction>,
    referenceActions: List<KKCPillAction>
): SpecialtyActionRowSpec = SpecialtyActionRowSpec(leading = specialtyActions, trailing = referenceActions)

/** Header colors for the two non-station columns. */
internal val KANBAN_SHEET_RIPS_COLOR = Color(0xFF475569)
internal val KANBAN_OTHER_COLOR = Color(0xFF6B7280)

internal fun kanbanColumnColor(columnId: String): Color = when (columnId) {
    SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS -> KANBAN_SHEET_RIPS_COLOR
    SPECIALTY_VIEWER_SECTION_ID_OTHER -> KANBAN_OTHER_COLOR
    else -> stationBarColor(columnId)
}

/** Header bar background: the column color darkened so white header text stays readable (>= 4.5:1). */
internal fun kanbanHeaderColor(color: Color): Color = lerp(color, Color.Black, 0.25f)

@Composable
internal fun SpecialtyKanbanCard(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    columnColor: Color,
    toggleState: KanbanCardToggleState,
    onToggle: (SpecialtyChecklistToggle, Boolean) -> Unit,
    onView: ((String) -> Unit)?,
    onEdit: (SpecialtyItem) -> Unit,
    onDelete: (String) -> Unit,
    onPatchDims: (String?, Double?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = resolved.item
    val toggle = toggleState.toggle
    val done = toggleState.done
    val totalSteps = toggleState.totalSteps
    val completedSteps = toggleState.completedSteps
    val dots = toggleState.dots
    val isSawStation = SpecialtyStation.SAW in item.stations
    val showDims = item.category != SpecialtyItemCategory.TO_ORDER &&
        (isSawStation || item.dimensions != null || item.quantity != null)
    val detail = if (showDims) kanbanDetailLine(item.copy(material = null)) else kanbanDetailLine(item)

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (done) 0.5f else 1f)
    ) {
        // Buckets don't scroll vertically (cards spill into sub-columns instead), so a card is at
        // most as tall as its bucket; one taller than that (e.g. the dims editor on a short
        // landscape screen) scrolls inside itself rather than being cut off.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (toggle != null) {
                    // Default 48dp touch target: this checkbox is the card's main action.
                    Checkbox(
                        checked = toggle.checked,
                        onCheckedChange = { next -> onToggle(toggle, next) },
                        enabled = toggleState.enabled,
                        colors = CheckboxDefaults.colors(checkedColor = columnColor)
                    )
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
            if (item.category == SpecialtyItemCategory.TO_ORDER) {
                // Same as the list: To Order items edit quantity only (dims/material pass through).
                SpecialtyQuantitySection(
                    item = item,
                    onPatchQuantity = { q -> onPatchDims(item.dimensions, q, item.material) }
                )
            } else if (showDims) {
                SpecialtyDimsSection(item = item, isSawStation = isSawStation, onPatchDims = onPatchDims)
            }
        }
    }
}

/** Space kept under the buckets; the same as the list layout's bottom content padding. */
internal val KANBAN_BOTTOM_CLEARANCE = 172.dp

/** Card width inside a bucket; matches the Supply board's cards. */
internal val KANBAN_CARD_WIDTH = 300.dp

/**
 * One bucket, laid out like a Supply board column: the header sits over a [FlowColumn] that fills
 * the board height and wraps cards into extra sub-columns instead of scrolling vertically, so the
 * bucket grows wider as it fills. The header is pinned to the bucket's resulting width.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun SpecialtyKanbanColumnFrame(
    label: String,
    color: Color,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    /** Emits the bucket's cards; apply [itemMotion] to each so reordering (done -> bottom) animates. */
    content: @Composable (itemMotion: Modifier) -> Unit
) {
    val shadowsOff = LocalLowEndMode.current.shadowsDisabled
    val animationsOn = !LocalLowEndMode.current.animationsDisabled
    Card(
        modifier = modifier
            .fillMaxHeight()
            .wrapContentWidth()
            .shadow(elevation = if (shadowsOff) 0.dp else 3.dp, shape = RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        CategoryColumnLayout(
            modifier = Modifier.fillMaxHeight(),
            header = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(kanbanHeaderColor(color))
                        // Same 48dp bar as a Supply column header (10dp padding around a 28dp control).
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color.White),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "$done/$total", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            },
            content = {
                // Scoped per bucket so card motion is measured relative to the bucket, not the
                // scrolling board (panning must not animate cards).
                LookaheadScope {
                    val itemMotion = if (animationsOn) Modifier.animateBounds(lookaheadScope = this) else Modifier
                    FlowColumn(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        content(itemMotion)
                    }
                }
            }
        )
    }
}

/**
 * The station pill row. It is the only reader of the board's active column, so crossing a column
 * while panning recomposes just this row -- not every bucket and card on the board.
 */
@Composable
private fun KanbanStationPillRow(
    columns: List<SpecialtyKanbanColumn>,
    board: SupplyBoardState,
    onSelect: (String) -> Unit
) {
    val activeKey by remember(board) { derivedStateOf { board.activeKey() } }
    KKCPillContainer(
        style = rememberKKCPillStyle(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        KKCSlidingTabRow(
            modifier = Modifier.fillMaxWidth(),
            trackingPosition = { if (board.isScrollInProgress) board.position() else null },
            items = columns.map { column ->
                KKCTabItem(
                    label = column.label.uppercase(),
                    isSelected = (activeKey ?: columns.firstOrNull()?.id) == column.id,
                    alwaysBold = true,
                    onClick = { onSelect(column.id) }
                )
            }
        )
    }
}

/**
 * Station columns side by side, panned horizontally like the Supply board, with a sliding station
 * pill row that tracks the column at the left edge. Every column stays composed (built a few up
 * front, then one per frame) so panning never builds a column mid-swipe.
 */
@Composable
internal fun SpecialtyKanbanBoard(
    columns: List<SpecialtyKanbanColumn>,
    stationOrder: List<SpecialtyStation>,
    completionOverrides: Map<String, Boolean>,
    inFlightUpdates: Map<String, Boolean>,
    sheetRipItems: List<AdminBoardStockItem>,
    sheetRipIsDone: (AdminBoardStockItem) -> Boolean,
    sheetRipRow: @Composable (AdminBoardStockItem) -> Unit,
    onToggle: (SpecialtyResolvedItem, SpecialtyChecklistToggle, Boolean) -> Unit,
    onView: ((String) -> Unit)?,
    onEdit: (SpecialtyItem) -> Unit,
    onDelete: (String) -> Unit,
    onPatchDims: (SpecialtyResolvedItem, String?, Double?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val board = rememberSupplyBoardState()
    val scope = rememberCoroutineScope()
    val animationsOn = !LocalLowEndMode.current.animationsDisabled
    val density = LocalDensity.current
    LaunchedEffect(columns.map { it.id }) { board.updateKeys(columns.map { it.id }) }

    var builtColumns by remember { mutableIntStateOf(3) }
    LaunchedEffect(columns.size) {
        while (builtColumns < columns.size) {
            withFrameNanos { }
            builtColumns++
        }
    }

    Column(modifier = modifier) {
        KanbanStationPillRow(
            columns = columns,
            board = board,
            onSelect = { id -> scope.launch { board.scrollToColumn(id, animate = animationsOn) } }
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp)
                // Buckets stop above the floating nav bar + Add Item decoration (the list's 172dp),
                // or above the keyboard when it is taller, so a card's dims fields stay reachable.
                .windowInsetsPadding(
                    WindowInsets.ime
                        .exclude(WindowInsets.systemBars)
                        .union(WindowInsets(bottom = KANBAN_BOTTOM_CLEARANCE))
                )
                .onSizeChanged { board.viewportPx = it.width }
                .horizontalScroll(board.scroll),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 4dp + 12dp spacing = 16dp inset, matching SupplyBoardState's edge.
            Spacer(Modifier.width(4.dp))
            columns.take(builtColumns).forEach { column ->
                key(column.id) {
                    val placed = Modifier.onPlaced { coords ->
                        board.columns[column.id] = coords.positionInParent().x.roundToInt() to coords.size.width
                    }
                    val color = kanbanColumnColor(column.id)
                    if (column.id == SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS) {
                        SpecialtyKanbanColumnFrame(
                            label = column.label,
                            color = color,
                            done = sheetRipItems.count(sheetRipIsDone),
                            total = sheetRipItems.size,
                            modifier = placed
                        ) { itemMotion ->
                            sheetRipItems.forEach { rip ->
                                key(rip.id) {
                                    Box(modifier = itemMotion.width(KANBAN_CARD_WIDTH)) { sheetRipRow(rip) }
                                }
                            }
                        }
                    } else {
                        // Station columns only hold items that include this station (split items
                        // then have a toggle keyed by it); Other holds station-less, unsplit items.
                        // kanbanColumnToggle relies on that.
                        val stateById = column.items.associate { resolved ->
                            resolved.item.id to kanbanCardToggleState(
                                resolved = resolved,
                                columnId = column.id,
                                toggles = checklistTogglesForItem(resolved, completionOverrides),
                                stationOrder = stationOrder,
                                inFlightUpdates = inFlightUpdates
                            )
                        }
                        val isDone: (SpecialtyResolvedItem) -> Boolean = { r -> stateById.getValue(r.item.id).done }
                        val ordered = orderKanbanCards(column.items, isDone)
                        SpecialtyKanbanColumnFrame(
                            label = column.label,
                            color = color,
                            done = column.items.count(isDone),
                            total = column.items.size,
                            modifier = placed
                        ) { itemMotion ->
                            ordered.forEach { resolved -> key(resolved.item.id) {
                                SpecialtyKanbanCard(
                                    resolved = resolved,
                                    columnId = column.id,
                                    columnColor = color,
                                    toggleState = stateById.getValue(resolved.item.id),
                                    onToggle = { toggle, next -> onToggle(resolved, toggle, next) },
                                    onView = onView,
                                    onEdit = onEdit,
                                    onDelete = onDelete,
                                    onPatchDims = { d, q, m -> onPatchDims(resolved, d, q, m) },
                                    modifier = itemMotion.width(KANBAN_CARD_WIDTH)
                                )
                            } }
                        }
                    }
                }
            }
            // Room to scroll the last columns up to the left edge so they can become active.
            Spacer(Modifier.width(with(density) { (board.viewportPx * 0.6f).toDp() }))
        }
    }
}
