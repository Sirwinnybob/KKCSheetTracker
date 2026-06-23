package com.kkc.sheettracker.data

import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.SpecialtyJob
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

class SpecialtyRepository(
    private var baseDir: File,
    private val progressStore: SpecialtyProgressStore,
    private var unifiedEngine: UnifiedMetadataEngine? = null
) {
    private fun engine(): UnifiedMetadataEngine {
        val existing = unifiedEngine
        if (existing != null) return existing
        return UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG
        ).also { unifiedEngine = it }
    }

    fun updateBaseDir(newBaseDir: File) {
        baseDir = newBaseDir
        unifiedEngine = null
    }

    fun currentBasePath(): String = baseDir.absolutePath

    fun scanJobs(): List<SpecialtyJob> {
        val (cachedJobInfos, needsDeepLoad) = engine().listJobsFromCacheOnly()
        val jobInfos = if (needsDeepLoad.isEmpty()) cachedJobInfos else engine().listJobs()
        return jobInfos.map { info -> buildSpecialtyJob(info) }
        // Preserve production order (set by server in cache); no secondary sort needed
    }

    /** Re-builds one SpecialtyJob from the current engine cache. Used by SpecialtyScanCoordinator. */
    fun getUpdatedJob(folderName: String): SpecialtyJob? {
        val (jobInfos, _) = engine().listJobsFromCacheOnly()
        val info = jobInfos.find { it.folderName == folderName } ?: return null
        return buildSpecialtyJob(info)
    }

    private fun buildSpecialtyJob(info: UnifiedJobInfo): SpecialtyJob {
        val resolved = runCatching { progressStore.loadResolvedItems(info.folderName) }
            .getOrElse { emptyList() }
        val totalItems = resolved.size
        val completedItems = resolved.count { it.isComplete }
        return SpecialtyJob(
            folderName = info.folderName,
            jobNumber = info.jobNumber,
            jobName = info.jobName,
            hiddenFromProduction = info.hiddenFromProduction,
            totalItems = totalItems,
            completedItems = completedItems,
            remainingItems = (totalItems - completedItems).coerceAtLeast(0),
            completionFraction = if (totalItems <= 0) 0f else completedItems.toFloat() / totalItems.toFloat(),
            resolvedItems = resolved,
            lineupPosition = info.lineupPosition,
            labels = info.labels,
            isPending = info.isPending,
            boardSection = info.boardSection
        )
    }

    fun loadResolvedItems(jobFolderName: String): List<SpecialtyResolvedItem> {
        return runCatching { progressStore.loadResolvedItems(jobFolderName) }
            .getOrElse { emptyList() }
    }
}
