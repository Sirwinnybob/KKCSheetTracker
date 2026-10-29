package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.MaterialLastTouch
import com.kkc.sheettracker.data.nextIncompletePage
import com.kkc.sheettracker.data.trackablePages
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.data.unified.UnifiedJobInfo

data class CncAppStateProjection(
    val programIndex: CncProgramIndex = CncProgramIndex.EMPTY,
    val materialRows: Map<JobMaterialKey, DashboardRecentMaterialItem> = emptyMap(),
)

data class CncProgramHints(val programs: List<ProgramCandidate> = emptyList()) {
    companion object { val EMPTY = CncProgramHints() }
}

internal fun cncProgramHints(status: MachineStatus): CncProgramHints {
    if (status !is MachineStatus.Live) return CncProgramHints.EMPTY
    val candidates = buildList {
        status.loaded?.let { program ->
            if (program.ambiguous) addAll(program.candidates)
            else add(ProgramCandidate(program.path, program.jobFolder))
        }
        status.lastFinished?.path?.let { add(ProgramCandidate(path = it)) }
    }.filter { !it.path.isNullOrBlank() || !it.jobFolder.isNullOrBlank() }
        .map { ProgramCandidate(it.path?.cncKey(), it.jobFolder?.cncKey()) }
        .distinct().sortedWith(compareBy({ it.jobFolder.orEmpty() }, { it.path.orEmpty() }))
    return CncProgramHints(candidates)
}

internal fun cncHydrationFolders(hints: CncProgramHints, activeJobs: List<UnifiedJobInfo>): Set<String> {
    val roster = cncActiveFoldersByNumber(activeJobs)
    return hints.programs.flatMap { resolveCncProgramFolders(it.jobFolder, it.path, roster) }.toSet()
}

/** Builds presentation data from the same counts as ordinary dashboard materials; no writes. */
internal fun buildCncMaterialRow(
    job: Job,
    material: Material,
    model: MaterialUiModel,
    pageStatuses: Map<Int, SheetStatusSnapshot>,
    touch: MaterialLastTouch?,
): DashboardRecentMaterialItem {
    val pages = trackablePages(material)
    val requested = touch?.page ?: pages.firstOrNull() ?: 1
    val clamped = pages.minByOrNull { kotlin.math.abs(it - requested) } ?: requested
    val next = nextIncompletePage(pages, pageStatuses, clamped)
    return DashboardRecentMaterialItem(job.folderName, job.jobNumber, material.materialName,
        material.pdfFilename, material.fileFingerprint, clamped, next, touch?.touchedAtMs ?: 0L,
        model.counts, model.completionFraction,
        material.metadata?.pages?.firstOrNull { it.pageNumber == next }?.thumbnailPath)
}
