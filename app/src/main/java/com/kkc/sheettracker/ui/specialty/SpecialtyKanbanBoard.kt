package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import kotlinx.coroutines.CoroutineStart
import com.kkc.sheettracker.ui.supply.BOARD_CARD_WIDTH
import com.kkc.sheettracker.ui.supply.BoardCardFlow
import com.kkc.sheettracker.ui.supply.BoardColumnCard
import com.kkc.sheettracker.ui.supply.BoardColumnsRow
import com.kkc.sheettracker.ui.supply.SupplyBoardState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillContainer
import com.kkc.sheettracker.ui.components.KKCSlidingTabRow
import com.kkc.sheettracker.ui.components.KKCTabItem
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.rememberKKCPillStyle
import com.kkc.sheettracker.ui.jobs.stationBarColor
import com.kkc.sheettracker.ui.supply.rememberSupplyBoardState
import kotlinx.coroutines.launch

/**
 * The board's columns: Sheet Rips first when present (its items are empty; rips render
 * separately), then the list's station sections in order, Other last.
 */
internal fun buildSpecialtyKanbanColumns(
    sections: List<SpecialtyDetailSection>,
    hasSheetRips: Boolean
): List<SpecialtyDetailSection> =
    if (hasSheetRips) listOf(SpecialtyDetailSection(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, "Sheet Rips", emptyList())) + sections
    else sections

/**
 * The checkbox a card shows in [columnId]: that station's toggle for station-split items, else the
 * item's single completion toggle (shared across its columns, as in the list). A split item with no
 * toggle for [columnId] gets none (null): it can't happen while items only land in their own
 * stations' columns, and guessing one of its toggles would tick the wrong station.
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
): List<KanbanStationDot> = kanbanStationDots(resolved, columnId, toggles, specialtyStationRank(stationOrder))

/** [kanbanStationDots] with the station ranking precomputed once per board pass. */
internal fun kanbanStationDots(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    toggles: List<SpecialtyChecklistToggle>,
    stationRank: Map<SpecialtyStation, Int>
): List<KanbanStationDot> =
    orderSpecialtyStations(resolved.item.stations, stationRank)
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
    /** A save for any of this item's checkboxes is still running (the list's "Saving..."). */
    val saving: Boolean,
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
    inFlightUpdates: Map<String, Boolean>,
    readOnly: Boolean = false
): KanbanCardToggleState =
    kanbanCardToggleState(resolved, columnId, toggles, specialtyStationRank(stationOrder), inFlightUpdates, readOnly)

internal fun kanbanCardToggleState(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    toggles: List<SpecialtyChecklistToggle>,
    stationRank: Map<SpecialtyStation, Int>,
    inFlightUpdates: Map<String, Boolean>,
    readOnly: Boolean = false
): KanbanCardToggleState {
    val toggle = kanbanColumnToggle(toggles, columnId)
    val totalSteps = toggles.size.coerceAtLeast(1)
    return KanbanCardToggleState(
        toggle = toggle,
        enabled = !readOnly && (toggle == null || isToggleEnabled(toggle.controlId, inFlightUpdates)),
        saving = toggles.any { !isToggleEnabled(it.controlId, inFlightUpdates) },
        completedSteps = toggles.count { it.checked }.coerceAtMost(totalSteps),
        totalSteps = totalSteps,
        dots = kanbanStationDots(resolved, columnId, toggles, stationRank)
    )
}

/** The card's status border, from its item's completed steps (same rule as the list row). */
internal fun kanbanCardStatus(completedSteps: Int, totalSteps: Int): SheetStatus = when {
    totalSteps > 0 && completedSteps >= totalSteps -> SheetStatus.COMPLETE
    completedSteps > 0 -> SheetStatus.IN_PROGRESS
    else -> SheetStatus.NOT_STARTED
}

/** What a card's checkbox marks done, for screen readers: the item, and the station if any. */
internal fun kanbanCheckboxLabel(title: String, columnId: String): String {
    val station = SpecialtyStation.entries.firstOrNull { it.name == columnId } ?: return "$title done"
    val stationLabel = station.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    return "$title done at $stationLabel"
}

/** Unchecked cards keep list order on top; checked cards keep list order at the bottom. */
internal fun <T> orderKanbanCards(
    items: List<T>,
    isDone: (T) -> Boolean
): List<T> {
    val (done, open) = items.partition(isDone)
    return open + done
}

