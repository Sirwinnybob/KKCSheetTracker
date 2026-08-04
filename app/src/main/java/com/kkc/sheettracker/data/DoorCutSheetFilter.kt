package com.kkc.sheettracker.data

import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.Part
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class DoorCutUnitTypeMetadata(
    val hasUnitTypeMetadata: Boolean = false,
    val sheetRowIds: Set<String> = emptySet(),
    val sheetMaterials: Set<String> = emptySet()
)

private val doorCutGson = Gson()
private val cabinetVisionUnitNames = setOf(
    "EACH",
    "PER FT",
    "SQ FT",
    "BD FT",
    "SHEET",
    "PER M",
    "SQ M",
    "BD M",
    "CUBIC M",
    "CUBIC FT",
    "PAIR"
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

internal fun loadMaterialMappings(baseDir: File): Map<String, String> {
    val file = File(baseDir, ".metadata/material_mappings.json")
    if (!file.exists() || !file.isFile) return emptyMap()
    val root = runCatching { JsonParser.parseString(file.readText()) }.getOrNull()
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?: return emptyMap()
    return buildMap {
        root.entrySet().forEach { (rawKey, rawValue) ->
            val normalizedKey = normalizedDoorPanelMaterialKey(rawKey)
            val normalizedValue = normalizedDoorPanelMaterialKey(rawValue.asStringOrNull())
            if (normalizedKey.isNotBlank() && normalizedValue.isNotBlank()) {
                put(normalizedKey, normalizedValue)
            }
        }
    }
}

internal fun mapDoorPanelMaterialToCncKey(
    material: String?,
    materialMappings: Map<String, String>
): String {
    val normalized = normalizedDoorPanelMaterialKey(material)
    if (normalized.isBlank()) return ""
    return materialMappings[normalized] ?: normalized
}

internal fun deriveCncOwnedDoorPanelMaterials(
    baseDir: File,
    jobFolderName: String,
    cncJob: Job = loadDoorPanelCncJob(baseDir, jobFolderName)
): Set<String> {
    if (cncJob.materials.isEmpty()) return emptySet()
    val rawCutlistIndexJson = loadHardwoodsCutlistIndexRawJson(baseDir.absolutePath, jobFolderName)
    val unitTypeMetadata = parseDoorCutUnitTypeMetadata(rawCutlistIndexJson)
    if (!unitTypeMetadata.hasUnitTypeMetadata) return emptySet()

    val hardwoodIndex = loadHardwoodsCutlistIndex(baseDir, jobFolderName) ?: return emptySet()
    val doorCutRows = hardwoodIndex.documents
        .filter { it.docType == HardwoodDocType.DOOR_CUT_LIST }
        .flatMap { doc -> filterDoorCutRowsToSheets(doc.rows, unitTypeMetadata) }
    if (doorCutRows.isEmpty()) return emptySet()

    val materialMappings = loadMaterialMappings(baseDir)
    val cncPartsByMaterial = cncJob.materials
        .groupBy { material -> mapDoorPanelMaterialToCncKey(material.materialName, materialMappings) }
        .mapValues { (_, materials) ->
            materials.flatMap { material ->
                material.metadata?.pages.orEmpty().flatMap { page -> page.parts }
            }
        }

    return doorCutRows
        .groupBy { row -> mapDoorPanelMaterialToCncKey(row.material, materialMappings) }
        .mapNotNull { (materialKey, rows) ->
            if (materialKey.isBlank()) return@mapNotNull null
            val cncParts = cncPartsByMaterial[materialKey].orEmpty()
            if (cncParts.isEmpty()) return@mapNotNull null
            if (rows.all { row -> cncParts.count { part -> preciseMatches(row, part) } >= row.qty }) {
                materialKey
            } else {
                null
            }
        }
        .toSet()
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
                if (isSheetUnitType(unitType)) {
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
                            if (isSheetUnitType(unitTypeValue.asString)) {
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

private fun isSheetUnitType(unitType: String): Boolean {
    return normalizeCabinetVisionUnitType(unitType) == "SHEET"
}

internal fun normalizeCabinetVisionUnitType(unitType: String): String {
    val normalized = unitType
        .trim()
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ")
        .uppercase(Locale.US)
    return when {
        normalized == "SHEETS" -> "SHEET"
        normalized in cabinetVisionUnitNames -> normalized
        else -> normalized
    }
}

private fun JsonObject.getStringIgnoreCase(key: String): String? {
    val entry = this.entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: return null
    val value = entry.value
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString
}

private fun JsonElement.asStringOrNull(): String? {
    return runCatching {
        if (isJsonNull || !isJsonPrimitive) return@runCatching null
        val primitive = asJsonPrimitive
        when {
            primitive.isString -> primitive.asString
            primitive.isNumber -> primitive.asNumber.toString()
            primitive.isBoolean -> primitive.asBoolean.toString()
            else -> null
        }
    }.getOrNull()
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

private fun loadHardwoodsCutlistIndex(baseDir: File, jobFolderName: String): HardwoodCutlistIndex? {
    val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
    if (!file.exists() || !file.isFile) return null
    return runCatching { doorCutGson.fromJson(file.readText(), HardwoodCutlistIndex::class.java) }.getOrNull()
}

private fun loadDoorPanelCncJob(baseDir: File, jobFolderName: String): Job {
    val jobDir = File(baseDir, jobFolderName)
    val cncDir = File(jobDir, "CNC")
    val jobNumber = jobFolderName.substringBefore(" - ").trim()
    if (!cncDir.exists() || !cncDir.isDirectory || jobNumber.isBlank()) {
        return Job(folderName = jobFolderName, jobNumber = jobNumber, jobName = "")
    }

    val materials = cncDir.listFiles()
        ?.filter { file ->
            file.isFile &&
                file.extension.equals("pdf", ignoreCase = true) &&
                "ALL SHEETS" !in file.name &&
                file.name.startsWith("$jobNumber - ")
        }
        ?.map { pdfFile ->
            val materialName = pdfFile.nameWithoutExtension.removePrefix("$jobNumber - ")
            Material(
                pdfFilename = pdfFile.name,
                materialName = materialName,
                pageCount = 0,
                metadata = loadDoorPanelMaterialMetadata(cncDir, pdfFile.name)
            )
        }
        .orEmpty()

    return Job(
        folderName = jobFolderName,
        jobNumber = jobNumber,
        jobName = jobFolderName.substringAfter(" - ", ""),
        materials = materials
    )
}

private fun loadDoorPanelMaterialMetadata(cncDir: File, pdfFilename: String): MaterialMetadata? {
    val metadataFile = File(cncDir, ".metadata/${pdfFilename.removeSuffix(".pdf")}.json")
    if (!metadataFile.exists() || !metadataFile.isFile) return null
    return runCatching { doorCutGson.fromJson(metadataFile.readText(), MaterialMetadata::class.java) }.getOrNull()
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
        it.docType != HardwoodDocType.FACE_FRAME_CUT_LIST
    }
    if (targetDocs.isEmpty()) return

    val engine = UnifiedMetadataEngineRegistry.getOrCreate(File(hardwoodsRepository.currentBasePath()), BuildConfig.DEBUG)
    val cncJob = engine.getCncSnapshot(jobFolderName)?.job ?: return

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
