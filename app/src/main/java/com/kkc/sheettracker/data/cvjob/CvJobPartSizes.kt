package com.kkc.sheettracker.data.cvjob

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Part

/*
 * CROSS-PROGRAM: Cabinet Vision occasionally exports a CNC part as `w=0 l=0`, and the PDF splitter
 * (PGM Sorting) stores that in the CNC sidecar. When the splitter could not recover the size itself,
 * the tablet fills it from `<job>/.metadata/cv_job/job_data.json` (CV Cutlist Query, read-only).
 * Same matching rule as the splitter: cabinet number + part name, visible parts preferred, and only
 * when every candidate agrees on one width and length.
 */

private const val TAG = "CvJobPartSizes"

class CvPartSizeIndex internal constructor(private val byCabinetAndName: Map<Pair<String, String>, List<Candidate>>) {
    internal class Candidate(val hidden: Boolean, val width: Double, val length: Double)

    /** The single agreed (width, length) for this cabinet and part name, or null when unknown or ambiguous. */
    fun sizeFor(cabinetNumber: Int, partName: String?): Pair<Double, Double>? {
        if (cabinetNumber <= 0) return null
        val all = byCabinetAndName[cabinetNumber.toString() to matchText(partName)] ?: return null
        val candidates = all.filter { !it.hidden }.ifEmpty { all }
        val sizes = candidates.map { it.width to it.length }.toSet()
        return sizes.singleOrNull()
    }
}

private fun matchText(value: String?): String = value.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()

private fun JsonObject.text(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.positiveSize(key: String): Double? =
    text(key)?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

/** Null when [json] is not a CV job-data object with a `parts` array. */
fun parseCvPartSizeIndex(json: String): CvPartSizeIndex? {
    val root = try {
        JsonParser.parseString(json)
    } catch (e: Exception) {
        return null
    }
    if (root == null || !root.isJsonObject) return null
    val parts: JsonArray = root.asJsonObject.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val index = mutableMapOf<Pair<String, String>, MutableList<CvPartSizeIndex.Candidate>>()
    for (element in parts) {
        val part = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val width = part.positiveSize("width") ?: continue
        val length = part.positiveSize("length") ?: continue
        val key = matchText(part.text("cabinet")) to matchText(part.text("description"))
        val hidden = part.text("hidden") == "true"
        index.getOrPut(key) { mutableListOf() }.add(CvPartSizeIndex.Candidate(hidden, width, length))
    }
    return CvPartSizeIndex(index)
}

/** Reads and parses the job's `job_data.json`; null when it is absent or unreadable. Never writes. */
fun loadCvPartSizeIndex(basePath: String, jobFolderName: String): CvPartSizeIndex? {
    if (basePath.isBlank() || jobFolderName.isBlank()) return null
    val file = cvJobDataFile(basePath, jobFolderName)
    if (!file.isFile) return null
    return try {
        parseCvPartSizeIndex(file.readText())
    } catch (e: Exception) {
        Log.w(TAG, "Could not read ${file.path}: ${e.message}")
        null
    }
}

// Gson builds these models without running Kotlin's null checks, so a field missing from the JSON
// is null at runtime. Every non-null field `copy` would otherwise carry over is supplied explicitly.
private fun <T> gsonNullable(value: T): T? = value

private fun Part.missingSize() = !(width > 0.0) || !(length > 0.0)

/**
 * Returns [job] with zero-sized CNC parts filled from the index [loadIndex] provides, or [job] itself
 * (same instance) when nothing needs or can be filled. [loadIndex] only runs when a part is missing a size.
 */
fun fillMissingCncPartSizes(job: Job, loadIndex: () -> CvPartSizeIndex?): Job {
    val needsFill = job.materials.any { material ->
        material.metadata?.pages.orEmpty().any { page -> page.parts.any { it.missingSize() } }
    }
    if (!needsFill) return job
    val index = loadIndex() ?: return job
    var changed = false
    val materials = job.materials.map { material ->
        val metadata = material.metadata ?: return@map material
        var materialChanged = false
        val pages = metadata.pages.map { page ->
            if (page.parts.none { it.missingSize() }) return@map page
            var pageChanged = false
            val parts = page.parts.map { part ->
                val size = if (part.missingSize()) index.sizeFor(part.cabNumber, part.name) else null
                if (size == null) part else {
                    pageChanged = true
                    part.copy(
                        width = size.first,
                        length = size.second,
                        name = gsonNullable(part.name) ?: "",
                        room = gsonNullable(part.room) ?: "",
                    )
                }
            }
            if (!pageChanged) page else {
                materialChanged = true
                page.copy(
                    sheetId = gsonNullable(page.sheetId) ?: "",
                    sheetFiles = gsonNullable(page.sheetFiles) ?: emptyList(),
                    parts = parts,
                )
            }
        }
        if (!materialChanged) material else {
            changed = true
            material.copy(
                pdfFilename = gsonNullable(material.pdfFilename) ?: "",
                materialName = gsonNullable(material.materialName) ?: "",
                fileFingerprint = gsonNullable(material.fileFingerprint) ?: "",
                metadata = metadata.copy(
                    jobNumber = gsonNullable(metadata.jobNumber) ?: "",
                    jobName = gsonNullable(metadata.jobName) ?: "",
                    material = gsonNullable(metadata.material) ?: "",
                    pdfFilename = gsonNullable(metadata.pdfFilename) ?: "",
                    pages = pages,
                ),
            )
        }
    }
    return if (changed) job.copy(materials = materials) else job
}
