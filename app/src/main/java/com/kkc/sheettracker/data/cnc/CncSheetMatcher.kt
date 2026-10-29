package com.kkc.sheettracker.data.cnc

sealed interface CncLoadedMark {
    data class Matched(val sheet: CncSheetRef, val name: String, val running: Boolean) : CncLoadedMark
    data class Unmatched(val name: String, val running: Boolean) : CncLoadedMark
}
data class CncFinishedMark(val sheet: CncSheetRef, val endLabel: String)
data class CncSheetMarks(val loaded: CncLoadedMark? = null, val lastFinished: CncFinishedMark? = null)

internal fun formatCncEndTime(end: String?): String? {
    val match = Regex("^(\\d{1,2}):([0-5]\\d)(?::[0-5]\\d)?$").matchEntire(end.orEmpty()) ?: return null
    val hours = match.groupValues[1].toInt()
    return if (hours in 0..23) "$hours:${match.groupValues[2]}" else null
}

internal fun matchCncSheets(status: MachineStatus, index: CncProgramIndex): CncSheetMarks {
    if (status !is MachineStatus.Live) return CncSheetMarks()
    fun resolve(name: String, candidate: ProgramCandidate): CncSheetRef? {
        val folder = resolveCncProgramFolders(candidate.jobFolder, candidate.path, index.foldersByNumber)
            .singleOrNull() ?: return null
        return index.programsByFolder[folder.cncKey()]?.get(name.cncStem())?.singleOrNull()
    }
    val loaded = status.loaded?.let { program ->
        val name = program.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@let null
        val running = program.running ?: status.producing ?: (status.state == "cutting")
        val sheet = if (program.ambiguous) {
            program.candidates.distinctBy { it.path.orEmpty().cncKey() to it.jobFolder.orEmpty().cncKey() }
                .mapNotNull { resolve(name, it) }.singleOrNull()
        } else resolve(name, ProgramCandidate(program.path, program.jobFolder))
        if (sheet != null) CncLoadedMark.Matched(sheet, name, running) else CncLoadedMark.Unmatched(name, running)
    }
    val finished = status.lastFinished?.let { program ->
        val name = program.name ?: return@let null
        val endLabel = formatCncEndTime(program.end) ?: return@let null
        resolve(name, ProgramCandidate(path = program.path))?.let { CncFinishedMark(it, endLabel) }
    }
    return CncSheetMarks(loaded, finished)
}

