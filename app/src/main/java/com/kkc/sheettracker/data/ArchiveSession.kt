package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

/**
 * Read-only dependency graph for viewing an archived (restored-to-cache) Ready Jobs job on a
 * tablet. All repositories, stores, coordinators, and derived state stores point at [baseDir] —
 * the archive cache's job-parent directory, i.e. the directory that directly contains the
 * restored job folder (mirrors the live-root layout that these stores and
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
    val jobRepository: JobRepository,
    val hardwoodsRepository: HardwoodsRepository,
    val specialtyRepository: SpecialtyRepository,
    val sheetRipProgressStore: SheetRipProgressStore,
    val tabletSpecialtyItemsStore: TabletSpecialtyItemsStore,
    val scanCoordinator: ScanCoordinator,
    val hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    val assemblyScanCoordinator: AssemblyScanCoordinator,
    val specialtyScanCoordinator: SpecialtyScanCoordinator,
    val appStateStore: AppStateStore,
    val assemblyStateStore: AssemblyStateStore,
    val specialtyStateStore: SpecialtyStateStore,
) {
    /** Cancels coordinator work; safe to call repeatedly when the host leaves composition. */
    fun close() {
        scanCoordinator.close()
        hardwoodsScanCoordinator.close()
        assemblyScanCoordinator.close()
        specialtyScanCoordinator.close()
    }

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
          * be passed explicitly rather than re-derived by listing [cacheJobParentDir]'s children.
          * Directory listing order is not guaranteed, and unrelated cache children must never be
          * mistaken for the restored job folder and silently point every store at empty content.
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
            val progressStore = ProgressStore(
                baseDir = cacheJobParentDir,
                tabletId = tabletId,
                localStateDir = localStateDir,
                readOnly = true,
                archiveFingerprintCompatibility = true,
            )
            val hardwoodsProgressStore = HardwoodsProgressStore(
                baseDir = cacheJobParentDir,
                tabletId = tabletId,
                readOnly = true,
            )
            val specialtyProgressStore = SpecialtyProgressStore(
                baseDir = cacheJobParentDir,
                tabletId = tabletId,
                readOnly = true,
            )
            val pdfMarkupStore = PdfMarkupStore(
                baseDir = cacheJobParentDir,
                tabletId = tabletId,
                readOnly = true,
            )
            val sheetRipProgressStore = SheetRipProgressStore(
                baseDir = cacheJobParentDir,
                readOnly = true,
            )
            val tabletSpecialtyItemsStore = TabletSpecialtyItemsStore(
                baseDir = cacheJobParentDir,
                tabletId = tabletId,
                readOnly = true,
            )
            val unifiedEngine = UnifiedMetadataEngineRegistry.getOrCreate(
                baseDir = cacheJobParentDir,
                isDebugBuild = isDebugBuild,
            )
            val jobRepository = JobRepository(
                baseDir = cacheJobParentDir,
                isDebugBuild = isDebugBuild,
                unifiedEngine = unifiedEngine,
            )
            val hardwoodsRepository = HardwoodsRepository(cacheJobParentDir)
            val specialtyRepository = SpecialtyRepository(
                baseDir = cacheJobParentDir,
                progressStore = specialtyProgressStore,
                unifiedEngine = unifiedEngine,
            )
            val scanCoordinator = ScanCoordinator(cacheJobParentDir, jobRepository)
            val hardwoodsScanCoordinator = HardwoodsScanCoordinator(hardwoodsRepository)
            val assemblyScanCoordinator = AssemblyScanCoordinator(cacheJobParentDir, jobRepository)
            val specialtyScanCoordinator = SpecialtyScanCoordinator(specialtyRepository)
            val appStateStore = AppStateStore(scanCoordinator, progressStore)
            val assemblyStateStore = AssemblyStateStore(
                assemblyScanCoordinator = assemblyScanCoordinator,
                scanCoordinator = scanCoordinator,
                hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                progressStore = progressStore,
                hardwoodsProgressStore = hardwoodsProgressStore,
                liveEngine = unifiedEngine,
            )
            val specialtyStateStore = SpecialtyStateStore(
                specialtyScanCoordinator = specialtyScanCoordinator,
                specialtyProgressStore = specialtyProgressStore,
                hardwoodsProgressStore = hardwoodsProgressStore,
                sheetRipProgressStore = sheetRipProgressStore,
                tabletItemsStore = tabletSpecialtyItemsStore,
                baseDir = cacheJobParentDir,
            )

            return ArchiveSession(
                archiveJobId = archiveJobId,
                contentVersion = contentVersion,
                baseDir = cacheJobParentDir,
                folderName = folderName,
                readOnly = true,
                tabletId = tabletId,
                progressStore = progressStore,
                hardwoodsProgressStore = hardwoodsProgressStore,
                specialtyProgressStore = specialtyProgressStore,
                pdfMarkupStore = pdfMarkupStore,
                unifiedEngine = unifiedEngine,
                jobRepository = jobRepository,
                hardwoodsRepository = hardwoodsRepository,
                specialtyRepository = specialtyRepository,
                sheetRipProgressStore = sheetRipProgressStore,
                tabletSpecialtyItemsStore = tabletSpecialtyItemsStore,
                scanCoordinator = scanCoordinator,
                hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                assemblyScanCoordinator = assemblyScanCoordinator,
                specialtyScanCoordinator = specialtyScanCoordinator,
                appStateStore = appStateStore,
                assemblyStateStore = assemblyStateStore,
                specialtyStateStore = specialtyStateStore,
            )
        }
    }
}
