package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.SpecialtyJob
import com.kkc.sheettracker.data.models.SpecialtyJobCard
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyScanState
import com.kkc.sheettracker.data.models.StationProgress
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class SpecialtyStateStore(
    private val specialtyScanCoordinator: SpecialtyScanCoordinator,
    private val specialtyProgressStore: SpecialtyProgressStore,
    private val sheetRipProgressStore: SheetRipProgressStore,
    private val tabletItemsStore: TabletSpecialtyItemsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val scanState: StateFlow<SpecialtyScanState>
        get() = specialtyScanCoordinator.state

    val progressVersion: StateFlow<Long>
        get() = specialtyProgressStore.progressVersion

    private val _sheetRipDoneVersion = MutableStateFlow(0L)
    val sheetRipDoneVersion: StateFlow<Long> = _sheetRipDoneVersion.asStateFlow()

    /** The deviceId of this tablet — used by the UI to identify own items. */
    val tabletId: String get() = tabletItemsStore.tabletId

    /** Trigger a rescan of all job folders from disk (e.g. on screen entry or periodic polling). */
    fun refresh(reason: RefreshReason = RefreshReason.APP_FOREGROUND, force: Boolean = true) {
        specialtyScanCoordinator.refresh(reason, force)
    }

    /** Creates or updates a tablet-created specialty item, then invalidates the cache. */
    suspend fun saveTabletItem(jobFolderName: String, item: TabletSpecialtyItem) =
        withContext(ioDispatcher) {
            tabletItemsStore.saveItem(jobFolderName, item)
            specialtyProgressStore.invalidateJobCache(jobFolderName)
        }

    /** Deletes a tablet-created specialty item (by id, "tablet:" prefix optional), then invalidates the cache. */
    suspend fun deleteTabletItem(jobFolderName: String, itemId: String) =
        withContext(ioDispatcher) {
            tabletItemsStore.deleteItem(jobFolderName, itemId)
            specialtyProgressStore.invalidateJobCache(jobFolderName)
        }

    fun loadSheetRipDone(jobFolderName: String): Map<String, Boolean> {
        return sheetRipProgressStore.loadDone(jobFolderName)
    }

    suspend fun setSheetRipDone(
        jobFolderName: String,
        itemId: String,
        done: Boolean
    ) = withContext(ioDispatcher) {
        sheetRipProgressStore.setDone(jobFolderName, itemId, done)
        _sheetRipDoneVersion.value = _sheetRipDoneVersion.value + 1L
    }

    fun getJobs(): List<SpecialtyJob> {
        return specialtyScanCoordinator.state.value.snapshot.jobs
    }

    fun getResolvedItems(jobFolderName: String): List<SpecialtyResolvedItem> {
        return specialtyProgressStore.loadResolvedItems(jobFolderName)
    }

    suspend fun setItemCompletionKey(
        jobFolderName: String,
        itemId: String,
        completionKey: String,
        completed: Boolean
    ) = withContext(ioDispatcher) {
        specialtyProgressStore.setCompletion(
            jobFolderName = jobFolderName,
            itemId = itemId,
            completionKey = completionKey,
            completed = completed
        )
    }

    suspend fun setItemCompletion(
        jobFolderName: String,
        itemId: String,
        completed: Boolean
    ) = withContext(ioDispatcher) {
        val resolvedItem = getResolvedItems(jobFolderName).firstOrNull { it.item.id == itemId }
        val completionKeys = resolvedItem
            ?.item
            ?.let(::completionKeysForItem)
            ?: listOf(SpecialtyProgressStore.ITEM_COMPLETION_KEY)

        specialtyProgressStore.setCompletions(
            jobFolderName = jobFolderName,
            itemId = itemId,
            completionKeys = completionKeys,
            completed = completed
        )
    }

    suspend fun patchSpecialtyItemFields(
        jobFolderName: String,
        itemId: String,
        dimensions: String?,
        quantity: Int?,
        material: String?
    ) = withContext(ioDispatcher) {
        specialtyProgressStore.updateSpecialtyItemFields(
            jobFolderName = jobFolderName,
            itemId = itemId,
            dimensions = dimensions,
            quantity = quantity,
            material = material
        )
    }

    fun deriveJobCards(): List<SpecialtyJobCard> {
        return getJobs().map { job ->
            SpecialtyJobCard(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                hiddenFromProduction = job.hiddenFromProduction,
                totalItems = job.totalItems,
                completedItems = job.completedItems,
                remainingItems = job.remainingItems,
                completionFraction = job.completionFraction,
                stationProgress = buildStationProgress(job.resolvedItems),
                lineupPosition = job.lineupPosition
            )
        }
    }

    private fun buildStationProgress(resolvedItems: List<SpecialtyResolvedItem>): List<StationProgress> {
        if (resolvedItems.isEmpty()) return emptyList()
        val totals = LinkedHashMap<String, Int>()
        val dones = LinkedHashMap<String, Int>()
        resolvedItems.forEach { resolved ->
            resolved.item.stations.forEach { station ->
                val key = station.name
                totals[key] = (totals[key] ?: 0) + 1
                if (resolved.isComplete) dones[key] = (dones[key] ?: 0) + 1
            }
        }
        return totals.keys.map { s -> StationProgress(s, dones[s] ?: 0, totals[s] ?: 0) }
    }
}
