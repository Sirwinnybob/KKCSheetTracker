package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine

/** One job's worth of TO_ORDER items, for the admin "To Order" tab. */
data class ToOrderGroup(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val items: List<SpecialtyResolvedItem>
)

/**
 * Aggregates the specialty + checklist items flagged [SpecialtyItemCategory.TO_ORDER] across
 * every job, mirroring the Hours Tracker web "To Order" tab (useAllJobsToOrder). Checklist items
 * are already folded into specialty items by [SpecialtyProgressStore], so a single per-job load
 * covers both.
 *
 * This performs per-job file I/O — call it off the main thread (Dispatchers.IO), never inside a
 * remember{} block.
 */
class ToOrderRepository(
    private val engine: UnifiedMetadataEngine,
    private val specialtyStore: SpecialtyProgressStore
) {
    fun loadGroups(): List<ToOrderGroup> {
        return engine.listJobs().mapNotNull { job ->
            val toOrder = specialtyStore.loadResolvedItems(job.folderName)
                .filter { it.item.category == SpecialtyItemCategory.TO_ORDER }
            if (toOrder.isEmpty()) null
            else ToOrderGroup(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                items = toOrder
            )
        }
    }
}
