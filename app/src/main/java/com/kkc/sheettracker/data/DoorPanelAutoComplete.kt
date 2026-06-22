package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.SpecialtyItem

data class DoorPanelTarget(val rowId: String, val qty: Int)

private const val DOOR_PANELS_AUTO_PREFIX = "door_panels_auto|"

fun matchingDoorPanelRows(
    item: SpecialtyItem,
    sheetRows: List<HardwoodCutlistRow>,
    mappings: MaterialMappings
): List<DoorPanelTarget> {
    if (!item.automationKey.orEmpty().trim().startsWith(DOOR_PANELS_AUTO_PREFIX, ignoreCase = true)) {
        return emptyList()
    }

    val itemMaterial = item.material?.takeIf { it.isNotBlank() } ?: return emptyList()
    val itemCanonical = mappings.canonical(itemMaterial)
    if (itemCanonical.isEmpty()) return emptyList()

    return sheetRows.mapNotNull { row ->
        val rowMaterial = row.material?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (mappings.canonical(rowMaterial) == itemCanonical) {
            DoorPanelTarget(rowId = row.rowId, qty = row.qty)
        } else {
            null
        }
    }
}