/** Header colors for the two non-station columns. */
private val KANBAN_SHEET_RIPS_COLOR = Color(0xFF475569)
private val KANBAN_OTHER_COLOR = Color(0xFF6B7280)

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
    basePath: String,
    jobFolderName: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    val item = resolved.item
    val toggle = toggleState.toggle
    val done = toggleState.done
    val totalSteps = toggleState.totalSteps
    val completedSteps = toggleState.completedSteps
    val dots = toggleState.dots

    val title = specialtyItemTitle(item.cabinetLabel, item.cabinetNumbers, item.name)
    // Same status border as the list row. A card done in this column also dims its summary
    // (title, steps, progress), but not its controls and details, which stay readable.
    StatusBorderedCard(
        status = kanbanCardStatus(completedSteps, totalSteps),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
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
                    val checkboxLabel = kanbanCheckboxLabel(title, columnId)
                    Checkbox(
                        checked = toggle.checked,
                        onCheckedChange = { next -> onToggle(toggle, next) },
                        enabled = toggleState.enabled,
                        colors = CheckboxDefaults.colors(checkedColor = columnColor),
                        modifier = Modifier.semantics { contentDescription = checkboxLabel }
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.category == SpecialtyItemCategory.TO_ORDER) {
                    StatusChip(
                        text = "To Order",
                        backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Column(
                modifier = Modifier.alpha(if (done) 0.6f else 1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
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
                    trackColor = com.kkc.sheettracker.ui.components.emptyProgressSegmentColor()
                )
            }
            if (dots.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dots.forEach { dot ->
                        val dotColor = stationBarColor(dot.station.name)
                        val stationLabel = dot.station.name.lowercase().replace('_', ' ')
                            .replaceFirstChar { it.uppercase() }
                        Box(
                            modifier = Modifier
                                .semantics {
                                    contentDescription = "$stationLabel ${if (dot.done) "done" else "not done"}"
                                }
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (dot.done) dotColor else Color.Transparent)
                                .border(1.5.dp, dotColor, CircleShape)
                        )
                    }
                }
            }
            // Same fields and editors as the list row (notes, supplier, dims, attachments, ...).
            SpecialtyItemDetails(
                item = item,
                onPatchDims = onPatchDims,
                basePath = basePath,
                jobFolderName = jobFolderName,
                saving = toggleState.saving,
                readOnly = readOnly
            )
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
                if (!readOnly) OutlinedButton(
                    onClick = { onEdit(item) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                if (!readOnly) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onDelete(item.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete $title",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Space kept under the buckets; the same as the list layout's bottom content padding. */
internal val KANBAN_BOTTOM_CLEARANCE = 172.dp

/**
 * Slides a card to its new spot when its bucket reorders (done -> bottom) or reflows. It tracks the
 * card's position within the bucket only, so panning the board never animates it, and it does so
 * from the one regular placement pass -- no per-bucket LookaheadScope / animateBounds approach pass
 * and no lookahead coordinate math on every placement.
 */
private data object KanbanCardMotionElement : ModifierNodeElement<KanbanCardMotionNode>() {
    override fun create() = KanbanCardMotionNode()
    override fun update(node: KanbanCardMotionNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "kanbanCardMotion"
    }
}

private class KanbanCardMotionNode : Modifier.Node(), LayoutModifierNode {
    private var offset: Animatable<IntOffset, AnimationVector2D>? = null

    override fun onDetach() {
        offset = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // This node's own position in the bucket, as the bucket just placed it.
            val target = coordinates?.positionInParent()?.round()
            if (target == null) {
                placeable.place(0, 0)
                return@layout
            }
            // First placement (or reattached): start where it is, nothing to animate.
            val anim = offset ?: Animatable(target, IntOffset.VectorConverter).also { offset = it }
            if (anim.targetValue != target) {
                // Undispatched so targetValue updates now and a second placement this frame
                // doesn't start the same animation again.
                this@KanbanCardMotionNode.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    anim.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold))
                }
            }
            // Reading anim.value here re-runs just this placement each animation frame.
            placeable.place(anim.value - target)
        }
    }
}

/**
 * One bucket, laid out like a Supply board column ([BoardColumnCard]): the header sits over cards
 * that fill the board height and wrap into extra sub-columns instead of scrolling vertically, so
 * the bucket grows wider as it fills. The header is pinned to the bucket's resulting width.
 */
@Composable
internal fun SpecialtyKanbanColumnFrame(
    label: String,
    color: Color,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** Emits the bucket's cards; apply [itemMotion] to each so reordering (done -> bottom) animates. */
    content: @Composable (itemMotion: Modifier) -> Unit
) {
    val animationsOn = !LocalLowEndMode.current.animationsDisabled
    BoardColumnCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
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
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(end = 8.dp).size(20.dp)
                    )
                }
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color.White),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "$done/$total", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    ) {
        val itemMotion = if (animationsOn) Modifier.then(KanbanCardMotionElement) else Modifier
        BoardCardFlow { content(itemMotion) }
    }
}

/**
 * The station pill row. It is the only reader of the board's active column, so crossing a column
 * while panning recomposes just this row -- not every bucket and card on the board.
 */
