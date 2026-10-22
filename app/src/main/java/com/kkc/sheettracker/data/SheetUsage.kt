package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import java.util.Locale

private val PLYWOOD_SLAB_MATERIAL = Regex("\\bplywood\\s+slab\\b", RegexOption.IGNORE_CASE)

const val SHEET_USAGE_PREFIX = "sheet_usage|"

fun sheetUsageKey(material: String): String = SHEET_USAGE_PREFIX + material.trim().lowercase(Locale.ROOT)

/** Keyboard type is only a hint: validate pasted text and integer overflow as well. */
fun parseWholeSheets(text: String): Int? =
    text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()

/** sheetRows must come from the canonical SHEETS-unit filter, never inferred dimensions. */
fun sheetUsageMaterialForItem(item: SpecialtyItem, sheetRows: List<HardwoodCutlistRow>, mappings: MaterialMappings): String? {
    if (item.category != SpecialtyItemCategory.CUSTOM || SpecialtyStation.SAW !in item.stations || item.material.isNullOrBlank()) return null
    val automaticPanels = item.automationKey.orEmpty().startsWith("door_panels_auto|", ignoreCase = true)
    val explicitSlab = PLYWOOD_SLAB_MATERIAL.containsMatchIn(item.material)
    val sourceMaterial = sheetRows.firstOrNull { row ->
        mappings.canonical(row.material) == mappings.canonical(item.material) &&
            (automaticPanels || explicitSlab || (row.description.contains("slab", ignoreCase = true) &&
                item.cabinetNumbers.any { it.trim() in row.cabinets.map(String::trim) }))
    }?.material?.trim()?.takeIf { it.isNotEmpty() }
    return sourceMaterial ?: item.material.trim().takeIf { explicitSlab }
}
