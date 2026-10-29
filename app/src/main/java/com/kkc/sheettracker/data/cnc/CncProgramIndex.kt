package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import java.util.Locale

data class CncSheetRef(val jobFolderName: String, val pdfFilename: String, val page: Int)

data class CncProgramIndex(
    val foldersByNumber: Map<String, Set<String>> = emptyMap(),
    val programsByFolder: Map<String, Map<String, Set<CncSheetRef>>> = emptyMap(),
    val headPagesByMaterial: Map<JobMaterialKey, Map<Int, Int>> = emptyMap(),
    val thumbnailsBySheet: Map<CncSheetRef, String?> = emptyMap(),
) {
    companion object { val EMPTY = CncProgramIndex() }
}

internal fun String.cncKey(): String = trim().lowercase(Locale.ROOT)
internal fun String.cncStem(): String = trim().replace(Regex("\\.pgm$", RegexOption.IGNORE_CASE), "").cncKey()

internal fun cncActiveFoldersByNumber(activeJobs: List<UnifiedJobInfo>): Map<String, Set<String>> =
    activeJobs.groupBy { it.jobNumber.cncKey() }.mapValues { (_, jobs) -> jobs.map { it.folderName }.toSet() }

private val jobNumberPrefix = Regex("^(\\d+[A-Za-z]?)(?=$|[\\s-])")
private val jobFolderSegment = Regex("^\\d+[A-Za-z]?\\s*-\\s*\\S.*")

/** Windows paths must be split explicitly: Android's File does not recognize backslashes. */
internal fun resolveCncProgramFolders(
    jobFolder: String?,
    path: String?,
    foldersByNumber: Map<String, Set<String>>,
): Set<String> {
    val folders = foldersByNumber.values.flatten().toSet()
    fun resolveHint(hint: String): Set<String> {
        val exact = folders.filter { it.equals(hint.trim(), ignoreCase = true) }
        if (exact.isNotEmpty()) return exact.toSet().takeIf { it.size == 1 }.orEmpty()
        val number = jobNumberPrefix.find(hint.trim())?.groupValues?.get(1)?.cncKey() ?: return emptySet()
        return foldersByNumber[number].orEmpty().takeIf { it.size == 1 }.orEmpty()
    }
    if (!jobFolder.isNullOrBlank()) return resolveHint(jobFolder)
    val segments = path?.split('/', '\\')?.filter { it.isNotBlank() }.orEmpty()
    val exact = segments.flatMap { segment -> folders.filter { it.equals(segment, ignoreCase = true) } }.toSet()
    if (exact.isNotEmpty()) return exact.takeIf { it.size == 1 }.orEmpty()
    val hints = segments.filter { jobFolderSegment.matches(it) }
    return hints.singleOrNull()?.let(::resolveHint).orEmpty()
}

internal fun buildCncProgramIndex(activeJobs: List<UnifiedJobInfo>, loadedJobs: List<Job>): CncProgramIndex {
    val roster = cncActiveFoldersByNumber(activeJobs)
    val activeFolders = activeJobs.map { it.folderName }.toSet()
    val programs = linkedMapOf<String, MutableMap<String, MutableSet<CncSheetRef>>>()
    val heads = linkedMapOf<JobMaterialKey, Map<Int, Int>>()
    val thumbnails = linkedMapOf<CncSheetRef, String?>()
    for (job in loadedJobs.filter { it.folderName in activeFolders }) {
        val byStem = programs.getOrPut(job.folderName.cncKey()) { linkedMapOf() }
        for (material in job.materials) {
            val metadata = material.metadata?.pages.orEmpty()
            val byPage = metadata.associateBy { it.pageNumber }
            heads[JobMaterialKey(job.folderName, material.pdfFilename)] =
                (1..material.pageCount).associateWith { material.resolveHeadPage(it) }
            for (page in metadata) {
                if ((page.hiddenInApp || page.trackingExcluded) && !page.isPartListContinuation) continue
                val head = material.resolveHeadPage(page.pageNumber)
                val headMeta = byPage[head] ?: continue
                if (head !in 1..material.pageCount || headMeta.hiddenInApp ||
                    headMeta.trackingExcluded || headMeta.isPartListContinuation) continue
                val ref = CncSheetRef(job.folderName, material.pdfFilename, head)
                thumbnails[ref] = headMeta.thumbnailPath
                for (stem in inferSheetFiles(page).map { it.cncStem() }.filter { it.isNotBlank() }) {
                    byStem.getOrPut(stem) { linkedSetOf() }.add(ref)
                }
            }
        }
    }
    return CncProgramIndex(roster, programs.mapValues { (_, stems) ->
        stems.mapValues { (_, refs) -> refs.toSet() }
    }, heads.toMap(), thumbnails.toMap())
}

