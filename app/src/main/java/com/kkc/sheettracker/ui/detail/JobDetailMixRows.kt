package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.SheetStatus

data class JobDetailMixRow(val title: String, val selection: ViewerMixSelection?, val interrupted: Boolean,
    val unmatched: Int, val error: Boolean, val needsRefresh: Boolean)

internal fun jobDetailMixRows(material: Material, catalog: MixFilesCatalog?, statusOf: (Int) -> SheetStatus): List<JobDetailMixRow> {
    val groups = catalog?.let(::groupMixFiles).orEmpty().filter { it.mix != null || it.frz != null }
    return groups.map { group ->
        val file = group.primary
        val items = matchEntries(file.entries, sheetPages(material.metadata?.pages.orEmpty()))
        JobDetailMixRow("${group.title} - ${material.materialName}", ViewerMixSelection(group.title, file.file),
            group.isInterrupted, items.count { it is MixItem.Unmatched }, group.errorFile != null,
            file.entries.any { it.headerDrift } || items.filterIsInstance<MixItem.Sheet>().any {
                group.isInterrupted && it.remaining != null && (isLockedStatus(statusOf(it.page.pageNumber)) != (it.remaining == 0))
            })
    }.ifEmpty { listOf(JobDetailMixRow(material.materialName, null, false, 0, false, false)) }
}
