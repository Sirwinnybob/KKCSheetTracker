package com.kkc.sheettracker.data.models

/**
 * Hardwoods and Specialty are two fully independent instances of the hidden-materials feature
 * -- SpecialtyDoorPanelsScreen reads rows from the same HardwoodCutlistIndex/HardwoodDocType
 * data as the Hardwoods cutlist screen, so hiding a material in one mode must never affect the
 * other (see the 2026-09-08 hidden-materials design doc). This enum's `.name` values
 * ("HARDWOODS"/"SPECIALTY") are sent verbatim to the backend and must match
 * routes/hidden_materials_store.py's VALID_MODES exactly.
 */
enum class HiddenMaterialsMode { HARDWOODS, SPECIALTY }

/** One hide/unhide record. `docType` matches HardwoodDocType.name (e.g. "NAILER_CUT_LIST"). */
data class HiddenMaterialEntry(
    val docType: String = "",
    val material: String = "",
    val hiddenAt: String = "",
    val tabletId: String = ""
)

data class HiddenMaterialsGlobalDoc(
    val entries: List<HiddenMaterialEntry> = emptyList()
)

data class HiddenMaterialsJobDoc(
    val hides: List<HiddenMaterialEntry> = emptyList(),
    val unhides: List<HiddenMaterialEntry> = emptyList()
)

/**
 * The aggregated shape broadcast by the live WebSocket's `hiddenMaterials` field and assembled
 * locally by HiddenMaterialsRepository from the two on-disk files. `jobs` only ever contains
 * entries for jobs that actually have an override file -- most jobs never touch this feature.
 */
data class HiddenMaterialsDocument(
    val global: HiddenMaterialsGlobalDoc = HiddenMaterialsGlobalDoc(),
    val jobs: Map<String, HiddenMaterialsJobDoc> = emptyMap()
)
