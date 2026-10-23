package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.Material
import com.google.gson.JsonParser
import java.io.File

data class SawSheetMaterial(val material: String, val cncUsed: Int?)

internal fun buildSawSheetMaterials(
    sheetNames: List<String>, cncMaterials: List<Material>, mappings: MaterialMappings,
    cncUsed: ((Material) -> Int)?
): List<SawSheetMaterial> {
    val names = linkedMapOf<String, String>()
    sheetNames.forEach { name ->
        val key = mappings.canonical(name)
        if (key.isNotEmpty()) names.putIfAbsent(key, name.trim())
    }
    val used = mutableMapOf<String, Int>()
    cncMaterials.distinctBy { it.pdfFilename.lowercase(java.util.Locale.ROOT) }.forEach { material ->
        val key = mappings.canonical(material.metadata?.material?.takeIf(String::isNotBlank) ?: material.materialName)
        if (key in names && cncUsed != null) used[key] = (used[key] ?: 0) + cncUsed(material)
    }
    return names.map { (key, name) -> SawSheetMaterial(name, if (cncUsed == null) null else used[key] ?: 0) }
        .sortedBy { it.material.lowercase(java.util.Locale.ROOT) }
}

internal fun usedCncSheets(material: Material, isComplete: (Int) -> Boolean): Int {
    val metadataPages = material.metadata?.pages.orEmpty()
    val pages = if (metadataPages.isEmpty()) (1..material.pageCount).toList() else metadataPages
        .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
        .map { it.pageNumber }.filter { it in 1..material.pageCount }.distinct()
    return pages.count(isComplete)
}

/** Only declared sheet units are included. Quantities/areas never become saw counts. */
internal fun publishedSheetMaterialNames(json: String?, requiredSchemaVersion: Int? = null): List<String> =
    readSheetMaterialSummary(json, requiredSchemaVersion).orEmpty()

/** Null means unavailable; an empty summary is authoritative and must not revive stale rows. */
private fun readSheetMaterialSummary(json: String?, requiredSchemaVersion: Int?): List<String>? = runCatching {
    val root = JsonParser.parseString(json ?: return null).asJsonObject
    if (requiredSchemaVersion != null && root.get("schemaVersion")?.asInt != requiredSchemaVersion) return null
    val summary = root.getAsJsonArray("materialSummary") ?: return null
    fun isSheet(unit: String) = unit.trim().equals("sheet", true) || unit.trim().equals("sheets", true)
    buildList {
        summary.forEach { element ->
            val row = element.asJsonObject
            if (isSheet(row.get("unit")?.asString.orEmpty())) add(row.get("material").asString)
        }
    }.filter(String::isNotBlank)
}.getOrNull()

internal fun loadPublishedSheetMaterials(baseDir: File, job: String): List<String> {
    val metadata = File(baseDir, "$job/.metadata")
    // CROSS-PROGRAM: CV Cutlist Query owns job_data.json; Hours Tracker's worker publishes
    // the summary mirrors. These inputs are read-only here; CNC/custom labels cannot add stock.
    return sequenceOf("cv_job/job_data.json", "hardwoods/cutlist_index.json", "cache_static.json")
        .mapNotNull { path -> readSheetMaterialSummary(runCatching { File(metadata, path).readText() }.getOrNull(),
            if (path == "cv_job/job_data.json") 2 else null) }
        .firstOrNull().orEmpty()
}
