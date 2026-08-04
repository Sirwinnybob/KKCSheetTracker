package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.HardwoodRowRevisionState
import com.kkc.sheettracker.data.models.HardwoodSearchEntry
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

data class HardwoodsCacheScanResult(
    val jobs: List<HardwoodJob>,
    val searchIndex: List<HardwoodSearchEntry>,
    val needsDeepLoad: List<String>
)

class HardwoodsRepository(private var baseDir: File) {
    private var unifiedEngine: UnifiedMetadataEngine? = null

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

    fun scanJobs(): List<HardwoodJob> {
        return scanJobsFromCacheOnly().jobs
    }

    /**
     * Fast path used by HardwoodsScanCoordinator: builds list identities and progress inputs
     * solely from cache_index.json. The cutlist index remains a detail/search-only read.
     */
    fun scanJobsFromCacheOnly(): HardwoodsCacheScanResult {
        val (jobInfos, missingIndexes) = engine().listJobsFromCacheIndex()
        val jobs = jobInfos.map { info ->
            HardwoodJob(
                folderName = info.folderName,
                jobNumber = info.jobNumber,
                jobName = info.jobName,
                hiddenFromProduction = info.hiddenFromProduction,
                lineupPosition = info.lineupPosition,
                labels = info.labels,
                isPending = info.isPending,
                boardSection = info.boardSection
            )
        }
        return HardwoodsCacheScanResult(
            jobs = jobs,
            searchIndex = emptyList(),
            needsDeepLoad = missingIndexes
        )
    }

    /**
     * Search-screen-only projection. The Jobs list must never call this: it expands the already
     * gate-filtered cache-index job set into full hardwood snapshots so row-level search data is
     * available after the operator explicitly opens Search.
     */
    fun buildSearchIndexForSearchScreen(): List<HardwoodSearchEntry> {
        val (jobInfos, _) = engine().listJobsFromCacheIndex()
        val jobs = jobInfos.mapNotNull { info ->
            engine().getHardwoodsSnapshot(info.folderName)?.job
        }
        return buildSearchIndex(jobs)
    }

    /** Re-projects one job from the engine's in-memory cache. Used by HardwoodsScanCoordinator. */
    fun getUpdatedJob(folderName: String): HardwoodJob? {
        val info = engine().getMergedJobInfo(folderName) ?: return null
        return engine().getHardwoodsSnapshot(folderName)?.job
            ?.copy(
                lineupPosition = info.lineupPosition,
                labels = info.labels,
                hiddenFromProduction = info.hiddenFromProduction,
                isPending = info.isPending,
                boardSection = info.boardSection
            )
    }

    fun buildSearchIndex(jobs: List<HardwoodJob>): List<HardwoodSearchEntry> {
        val out = mutableListOf<HardwoodSearchEntry>()
        for (job in jobs) {
            val index = job.index ?: continue
            for (doc in index.documents) {
                for (row in doc.rows) {
                    out += HardwoodSearchEntry(
                        jobFolderName = job.folderName,
                        jobNumber = job.jobNumber,
                        jobName = job.jobName,
                        docType = doc.docType,
                        pdfFilename = doc.pdfFilename,
                        rowId = row.rowId,
                        description = row.description,
                        width = row.width,
                        length = row.length,
                        cabinetNumbers = row.cabinets,
                        rawCabinetText = row.rawCabinetText
                    )
                }
            }
        }
        return out
    }

    fun loadHardwoodsIndex(jobFolderName: String): HardwoodCutlistIndex? {
        return engine().getHardwoodsSnapshot(jobFolderName)?.job?.index
    }

    fun loadHardwoodsRevisionHistory(jobFolderName: String): HardwoodRevisionHistory? {
        return engine().getHardwoodsRevisionHistory(jobFolderName).history
    }

    fun findRowRevisionState(
        jobFolderName: String,
        docType: String,
        rowId: String
    ): HardwoodRowRevisionState? {
        val history = loadHardwoodsRevisionHistory(jobFolderName) ?: return null
        return history.currentRowStates.firstOrNull {
            it.docType == docType && it.rowId == rowId
        }
    }

    fun getRowRevisionStates(jobFolderName: String): Map<Pair<String, String>, HardwoodRowRevisionState> {
        val history = loadHardwoodsRevisionHistory(jobFolderName) ?: return emptyMap()
        return history.currentRowStates.associateBy { it.docType to it.rowId }
    }

    fun getHardwoodsPdfFile(jobFolderName: String, pdfFilename: String, preferDarkMode: Boolean): File? {
        val jobDir = File(baseDir, jobFolderName)
        val light = File(jobDir, pdfFilename)
        val dark = File(jobDir, "DARK MODE/$pdfFilename")
        return when {
            preferDarkMode && dark.exists() -> dark
            light.exists() -> light
            dark.exists() -> dark
            else -> null
        }
    }

    fun findHardwoodsPdfFilename(jobFolderName: String, docType: HardwoodDocType): String? {
        return engine()
            .getHardwoodsSnapshot(jobFolderName)
            ?.job
            ?.index
            ?.documents
            ?.firstOrNull { it.docType == docType }
            ?.pdfFilename
            ?.takeIf { it.isNotBlank() }
    }

    fun countPdfPages(pdfFile: File?): Int {
        if (pdfFile == null || !pdfFile.exists()) return 0
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (_: Exception) {
            0
        }
    }

    fun loadBoardStock(jobFolderName: String): List<BoardStockRow> {
        return engine().getBoardStockRows(jobFolderName, includeProgressOverlay = false).rows
    }
}
