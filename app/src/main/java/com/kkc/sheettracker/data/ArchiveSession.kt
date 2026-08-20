package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

/**
 * Read-only session descriptor for viewing an archived (restored-to-cache) Ready Jobs job on a
 * tablet. Wires up read-only instances of the four store classes plus a UnifiedMetadataEngine,
 * all pointed at [baseDir] — the archive cache's job-parent directory, i.e. the directory that
 * directly contains the restored job folder (mirrors the live-root layout that these stores and
 * UnifiedMetadataEngineRegistry.listJobs() expect: one folder-per-job under a single base dir).
 *
 * Every store is constructed with readOnly = true, which each store class enforces internally by
 * short-circuiting its own write/save methods (see ProgressStore, HardwoodsProgressStore,
 * SpecialtyProgressStore, PdfMarkupStore — each guards its mutating methods with
 * `if (readOnly) return`). This mirrors the existing view-only-mode wiring in MainActivity.kt
 * (ProgressStore) and NavGraph.kt (HardwoodsProgressStore, SpecialtyProgressStore), which already
 * pass `readOnly = isViewOnlyMode` from the live-job code paths.
 *
 * KNOWN LIMITATION — stale UnifiedMetadataEngine on cache-slot reuse: ArchiveCacheManager reuses
 * a stable, non-UUID path (`cacheRoot/archiveJobId`) across re-downloads of the same archiveJobId
 * (e.g. after 24h expiry prune-and-refetch, or a re-archived version landing in the same slot).
 * UnifiedMetadataEngineRegistry.getOrCreate keys purely on `baseDir.absolutePath + isDebugBuild`
 * and caches indefinitely — it has a `clear(baseDir, isDebugBuild)` but nothing in the codebase
 * calls it. If a tablet process stays resident across such a re-download (plausible for a shop
 * tablet left open through a shift), [create] will keep returning the previously-cached engine
 * instance pointed at the old files, silently serving stale metadata. Not exercised by this class
 * alone since nothing re-downloads to an existing slot yet — but whichever future task adds that
 * re-download path (ArchiveCacheManager or its caller) must call
 * `UnifiedMetadataEngineRegistry.clear(baseDir, isDebugBuild)` for the slot before or during the
 * re-download, or route through this session with a fresh baseDir per version instead.
 */
class ArchiveSession private constructor(
    val archiveJobId: String,
    val contentVersion: String,
    val baseDir: File,
    val folderName: String,
    val readOnly: Boolean,
    val tabletId: String,
    val progressStore: ProgressStore,
    val hardwoodsProgressStore: HardwoodsProgressStore,
    val specialtyProgressStore: SpecialtyProgressStore,
    val pdfMarkupStore: PdfMarkupStore,
    val unifiedEngine: UnifiedMetadataEngine,
) {
    companion object {
        /**
         * @param cacheJobParentDir the directory that directly contains the restored archive job
         * folder (i.e. the parent of e.g. `.../cache/100 - Alpha`), NOT the job folder itself.
         * UnifiedMetadataEngineRegistry.listJobs() scans baseDir's direct children as jobs, and
         * the four stores below resolve job-relative paths as `File(baseDir, jobFolderName)`, so
         * baseDir must be that parent directory for both to work correctly.
         * @param folderName the restored job folder's exact name under [cacheJobParentDir] (e.g.
         * `ArchiveCacheResult.Success.jobDir.name`, or the `folderName` already threaded through
         * the Task 7 nav route `archive/job/{archiveJobId}/{folderName}/{contentVersion}`). Must
         * be passed explicitly rather than re-derived by listing [cacheJobParentDir]'s children:
         * ProgressStore's own `init` creates a second directory (`.state`) under the same parent
         * once constructed, so on any call after the first, a directory-listing approach could
         * non-deterministically resolve to `.state` instead of the real job folder (File.listFiles
         * order is not guaranteed) and silently point every store at an empty path.
         *
         * This function does not itself verify that `File(cacheJobParentDir, folderName)` exists
         * — callers (Task 6/7) are responsible for ensuring the job directory has finished
         * extracting/restoring before calling [create]; calling this against a not-yet-populated
         * or evicted cache slot will construct stores pointed at nonexistent content rather than
         * failing loudly.
         */
        fun create(
            archiveJobId: String,
            contentVersion: String,
            cacheJobParentDir: File,
            folderName: String,
            tabletId: String,
            isDebugBuild: Boolean,
        ): ArchiveSession {
            val localStateDir = File(cacheJobParentDir, ".state")
            return ArchiveSession(
                archiveJobId = archiveJobId,
                contentVersion = contentVersion,
                baseDir = cacheJobParentDir,
                folderName = folderName,
                readOnly = true,
                tabletId = tabletId,
                progressStore = ProgressStore(
                    baseDir = cacheJobParentDir,
                    tabletId = tabletId,
                    localStateDir = localStateDir,
                    readOnly = true,
                ),
                hardwoodsProgressStore = HardwoodsProgressStore(
                    baseDir = cacheJobParentDir,
                    tabletId = tabletId,
                    readOnly = true,
                ),
                specialtyProgressStore = SpecialtyProgressStore(
                    baseDir = cacheJobParentDir,
                    tabletId = tabletId,
                    readOnly = true,
                ),
                pdfMarkupStore = PdfMarkupStore(
                    baseDir = cacheJobParentDir,
                    tabletId = tabletId,
                    readOnly = true,
                ),
                unifiedEngine = UnifiedMetadataEngineRegistry.getOrCreate(
                    baseDir = cacheJobParentDir,
                    isDebugBuild = isDebugBuild,
                ),
            )
        }
    }
}
