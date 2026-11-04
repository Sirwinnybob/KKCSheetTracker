package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.SheetStatus

/** [machine] owns this row's mix context; null for archived snapshots that never query a CNC. */
internal data class JobDetailMaterialEntry(val material: Material, val title: String, val mixSelection: ViewerMixSelection?,
    val machine: CncMachine? = null)

data class JobDetailMixRow(val title: String, val selection: ViewerMixSelection?, val interrupted: Boolean,
    val unmatched: Int, val error: Boolean, val needsRefresh: Boolean)

/**
 * Rows for one machine's catalog. A machine without FRZ support (Blue SCM) never offers an FRZ:
 * FRZ-only groups are hidden and a MIX/FRZ pair is presented by its MIX.
 */
internal fun jobDetailMixRows(material: Material, catalog: MixFilesCatalog?, frzSupported: Boolean = true,
    statusOf: (Int) -> SheetStatus): List<JobDetailMixRow> {
    val groups = catalog?.let(::groupMixFiles).orEmpty().filter { it.mix != null || (frzSupported && it.frz != null) }
    return groups.map { group ->
        val file = if (frzSupported) group.primary else group.mix!!
        val interrupted = frzSupported && group.isInterrupted
        val items = matchEntries(file.entries, sheetPages(material.metadata?.pages.orEmpty()))
        JobDetailMixRow("${group.title} - ${material.materialName}", ViewerMixSelection(group.title, file.file),
            interrupted, items.count { it is MixItem.Unmatched }, if (frzSupported) group.errorFile != null else file.error != null,
            file.entries.any { it.headerDrift } || items.filterIsInstance<MixItem.Sheet>().any {
                interrupted && it.remaining != null && (isLockedStatus(statusOf(it.page.pageNumber)) != (it.remaining == 0))
            })
    }.ifEmpty { listOf(JobDetailMixRow(material.materialName, null, false, 0, false, false)) }
}

/**
 * Machines whose catalog Job Detail shows for one material: the single owner, or each holder separately when
 * the folder is on both machines (never merged). Archived snapshots ([enabled] false) have no machine.
 */
internal fun jobDetailMaterialMachines(enabled: Boolean, statesByMachine: Map<String, CatalogState?>,
    machines: List<CncMachine> = CncMachines.mixMachines): List<CncMachine?> {
    if (!enabled) return listOf(null)
    return when (val owner = resolveMaterialOwnership(statesByMachine, machines = machines)) {
        is MaterialOwnership.Single -> listOf(owner.machine)
        is MaterialOwnership.Ambiguous -> owner.machines
    }
}
