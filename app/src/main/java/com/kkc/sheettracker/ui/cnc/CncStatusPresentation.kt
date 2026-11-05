package com.kkc.sheettracker.ui.cnc

import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.models.*

enum class CncPillKind { CUTTING, IDLE, FINISHED }
enum class CncPillPlacement { DASHBOARD, HEADER, NAVIGATOR }
/** Which SCM a pill belongs to; tints the pill and its dot. Null keeps the status-kind color. */
enum class CncPillMachine { RED, BLUE }
data class CncPill(
    val kind: CncPillKind,
    val headerText: String,
    val navigatorText: String,
    val dashboardText: String,
    val machine: CncPillMachine? = null,
)

internal fun cncPillMachineFor(displayName: String): CncPillMachine? = when (displayName) {
    CncMachines.scm.displayName -> CncPillMachine.RED
    CncMachines.scmNew.displayName -> CncPillMachine.BLUE
    else -> null
}

private fun loadedPill(running: Boolean) = if (running)
    CncPill(CncPillKind.CUTTING, "ON CNC · CUTTING", "ON CNC", "ON CNC")
else CncPill(CncPillKind.IDLE, "LOADED · IDLE", "ON CNC", "LOADED · IDLE")

private fun CncSheetRef.sameSheet(other: CncSheetRef): Boolean =
    page == other.page && jobFolderName.equals(other.jobFolderName, true) && pdfFilename.equals(other.pdfFilename, true)

internal fun cncPillForSheet(marks: CncSheetMarks, sheet: CncSheetRef, machine: CncPillMachine? = null): CncPill? {
    val loaded = marks.loaded as? CncLoadedMark.Matched
    if (loaded?.sheet?.sameSheet(sheet) == true) return loadedPill(loaded.running).copy(machine = machine)
    val finished = marks.lastFinished?.takeIf { it.sheet.sameSheet(sheet) } ?: return null
    return CncPill(CncPillKind.FINISHED, "CUT ON CNC ${finished.endLabel}", "CUT ${finished.endLabel}",
        "CUT ON CNC ${finished.endLabel}", machine)
}

internal fun cncPillsForMaterial(
    marks: CncSheetMarks,
    jobFolderName: String,
    material: Material?,
    visiblePages: List<Int>,
    machine: CncPillMachine? = null,
): Map<Int, CncPill> {
    if (material == null) return emptyMap()
    return visiblePages.mapNotNull { page ->
        cncPillForSheet(marks, CncSheetRef(jobFolderName, material.pdfFilename, material.resolveHeadPage(page)), machine)
            ?.let { page to it }
    }.toMap()
}

/** One sheet across every machine: a machine with it loaded wins over one that last finished it. */
internal fun cncPillForSheet(machines: List<Pair<String, CncSheetMarks>>, sheet: CncSheetRef): CncPill? {
    val pills = machines.mapNotNull { (name, marks) -> cncPillForSheet(marks, sheet, cncPillMachineFor(name)) }
    return pills.firstOrNull { it.kind != CncPillKind.FINISHED } ?: pills.firstOrNull()
}

/** A sheet another CNC has loaded that the viewer can jump to. */
data class CncJumpTarget(val machineName: String, val machine: CncPillMachine?, val sheet: CncSheetRef, val running: Boolean)

/**
 * The other CNC's loaded sheet, for toggling between machines from the viewer. A machine whose loaded sheet
 * is the one on screen is never a target. When the screen shows neither machine's sheet and both have one,
 * the machine other than [viewerMachineName] wins, then a cutting machine over an idle one.
 */
internal fun otherCncJumpTarget(machines: List<Pair<String, CncSheetMarks>>, current: CncSheetRef,
    viewerMachineName: String? = null): CncJumpTarget? =
    machines.mapNotNull { (name, marks) ->
        (marks.loaded as? CncLoadedMark.Matched)?.let { CncJumpTarget(name, cncPillMachineFor(name), it.sheet, it.running) }
    }.filterNot { it.sheet.sameSheet(current) }
        .sortedWith(compareBy<CncJumpTarget> { it.machineName == viewerMachineName }.thenBy { !it.running })
        .firstOrNull()

