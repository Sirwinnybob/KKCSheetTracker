package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.SpecialtyJob
import com.kkc.sheettracker.data.models.SpecialtyJobCard
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyScanState
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.StationProgress
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class SpecialtyStateStore(
    private val specialtyScanCoordinator: SpecialtyScanCoordinator,
    private val specialtyProgressStore: SpecialtyProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore,
    private val sheetRipProgressStore: SheetRipProgressStore,
    private val tabletItemsStore: TabletSpecialtyItemsStore,
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val hardwoodIndexGson = Gson()

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

    /**
     * Non-blocking detail-screen open refresh.
     * Keeps current UI content visible, invalidates per-job resolved-item cache,
     * then lets the coordinator verify this one job in the background.
     */
    fun refreshJobOnOpen(jobFolderName: String) {
        specialtyProgressStore.invalidateJobCache(jobFolderName)
        specialtyScanCoordinator.refreshJobOnOpen(jobFolderName)
    }

    fun loadSpecialtyItems(jobFolderName: String): List<SpecialtyItem> {
        return specialtyProgressStore.loadSpecialtyItems(jobFolderName)
    }

    /** Creates or updates a specialty item (admin edit overlay or tablet item), then invalidates the cache. */
    suspend fun saveSpecialtyItem(jobFolderName: String, item: TabletSpecialtyItem) =
        withContext(ioDispatcher) {
            tabletItemsStore.saveItem(jobFolderName, item)
            specialtyProgressStore.invalidateJobCache(jobFolderName)
        }

    /** Deletes a specialty item (writes tombstone in tablet sidecar), then invalidates the cache. */
    suspend fun deleteSpecialtyItem(jobFolderName: String, itemId: String) =
        withContext(ioDispatcher) {
            tabletItemsStore.deleteItemTombstone(jobFolderName, itemId)
            specialtyProgressStore.invalidateJobCache(jobFolderName)
        }

    /** Creates or updates a tablet-created specialty item, then invalidates the cache. */
    suspend fun saveTabletItem(jobFolderName: String, item: TabletSpecialtyItem) =
        saveSpecialtyItem(jobFolderName, item)

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
        val item = getResolvedItems(jobFolderName).firstOrNull { it.item.id == itemId }?.item
        specialtyProgressStore.setCompletion(
            jobFolderName = jobFolderName,
            itemId = itemId,
            completionKey = completionKey,
            completed = completed
        )
        if (completionKey.equals(SpecialtyStation.SAW.name, ignoreCase = true) && item != null) {
            autoCompleteDoorPanelRows(jobFolderName, item, completed)
        }
        item?.let { autoCompleteClosetRodRows(jobFolderName, it, completed) }
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
        resolvedItem?.item?.let {
            autoCompleteDoorPanelRows(jobFolderName, it, completed)
            autoCompleteClosetRodRows(jobFolderName, it, completed)
        }
    }

    private fun autoCompleteClosetRodRows(
        jobFolderName: String,
        item: SpecialtyItem,
        completed: Boolean
    ) {
        val automationKey = item.automationKey.orEmpty().trim()
        if (!automationKey.startsWith("closet_rods_auto|", ignoreCase = true)) return

        runCatching {
            val rawJson = loadHardwoodsCutlistIndexRawJson(baseDir.absolutePath, jobFolderName) ?: return
            val hardwoodIndex = hardwoodIndexGson.fromJson(rawJson, HardwoodCutlistIndex::class.java) ?: return
            val closetRows = hardwoodIndex.documents
                .firstOrNull { it.docType == HardwoodDocType.CLOSET_ROD_CUT_LIST }
                ?.rows
                ?: return
            val normalizedMaterial = normalizeClosetRodAutomationMaterial(automationKey, item.material)
            closetRows
                .filter { row ->
                    normalizeCabinetVisionUnitType(row.unitType) == "PER FT" &&
                        normalizeClosetRodMaterial(row.material) == normalizedMaterial
                }
                .forEach { target ->
                    hardwoodsProgressStore.setDoneCount(
                        jobFolderName = jobFolderName,
                        docType = HardwoodDocType.CLOSET_ROD_CUT_LIST.name,
                        rowId = target.rowId,
                        qty = target.qty,
                        doneCount = if (completed) target.qty else 0
                    )
                }
        }
    }

    private fun autoCompleteDoorPanelRows(
        jobFolderName: String,
        item: SpecialtyItem,
        completed: Boolean
    ) {
        if (!item.automationKey.orEmpty().trim().startsWith("door_panels_auto|", ignoreCase = true)) return

        runCatching {
            val rawJson = loadHardwoodsCutlistIndexRawJson(baseDir.absolutePath, jobFolderName) ?: return
            val hardwoodIndex = hardwoodIndexGson.fromJson(rawJson, HardwoodCutlistIndex::class.java) ?: return
            val doorCutRows = hardwoodIndex.documents
                .firstOrNull { it.docType == HardwoodDocType.DOOR_CUT_LIST }
                ?.rows
                ?: return
            val sheetRows = filterDoorCutRowsToSheets(
                rows = doorCutRows,
                unitTypeMetadata = parseDoorCutUnitTypeMetadata(rawJson)
            )
            val mappings = MaterialMappings.load(baseDir)

            matchingDoorPanelRows(item, sheetRows, mappings).forEach { target ->
                hardwoodsProgressStore.setDoneCount(
                    jobFolderName = jobFolderName,
                    docType = HardwoodDocType.DOOR_CUT_LIST.name,
                    rowId = target.rowId,
                    qty = target.qty,
                    doneCount = if (completed) target.qty else 0
                )
            }
        }
    }

    suspend fun patchSpecialtyItemFields(
        jobFolderName: String,
        itemId: String,
        dimensions: String?,
        quantity: Double?,
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
            val specialtyItems = job.resolvedItems
            val specialtyTotal = specialtyItems.size
            val specialtyCompleted = specialtyItems.count { it.isComplete }
            SpecialtyJobCard(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                hiddenFromProduction = job.hiddenFromProduction,
                totalItems = specialtyTotal,
                completedItems = specialtyCompleted,
                remainingItems = (specialtyTotal - specialtyCompleted).coerceAtLeast(0),
                completionFraction = if (specialtyTotal <= 0) 0f else specialtyCompleted.toFloat() / specialtyTotal.toFloat(),
                stationProgress = buildStationProgress(specialtyItems),
                lineupPosition = job.lineupPosition,
                labels = job.labels,
                isPending = job.isPending,
                boardSection = job.boardSection
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

private fun normalizeClosetRodAutomationMaterial(automationKey: String, material: String?): String {
    val fromKey = automationKey.substringAfter("closet_rods_auto|", missingDelimiterValue = "")
    return normalizeClosetRodMaterial(fromKey.ifBlank { material })
}

private fun normalizeClosetRodMaterial(value: String?): String {
    return value
        .orEmpty()
        .trim()
        .replace(Regex("""\s+"""), " ")
        .uppercase()
}
