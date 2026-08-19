package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.models.CacheIndexRoot

/**
 * Decorates [delegate] (the registry-singleton [FileBackedUnifiedMetadataEngine]) with
 * live WebSocket state for the three jobs-list-read methods, leaving every other method
 * an exact passthrough via Kotlin interface delegation. See
 * docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md.
 *
 * [setConnected] clearing the live map on disconnect (rather than leaving stale entries
 * behind) is what makes falling back to [delegate] safe at any moment.
 */
class LiveAwareUnifiedMetadataEngine(
    private val delegate: UnifiedMetadataEngine
) : UnifiedMetadataEngine by delegate {

    @Volatile private var liveJobs: Map<String, CacheIndexRoot> = emptyMap()
    @Volatile private var connected = false

    fun applySnapshot(jobs: Map<String, CacheIndexRoot>) {
        liveJobs = jobs.toMap()
        connected = true
    }

    fun applyDelta(folderName: String, index: CacheIndexRoot?) {
        liveJobs = if (index == null) liveJobs - folderName else liveJobs + (folderName to index)
    }

    fun setConnected(value: Boolean) {
        connected = value
        if (!value) liveJobs = emptyMap()
    }

    override fun listJobsFromCacheIndex(): Pair<List<UnifiedJobInfo>, List<String>> =
        if (connected) buildFromLive() to emptyList() else delegate.listJobsFromCacheIndex()

    override fun getProgressFromIndex(folderName: String): CacheIndexProgressSummary? =
        if (connected) liveJobs[folderName]?.progressSummary else delegate.getProgressFromIndex(folderName)

    override fun getCachedJobInfos(): List<UnifiedJobInfo> =
        if (connected) buildFromLive() else delegate.getCachedJobInfos()

    private fun buildFromLive(): List<UnifiedJobInfo> =
        liveJobs.values.mapNotNull { root ->
            val rawInfo = root.jobInfo ?: return@mapNotNull null
            UnifiedJobInfo(
                folderName = rawInfo.folderName,
                jobNumber = rawInfo.jobNumber,
                jobName = rawInfo.jobName,
                hiddenFromProduction = rawInfo.hiddenFromProduction,
                lineupPosition = rawInfo.lineupPosition,
                indexProgress = root.progressSummary
            )
        }.sortedWith(
            compareBy<UnifiedJobInfo> { it.lineupPosition ?: Int.MAX_VALUE }
                .thenByDescending { it.jobNumber.toIntOrNull() ?: 0 }
                .thenBy { it.folderName }
        )
}
