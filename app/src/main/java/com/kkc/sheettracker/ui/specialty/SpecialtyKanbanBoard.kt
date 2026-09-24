package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillAction

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