internal fun cncPillsForMaterial(
    machines: List<Pair<String, CncSheetMarks>>,
    jobFolderName: String,
    material: Material?,
    visiblePages: List<Int>,
): Map<Int, CncPill> {
    if (material == null) return emptyMap()
    return visiblePages.mapNotNull { page ->
        cncPillForSheet(machines, CncSheetRef(jobFolderName, material.pdfFilename, material.resolveHeadPage(page)))
            ?.let { page to it }
    }.toMap()
}

sealed interface CncDashboardRow {
    data class Material(val item: DashboardRecentMaterialItem, val openPage: Int, val pill: CncPill?) : CncDashboardRow
    data class Unknown(val name: String, val running: Boolean, val machine: String? = null) : CncDashboardRow
}
data class CncDashboardProjection(val rows: List<CncDashboardRow>, val recentCount: Int)

internal fun projectCncDashboard(
    recent: List<DashboardRecentMaterialItem>,
    projection: CncAppStateProjection,
    marks: CncSheetMarks,
): CncDashboardProjection {
    val rows = recent.map { CncDashboardRow.Material(it, it.nextIncompletePage, null) }.toMutableList<CncDashboardRow>()
    when (val loaded = marks.loaded) {
        is CncLoadedMark.Matched -> {
            val ref = loaded.sheet
            val key = JobMaterialKey(ref.jobFolderName, ref.pdfFilename)
            val item = projection.materialRows[key]
            if (item == null) rows.add(0, CncDashboardRow.Unknown(loaded.name, loaded.running))
            else {
                rows.removeAll { row -> row is CncDashboardRow.Material &&
                    row.item.jobFolderName == ref.jobFolderName && row.item.pdfFilename == ref.pdfFilename }
                rows.add(0, CncDashboardRow.Material(item.copy(nextIncompletePage = ref.page,
                    thumbnailPath = projection.programIndex.thumbnailsBySheet[ref]), ref.page, loadedPill(loaded.running)))
            }
        }
        is CncLoadedMark.Unmatched -> rows.add(0, CncDashboardRow.Unknown(loaded.name, loaded.running))
        null -> Unit
    }
    return CncDashboardProjection(rows.toList(), recent.size)
}


/** Preserve one loaded card per machine even when both point to the same material. */
internal fun projectCncDashboard(
    recent: List<DashboardRecentMaterialItem>,
    projection: CncAppStateProjection,
    machines: List<Pair<String, CncSheetMarks>>,
): CncDashboardProjection {
    val rows = recent.map { CncDashboardRow.Material(it, it.nextIncompletePage, null) }.toMutableList<CncDashboardRow>()
    for ((machine, marks) in machines.asReversed()) {
        when (val loaded = marks.loaded) {
            is CncLoadedMark.Matched -> {
                val ref = loaded.sheet
                val item = projection.materialRows[JobMaterialKey(ref.jobFolderName, ref.pdfFilename)]
                if (item == null) rows.add(0, CncDashboardRow.Unknown(loaded.name, loaded.running, machine))
                else {
                    rows.removeAll { row -> row is CncDashboardRow.Material && row.pill == null &&
                        row.item.jobFolderName.equals(ref.jobFolderName, true) && row.item.pdfFilename.equals(ref.pdfFilename, true) }
                    val pill = loadedPill(loaded.running).let {
                        it.copy(dashboardText = "${machine.uppercase()} · ${it.dashboardText}", machine = cncPillMachineFor(machine))
                    }
                    rows.add(0, CncDashboardRow.Material(item.copy(nextIncompletePage = ref.page,
                        thumbnailPath = projection.programIndex.thumbnailsBySheet[ref]), ref.page, pill))
                }
            }
            is CncLoadedMark.Unmatched -> rows.add(0, CncDashboardRow.Unknown(loaded.name, loaded.running, machine))
            null -> Unit
        }
    }
    return CncDashboardProjection(rows, recent.size)
}
