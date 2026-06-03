package com.kkc.sheettracker.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Part
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class DoorCutUnitTypeMetadata(
    val hasUnitTypeMetadata: Boolean = false,
    val sheetRowIds: Set<String> = emptySet(),
    val sheetMaterials: Set<String> = emptySet()
)

fun loadHardwoodsCutlistIndexRawJson(basePath: String, jobFolderName: String): String? {
    val file = File(basePath, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
    if (!file.exists() || !file.isFile) return null
    return runCatching { file.readText() }.getOrNull()
}

fun filterDoorCutRowsToSheets(
    rows: List<HardwoodCutlistRow>,
    unitTypeMetadata: DoorCutUnitTypeMetadata
): List<HardwoodCutlistRow> {
    if (!unitTypeMetadata.hasUnitTypeMetadata) return emptyList()
    if (rows.isEmpty()) return emptyList()
    return rows.filter { row ->
        normalizedDoorPanelRowIdKey(row.rowId) in unitTypeMetadata.sheetRowIds ||
            normalizedDoorPanelMaterialKey(row.material).takeIf { it.isNotBlank() } in unitTypeMetadata.sheetMaterials
    }
}

fun parseDoorCutUnitTypeMetadata(rawCutlistIndexJson: String?): DoorCutUnitTypeMetadata {
    if (rawCutlistIndexJson.isNullOrBlank()) return DoorCutUnitTypeMetadata()
    val root = runCatching { JsonParser.parseString(rawCutlistIndexJson) }.getOrNull()
        ?: return DoorCutUnitTypeMetadata()
    val doorDocNodes = mutableListOf<JsonElement>()
    collectDoorCutDocumentNodes(root, doorDocNodes)
    if (doorDocNodes.isEmpty()) return DoorCutUnitTypeMetadata()

    var hasUnitTypeMetadata = false
    val sheetRowIds = linkedSetOf<String>()
    val sheetMaterials = linkedSetOf<String>()
    doorDocNodes.forEach { node ->
        collectDoorCutUnitTypes(
            element = node,
            onUnitTypeDetected = { hasUnitTypeMetadata = true },
            onSheetRowDetected = { rowId -> sheetRowIds += rowId },
            onSheetMaterialDetected = { material -> sheetMaterials += material }
        )
    }

    return DoorCutUnitTypeMetadata(
        hasUnitTypeMetadata = hasUnitTypeMetadata,
        sheetRowIds = sheetRowIds,
        sheetMaterials = sheetMaterials
    )
}

private fun collectDoorCutDocumentNodes(element: JsonElement, out: MutableList<JsonElement>) {
    when {
        element.isJsonArray -> {
            element.asJsonArray.forEach { child -> collectDoorCutDocumentNodes(child, out) }
        }
        element.isJsonObject -> {
            val obj = element.asJsonObject
            val docType = obj.getStringIgnoreCase("docType")
            if (docType.equals(HardwoodDocType.DOOR_CUT_LIST.name, ignoreCase = true)) {
                out += element
            }

            val documents = obj.get("documents")
            when {
                documents?.isJsonArray == true -> {
                    documents.asJsonArray.forEach { child -> collectDoorCutDocumentNodes(child, out) }
                }
                documents?.isJsonObject == true -> {
                    val docsObj = documents.asJsonObject
                    docsObj.entrySet().forEach { (key, value) ->
                        if (key.equals(HardwoodDocType.DOOR_CUT_LIST.name, ignoreCase = true)) {
                            out += value
                        }
                        collectDoorCutDocumentNodes(value, out)
                    }
                }
            }
        }
    }
}

private fun collectDoorCutUnitTypes(
    element: JsonElement,
    onUnitTypeDetected: () -> Unit,
    onSheetRowDetected: (String) -> Unit,
    onSheetMaterialDetected: (String) -> Unit
) {
    when {
        element.isJsonArray -> {
            element.asJsonArray.forEach { child ->
                collectDoorCutUnitTypes(
                    child,
                    onUnitTypeDetected = onUnitTypeDetected,
                    onSheetRowDetected = onSheetRowDetected,
                    onSheetMaterialDetected = onSheetMaterialDetected
                )
            }
        }
        element.isJsonObject -> {
            val obj = element.asJsonObject

            val unitType = obj.getStringIgnoreCase("unitType")
            if (unitType != null) {
                onUnitTypeDetected()
                if (unitType.equals("SHEETS", ignoreCase = true)) {
                    obj.getStringIgnoreCase("rowId")
                        ?.let(::normalizedDoorPanelRowIdKey)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { normalized -> onSheetRowDetected(normalized) }
                    obj.getStringIgnoreCase("id")
                        ?.takeIf { it.startsWith("row-", ignoreCase = true) }
                        ?.let(::normalizedDoorPanelRowIdKey)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { normalized -> onSheetRowDetected(normalized) }
                    extractMaterialName(obj)
                        ?.let(::normalizedDoorPanelMaterialKey)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(onSheetMaterialDetected)
                }
            }

            obj.entrySet().forEach { (key, value) ->
                if (key.equals("unitTypeByMaterial", ignoreCase = true) && value.isJsonObject) {
                    value.asJsonObject.entrySet().forEach { (materialName, unitTypeValue) ->
                        if (unitTypeValue.isJsonPrimitive && unitTypeValue.asJsonPrimitive.isString) {
                            onUnitTypeDetected()
                            if (unitTypeValue.asString.equals("SHEETS", ignoreCase = true)) {
                                normalizedDoorPanelMaterialKey(materialName)
                                    .takeIf { it.isNotBlank() }
                                    ?.let(onSheetMaterialDetected)
                            }
                        }
                    }
                }
                collectDoorCutUnitTypes(
                    value,
                    onUnitTypeDetected = onUnitTypeDetected,
                    onSheetRowDetected = onSheetRowDetected,
                    onSheetMaterialDetected = onSheetMaterialDetected
                )
            }
        }
    }
}

private fun JsonObject.getStringIgnoreCase(key: String): String? {
    val entry = this.entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: return null
    val value = entry.value
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString
}

private fun extractMaterialName(obj: JsonObject): String? {
    val keyCandidates = listOf("material", "materialName", "name")
    keyCandidates.forEach { key ->
        val value = obj.getStringIgnoreCase(key)?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun normalizedDoorPanelMaterialKey(material: String?): String {
    return material
        .orEmpty()
        .trim()
        .replace(Regex("""\s+"""), " ")
        .uppercase(Locale.US)
}

private fun normalizedDoorPanelRowIdKey(rowId: String?): String {
    return rowId
        .orEmpty()
        .trim()
        .uppercase(Locale.US)
}

fun parseDimension(text: String): Double? {
    val normalized = text.trim()
    if (normalized.isBlank()) return null

    val mixedFraction = Regex("""(-?\d+)\s+(\d+)\s*/\s*(\d+)""").find(normalized)
    if (mixedFraction != null) {
        val whole = mixedFraction.groupValues[1].toDoubleOrNull() ?: return null
        val numerator = mixedFraction.groupValues[2].toDoubleOrNull() ?: return null
        val denominator = mixedFraction.groupValues[3].toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return whole + (numerator / denominator)
    }

    val fractionOnly = Regex("""(-?\d+)\s*/\s*(\d+)""").find(normalized)
    if (fractionOnly != null) {
        val numerator = fractionOnly.groupValues[1].toDoubleOrNull() ?: return null
        val denominator = fractionOnly.groupValues[2].toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    return Regex("""-?\d+(?:\.\d+)?""").find(normalized)?.value?.toDoubleOrNull()
}

data class DoorStileRailWidths(
    val stileWidth: Double,
    val railWidth: Double
)

fun parseStileRailWidths(description: String): DoorStileRailWidths {
    val pattern = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+\.\d+|\d+)""")
    val matches = pattern.findAll(description).map { it.value }.toList()
    val values = matches.mapNotNull { parseDimension(it) }
    
    val defaultStile = 2.25
    val defaultRail = 2.25
    
    if (values.isEmpty()) {
        return DoorStileRailWidths(defaultStile, defaultRail)
    }
    
    val stile = values[0]
    val rail = if (values.size > 1) values[1] else stile
    return DoorStileRailWidths(stile, rail)
}

fun preciseMatches(row: HardwoodCutlistRow, part: Part): Boolean {
    // 1. Cabinet match
    val cabMatch = row.cabinets.any { cabStr ->
        val cabInt = cabStr.toIntOrNull()
        if (cabInt != null) {
            cabInt == part.cabNumber
        } else {
            cabStr.trim() == part.cabNumber.toString()
        }
    }
    if (!cabMatch) return false

    // 2. Name match (case-insensitive, normalized spaces, allowing containing/equality/panel matching)
    val normRow = row.description.trim().replace(Regex("""\s+"""), " ").uppercase(Locale.US)
    val normPart = part.name.trim().replace(Regex("""\s+"""), " ").uppercase(Locale.US)
    if (normRow.isEmpty() || normPart.isEmpty()) return false
    
    val nameMatch = normRow == normPart || 
                    normRow.contains(normPart) || 
                    normPart.contains(normRow) ||
                    (normRow.contains("PANEL") && normPart.contains("PANEL")) ||
                    (normRow.contains("SLAB") && normPart.contains("SLAB"))
    if (!nameMatch) return false

    // 3. Size match (allowing rotation, stile & rail reductions, and within a 0.02 tolerance)
    val rowWidth = parseDimension(row.width) ?: return false
    val rowLength = parseDimension(row.length) ?: return false

    // Direct / Rotated exact match
    val diffW = abs(rowWidth - part.width)
    val diffL = abs(rowLength - part.length)
    if (diffW <= 0.02 && diffL <= 0.02) return true

    val diffW_rot = abs(rowWidth - part.length)
    val diffL_rot = abs(rowLength - part.width)
    if (diffW_rot <= 0.02 && diffL_rot <= 0.02) return true

    // Panel size reduction match
    val widths = parseStileRailWidths(row.description)
    val doubleG = 0.936 // 2 * 0.468" groove depth
    
    // Scenario 1: stile reduces width, rail reduces length
    val expW1 = rowWidth - (2 * widths.stileWidth - doubleG)
    val expL1 = rowLength - (2 * widths.railWidth - doubleG)
    if (abs(expW1 - part.width) <= 0.02 && abs(expL1 - part.length) <= 0.02) return true
    if (abs(expW1 - part.length) <= 0.02 && abs(expL1 - part.width) <= 0.02) return true

    // Scenario 2: rail reduces width, stile reduces length
    val expW2 = rowWidth - (2 * widths.railWidth - doubleG)
    val expL2 = rowLength - (2 * widths.stileWidth - doubleG)
    if (abs(expW2 - part.width) <= 0.02 && abs(expL2 - part.length) <= 0.02) return true
    if (abs(expW2 - part.length) <= 0.02 && abs(expL2 - part.width) <= 0.02) return true

    return false
}

fun syncCncToHardwoods(
    jobFolderName: String,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    hardwoodsRepository: HardwoodsRepository,
    hardwoodsProgressStore: HardwoodsProgressStore
) {
    val hardwoodsIndex = hardwoodsRepository.loadHardwoodsIndex(jobFolderName) ?: return
    
    val rawCutlistIndexJson = loadHardwoodsCutlistIndexRawJson(hardwoodsRepository.currentBasePath(), jobFolderName)
    val unitTypeMetadata = parseDoorCutUnitTypeMetadata(rawCutlistIndexJson)

    val targetDocs = hardwoodsIndex.documents.filter {
        it.docType == HardwoodDocType.DOOR_CUT_LIST || it.docType == HardwoodDocType.DOOR_LIST
    }
    if (targetDocs.isEmpty()) return

    val cncJob = jobRepository.scanJobs().firstOrNull { it.folderName == jobFolderName } ?: return

    data class CncPartState(
        val part: Part,
        val isCompleted: Boolean
    )

    val allCncParts = mutableListOf<CncPartState>()
    val materialSheetCounts = mutableMapOf<String, Int>() // normalized -> total sheets
    val materialCompletedCounts = mutableMapOf<String, Int>() // normalized -> completed sheets

    cncJob.materials.forEach { material ->
        val normMat = normalizedDoorPanelMaterialKey(material.materialName)
        val pageCount = material.pageCount
        materialSheetCounts[normMat] = (materialSheetCounts[normMat] ?: 0) + pageCount

        val pages = material.metadata?.pages.orEmpty()
        for (pageNum in 1..pageCount) {
            val isCompleted = progressStore.isSheetComplete(jobFolderName, material.pdfFilename, pageNum, material.fileFingerprint)
            if (isCompleted) {
                materialCompletedCounts[normMat] = (materialCompletedCounts[normMat] ?: 0) + 1
            }

            val pageMeta = pages.firstOrNull { it.pageNumber == pageNum }
            if (pageMeta != null) {
                pageMeta.parts.forEach { part ->
                    allCncParts.add(CncPartState(part, isCompleted))
                }
            }
        }
    }

    targetDocs.forEach { doc ->
        doc.rows.forEach { row ->
            val matchingCncParts = allCncParts.filter { preciseMatches(row, it.part) }
            
            val isExplicitSheet = unitTypeMetadata.hasUnitTypeMetadata && (
                normalizedDoorPanelRowIdKey(row.rowId) in unitTypeMetadata.sheetRowIds ||
                normalizedDoorPanelMaterialKey(row.material).takeIf { it.isNotBlank() } in unitTypeMetadata.sheetMaterials
            )
            val hasCncMatches = matchingCncParts.isNotEmpty()
            val shouldSync = isExplicitSheet || hasCncMatches

            if (shouldSync) {
                val targetDoneCount = if (hasCncMatches) {
                    val completedCount = matchingCncParts.count { it.isCompleted }
                    completedCount.coerceAtMost(row.qty)
                } else {
                    // Fallback material-level proportional match
                    val normRowMat = normalizedDoorPanelMaterialKey(row.material)
                    val totalSheets = materialSheetCounts[normRowMat] ?: 0
                    val completedSheets = materialCompletedCounts[normRowMat] ?: 0
                    if (totalSheets > 0) {
                        val ratio = completedSheets.toDouble() / totalSheets.toDouble()
                        (row.qty * ratio).toInt().coerceIn(0, row.qty)
                    } else {
                        0
                    }
                }

                val currentProgress = hardwoodsProgressStore.getRowProgress(jobFolderName, doc.docType.name, row.rowId)
                if (currentProgress.doneCount != targetDoneCount) {
                    hardwoodsProgressStore.setDoneCount(jobFolderName, doc.docType.name, row.rowId, row.qty, targetDoneCount)
                }
            }
        }
    }
}
