package com.kkc.sheettracker.data.unified

interface UnifiedMetadataEngine {
    fun updateBasePath(path: String)
    fun invalidateAll()
    fun invalidateJob(jobFolderName: String)

    fun getBoardGridColumns(): Int
    fun listJobs(): List<UnifiedJobInfo>

    /** Returns cached job info for a single folder, or null if not loaded yet. No I/O. */
    fun getJobInfo(folderName: String): UnifiedJobInfo?

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
