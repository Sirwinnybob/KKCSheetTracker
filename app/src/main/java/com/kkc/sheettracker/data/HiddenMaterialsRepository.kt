package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsJobDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File

private val hiddenMaterialsGson = Gson()

private val HIDDEN_MATERIALS_MODE_SUBDIR = mapOf(
    HiddenMaterialsMode.HARDWOODS to "hardwoods",
    HiddenMaterialsMode.SPECIALTY to "specialty"
)

internal fun hiddenMaterialsModeSubdir(mode: HiddenMaterialsMode): String =
    HIDDEN_MATERIALS_MODE_SUBDIR.getValue(mode)

fun hiddenMaterialsGlobalPath(baseDir: File, mode: HiddenMaterialsMode): File =
    File(baseDir, ".metadata/${hiddenMaterialsModeSubdir(mode)}/hidden_materials_global.json")

fun hiddenMaterialsJobPath(baseDir: File, mode: HiddenMaterialsMode, jobFolderName: String): File =
    File(baseDir, "$jobFolderName/.metadata/${hiddenMaterialsModeSubdir(mode)}/hidden_materials.json")

/**
 * Parses the hidden-materials document JSON shape shared by the live WebSocket's
 * `hiddenMaterials` field and by [HiddenMaterialsRepository.fetchDocument]'s assembled shape.
 */
internal fun parseHiddenMaterialsDocument(json: String): HiddenMaterialsDocument =
    runCatching { hiddenMaterialsGson.fromJson(json, HiddenMaterialsDocument::class.java) }
        .getOrNull().sanitized()

/**
 * Gson populates a Kotlin non-null field with an actual `null` when the JSON key is explicitly
 * present with a `null` value (reflection bypasses Kotlin's compile-time null-safety here) --
 * so a document read off the shared drive can violate these types even though they're declared
 * non-null. Coalesce every field/list back to its declared default immediately after parsing,
 * mirroring the Python backend's own defensive `str(entry.get("material", ""))`-style reads, so
 * a partially-null document degrades gracefully instead of crashing on first use (e.g.
 * [entryKey]'s `material.trim()`).
 */
@Suppress("SENSELESS_COMPARISON")
private fun HiddenMaterialEntry?.sanitized(): HiddenMaterialEntry {
    val entry = this ?: HiddenMaterialEntry()
    return HiddenMaterialEntry(
        docType = entry.docType ?: "",
        material = entry.material ?: "",
        hiddenAt = entry.hiddenAt ?: "",
        tabletId = entry.tabletId ?: ""
    )
}

@Suppress("SENSELESS_COMPARISON")
private fun HiddenMaterialsGlobalDoc?.sanitized(): HiddenMaterialsGlobalDoc {
    val doc = this ?: HiddenMaterialsGlobalDoc()
    return HiddenMaterialsGlobalDoc(entries = (doc.entries ?: emptyList()).map { it.sanitized() })
}

@Suppress("SENSELESS_COMPARISON")
private fun HiddenMaterialsJobDoc?.sanitized(): HiddenMaterialsJobDoc {
    val doc = this ?: HiddenMaterialsJobDoc()
    return HiddenMaterialsJobDoc(
        hides = (doc.hides ?: emptyList()).map { it.sanitized() },
        unhides = (doc.unhides ?: emptyList()).map { it.sanitized() }
    )
}

@Suppress("SENSELESS_COMPARISON")
private fun HiddenMaterialsDocument?.sanitized(): HiddenMaterialsDocument {
    val doc = this ?: HiddenMaterialsDocument()
    return HiddenMaterialsDocument(
        global = doc.global.sanitized(),
        jobs = (doc.jobs ?: emptyMap()).mapValues { it.value.sanitized() }
    )
}

private fun normalizeMaterial(material: String): String = material.trim().lowercase()

private fun entryKey(entry: HiddenMaterialEntry): Pair<String, String> =
    entry.docType to normalizeMaterial(entry.material)

/**
 * Effective-visibility rule from the 2026-09-08 hidden-materials design doc: a job's own
 * `unhides` always wins, then the global list or the job's own `hides` hide it, else it is
 * visible. Mirrors routes/hidden_materials_store.py's `is_hidden` on the backend -- keep the
 * two in sync if this logic ever changes.
 */
fun isHiddenIn(document: HiddenMaterialsDocument, jobFolderName: String, docType: String, material: String): Boolean {
    val key = docType to normalizeMaterial(material)
    val jobDoc = document.jobs[jobFolderName]
    if (jobDoc != null && jobDoc.unhides.any { entryKey(it) == key }) return false
    if (jobDoc != null && jobDoc.hides.any { entryKey(it) == key }) return true
    return document.global.entries.any { entryKey(it) == key }
}

/**
 * Reads hidden-materials state from the shared network drive: the mode's global auto-hide list
 * plus one job's override file. Storage paths match hidden_materials_global_path/job_path on
 * the backend (routes/hidden_materials_store.py). Written by Hours Tracker; read-only on the
 * tablet -- see kkc-metadata-map's Ownership Map.
 * Call on Dispatchers.IO.
 */
class HiddenMaterialsRepository(private val baseDir: File) {

    /**
     * Assembles a [HiddenMaterialsDocument] scoped to one job: the mode's full global list, plus
     * that one job's override file (if any) under `jobs[jobFolderName]`. This is a strict subset
     * of the live channel's broadcast document (which includes every job with an override), but
     * [isHiddenIn] only ever needs the current job's entry, so scanning every job folder on the
     * tablet (unlike the backend, which owns the whole tree) would be needless I/O.
     */
    fun fetchDocument(mode: HiddenMaterialsMode, jobFolderName: String): HiddenMaterialsDocument {
        val global = readGlobal(mode)
        val jobDoc = readJob(mode, jobFolderName)
        val jobs = if (jobDoc == HiddenMaterialsJobDoc()) emptyMap() else mapOf(jobFolderName to jobDoc)
        return HiddenMaterialsDocument(global = global, jobs = jobs)
    }

    private fun readGlobal(mode: HiddenMaterialsMode): HiddenMaterialsGlobalDoc {
        val file = hiddenMaterialsGlobalPath(baseDir, mode)
        if (!file.exists() || !file.isFile) return HiddenMaterialsGlobalDoc()
        return runCatching {
            hiddenMaterialsGson.fromJson(file.readText(), HiddenMaterialsGlobalDoc::class.java)
        }.getOrNull().sanitized()
    }

    private fun readJob(mode: HiddenMaterialsMode, jobFolderName: String): HiddenMaterialsJobDoc {
        val file = hiddenMaterialsJobPath(baseDir, mode, jobFolderName)
        if (!file.exists() || !file.isFile) return HiddenMaterialsJobDoc()
        return runCatching {
            hiddenMaterialsGson.fromJson(file.readText(), HiddenMaterialsJobDoc::class.java)
        }.getOrNull().sanitized()
    }
}
