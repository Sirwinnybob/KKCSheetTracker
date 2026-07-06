package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.JobLabel

interface UnifiedMetadataEngine {
    fun updateBasePath(path: String)
    fun invalidateAll()
    fun invalidateJob(jobFolderName: String)

    fun getBoardGridColumns(): Int
    fun listJobs(): List<UnifiedJobInfo>

    /** The full label catalog defined in job_board.json (id/name/color), sorted by name. */
    fun listAllLabels(): List<JobLabel>

    /** Returns cached job info for a single folder, or null if not loaded yet. No I/O. */
    fun getJobInfo(folderName: String): UnifiedJobInfo?

    /**
     * Single-job board-merged accessor. Loads this one folder's cache_static.json (reusing the
     * in-memory cache when fresh) and merges its job_board.json config (labels/isPending/
     * boardSection). Returns null if the folder is gated out, has no cache yet, or fails to parse.
     * Use this instead of listJobsFromCacheOnly().find { ... } when only one job is needed —
     * avoids the O(N) full-base-dir scan + sort.
     */
    fun getMergedJobInfo(folderName: String): UnifiedJobInfo?

    /**
     * Fast cache-only scan: reads each job's cache_static.json without any staleness check.
     * Populates the in-memory cache so subsequent snapshot calls are instant.
     * Returns the list of jobs found in cache paired with folder names that have no cache yet.
     */
    fun listJobsFromCacheOnly(): Pair<List<UnifiedJobInfo>, List<String>>

    /**
     * Per-job deep refresh: runs the full staleness check for [folderName] only.
     * If the cache is stale, re-parses raw files and updates the in-memory cache.
     * Returns true if the data changed, false if the cache was already fresh.
     */
    fun refreshJobDeep(folderName: String): Boolean

    /**
     * Deep-scans every gated-in job folder: runs the full per-file staleness check and
     * re-parses raw metadata for any job whose cache is stale. Returns the folder names whose
     * data actually changed. Fresh jobs cost only a few stat calls; only stale jobs re-parse.
     */
    fun deepScanAllJobs(): List<String>

    /**
     * Loads (or reloads) a single job's data directly from its cache_static.json
     * without a staleness check. Used by the StaticCachePoller when the server
     * updates a cache file. Returns the job's UnifiedJobInfo on success, null on failure.
     */
    fun loadJobFromCacheFile(folderName: String): UnifiedJobInfo?
    fun getCncSnapshot(jobFolderName: String): UnifiedCncSnapshot?
    fun getHardwoodsSnapshot(jobFolderName: String): UnifiedHardwoodsSnapshot?
    fun getHardwoodsRevisionHistory(jobFolderName: String): UnifiedHardwoodsRevisionHistory
    fun getAssemblySnapshot(jobFolderName: String): UnifiedAssemblySnapshot?

    fun getCabinetSheetIndex(jobFolderName: String): UnifiedCabinetIndexLookup
    fun getPdfCatalog(jobFolderName: String): UnifiedPdfCatalog
    fun findReferencePdfFilename(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferenceLookup
    fun hasReferenceDocument(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferencePresence
    fun hasThreeDAssets(jobFolderName: String): UnifiedThreeDPresence

    fun resolveCabinetJump(jobFolderName: String, cabinetNumber: String): UnifiedCabinetJump
    fun resolveCabinetContext(jobFolderName: String, cabinetNumber: String): UnifiedCabinetContext
    fun resolveCabinetParts(
        jobFolderName: String,
        cabinetNumber: String,
        overlayLookup: UnifiedPartOverlayLookup
    ): UnifiedAssemblyCabinetParts

    fun getBoardStockRows(
        jobFolderName: String,
        includeProgressOverlay: Boolean = true,
        overlayLookup: UnifiedBoardStockOverlayLookup = UnifiedBoardStockOverlayLookup()
    ): UnifiedBoardStockRows

    fun getSignatures(jobFolderName: String): UnifiedMetadataSignature
}
