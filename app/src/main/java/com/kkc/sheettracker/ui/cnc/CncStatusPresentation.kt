package com.kkc.sheettracker.ui.cnc

import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.models.*

enum class CncPillKind { CUTTING, IDLE, FINISHED }
enum class CncPillPlacement { DASHBOARD, HEADER, NAVIGATOR }
data class CncPill(val kind: CncPillKind, val headerText: String, val navigatorText: String, val dashboardText: String)

private fun loadedPill(running: Boolean) = if (running)
    CncPill(CncPillKind.CUTTING, "ON CNC · CUTTING", "ON CNC", "ON CNC")
else CncPill(CncPillKind.IDLE, "LOADED · IDLE", "ON CNC", "LOADED · IDLE")

private fun CncSheetRef.sameSheet(other: CncSheetRef): Boolean =
    page == other.page && jobFolderName.equals(other.jobFolderName, true) && pdfFilename.equals(other.pdfFilename, true)

internal fun cncPillForSheet(marks: CncSheetMarks, sheet: CncSheetRef): CncPill? {
    val loaded = marks.loaded as? CncLoadedMark.Matched
    if (loaded?.sheet?.sameSheet(sheet) == true) return loadedPill(loaded.running)
    val finished = marks.lastFinished?.takeIf { it.sheet.sameSheet(sheet) } ?: return null
    return CncPill(CncPillKind.FINISHED, "CUT ON CNC ${finished.endLabel}", "CUT ${finished.endLabel}", "CUT ON CNC ${finished.endLabel}")
}

internal fun cncPillsForMaterial(
    marks: CncSheetMarks,
    jobFolderName: String,
    material: Material?,
    visiblePages: List<Int>,
): Map<Int, CncPill> {
    if (material == null) return emptyMap()
    return visiblePages.mapNotNull { page ->
        cncPillForSheet(marks, CncSheetRef(jobFolderName, material.pdfFilename, material.resolveHeadPage(page)))
            ?.let { page to it }
    }.toMap()
}

sealed interface CncDashboardRow {
    data class Material(val item: DashboardRecentMaterialItem, val openPage: Int, val pill: CncPill?) : CncDashboardRow
    data class Unknown(val name: String, val running: Boolean) : CncDashboardRow
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