@Composable
private fun KanbanStationPillRow(
    columns: List<SpecialtyDetailSection>,
    board: SupplyBoardState,
    onSelect: (String) -> Unit
) {
    val activeKey by remember(board) { derivedStateOf { board.activeKey() } }
    // For the frame after a column appears or goes, the board's keys lag behind [columns]; an
    // active key that isn't a current column falls back to the first rather than selecting none.
    val selectedId = activeKey?.takeIf { key -> columns.any { it.id == key } } ?: columns.firstOrNull()?.id
    KKCPillContainer(
        style = rememberKKCPillStyle(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        KKCSlidingTabRow(
            modifier = Modifier.fillMaxWidth(),
            trackingPosition = { if (board.isScrollInProgress) board.position() else null },
            // Same height/icon size as the action row above it (KKCPillActionRow).
            height = 48.dp,
            pillHeight = 40.dp,
            iconSize = 24.dp,
            items = columns.map { column ->
                KKCTabItem(
                    label = column.label.uppercase(),
                    icon = specialtySectionIcon(column.id),
                    isSelected = selectedId == column.id,
                    alwaysBold = true,
                    onClick = { onSelect(column.id) }
                )
            }
        )
    }
}

/**
 * Station columns side by side, panned horizontally like the Supply board ([BoardColumnsRow]),
 * with a sliding station pill row that tracks the column at the left edge.
 */
@Composable
internal fun SpecialtyKanbanBoard(
    columns: List<SpecialtyDetailSection>,
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
    basePath: String,
    jobFolderName: String,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val board = rememberSupplyBoardState()
    val scope = rememberCoroutineScope()
    val animationsOn = !LocalLowEndMode.current.animationsDisabled
    val stationRank = remember(stationOrder) { specialtyStationRank(stationOrder) }
    val columnsById = remember(columns) { columns.associateBy { it.id } }
    val columnKeys = remember(columns) { columns.map { it.id } }

    Column(modifier = modifier) {
        KanbanStationPillRow(
            columns = columns,
            board = board,
            onSelect = { id -> scope.launch { board.scrollToColumn(id, animate = animationsOn) } }
        )
        BoardColumnsRow(
            board = board,
            keys = columnKeys,
            initialBuiltColumns = 3,
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
        ) { columnId, placed ->
            val column = columnsById.getValue(columnId)
            val color = kanbanColumnColor(column.id)
            if (column.id == SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS) {
                SpecialtyKanbanColumnFrame(
                    label = column.label,
                    color = color,
                    done = sheetRipItems.count(sheetRipIsDone),
                    total = sheetRipItems.size,
                    modifier = placed,
                    icon = specialtySectionIcon(column.id)
                ) { itemMotion ->
                    // Same as the station buckets: finished rips drop to the bottom.
                    orderKanbanCards(sheetRipItems, sheetRipIsDone).forEach { rip ->
                        key(rip.id) {
                            Box(modifier = itemMotion.width(BOARD_CARD_WIDTH)) { sheetRipRow(rip) }
                        }
                    }
                }
            } else {
                // Station columns only hold items that include this station (split items then
                // have a toggle keyed by it); Other holds station-less, unsplit items.
                // kanbanColumnToggle relies on that. Each card keeps its own state even if two
                // items were ever to share an id.
                // Remembered so a bucket rebuilt for an unrelated reason (a column built, a rip
                // ticked) doesn't recompute every card's toggles and order.
                val cards = remember(column, completionOverrides, inFlightUpdates, stationRank, readOnly) {
                    orderKanbanCards(
                        column.items.map { resolved ->
                            resolved to kanbanCardToggleState(
                                resolved = resolved,
                                columnId = column.id,
                                toggles = checklistTogglesForItem(resolved, completionOverrides),
                                stationRank = stationRank,
                                inFlightUpdates = inFlightUpdates,
                                readOnly = readOnly
                            )
                        }
                    ) { (_, state) -> state.done }
                }
                SpecialtyKanbanColumnFrame(
                    label = column.label,
                    color = color,
                    done = cards.count { (_, state) -> state.done },
                    total = cards.size,
                    modifier = placed,
                    icon = specialtySectionIcon(column.id)
                ) { itemMotion ->
                    cards.forEach { (resolved, state) ->
                        key(resolved.item.id) {
                            SpecialtyKanbanCard(
                                resolved = resolved,
                                columnId = column.id,
                                columnColor = color,
                                toggleState = state,
                                onToggle = { toggle, next -> onToggle(resolved, toggle, next) },
                                onView = onView,
                                onEdit = onEdit,
                                onDelete = onDelete,
                                onPatchDims = { d, q, m -> onPatchDims(resolved, d, q, m) },
                                basePath = basePath,
                                jobFolderName = jobFolderName,
                                readOnly = readOnly,
                                modifier = itemMotion.width(BOARD_CARD_WIDTH)
                            )
                        }
                    }
                }
            }
        }
    }
}
