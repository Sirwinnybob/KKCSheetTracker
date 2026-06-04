package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.unified.UnifiedPdfPageCountResult
import com.kkc.sheettracker.data.unified.UnifiedReferenceQuery
import java.io.File

class JobRepository(
    private var baseDir: File,
    private val isDebugBuild: Boolean = false,
    private var unifiedEngine: UnifiedMetadataEngine? = null
) {
    private val cacheLock = Any()
    @Volatile private var cachedJobs: List<Job>? = null
    @Volatile private var cachedSearchIndex: List<PartSearchEntry>? = null
    @Volatile private var scanCoordinator: ScanCoordinator? = null

    private fun engine(): UnifiedMetadataEngine {
        val existing = unifiedEngine
        if (existing != null) return existing
        return UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = isDebugBuild,
            pdfPageCounter = ::countPdfPagesForEngine
        ).also { unifiedEngine = it }
    }

    internal fun attachScanCoordinator(coordinator: ScanCoordinator) {
        scanCoordinator = coordinator
    }

    fun updateBaseDir(newBaseDir: File) {
        baseDir = newBaseDir
        unifiedEngine = null
        invalidateCache()
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedJobs = null
            cachedSearchIndex = null
        }
        unifiedEngine?.invalidateAll()
    }

    fun getBoardGridColumns(): Int = engine().getBoardGridColumns()

    fun scanJobs(forceRefresh: Boolean = false): List<Job> {
        scanCoordinator?.let { coordinator ->
            if (forceRefresh) {
                coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
            }
            return coordinator.currentSnapshotJobs()
        }

        if (!forceRefresh) {
            cachedJobs?.let { return it }
        } else {
            invalidateCache()
        }

        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()

        val scanned = engine().listJobs()
            .mapNotNull { info ->
                engine().getCncSnapshot(info.folderName)?.job
            }
            .sortedWith { a, b ->
                val numberCmp = compareJobNumbersDesc(a.jobNumber, b.jobNumber)
                if (numberCmp != 0) numberCmp else a.folderName.compareTo(b.folderName, ignoreCase = true)
            }

        synchronized(cacheLock) {
            cachedJobs = scanned
        }
        return scanned
    }

    fun buildSearchIndex(forceRefresh: Boolean = false): List<PartSearchEntry> {
        scanCoordinator?.let { coordinator ->
            if (forceRefresh) {
                coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
            }
            return coordinator.currentSearchIndex()
        }

        if (!forceRefresh) {
            cachedSearchIndex?.let { return it }
        }

        val jobs = scanJobs(forceRefresh = forceRefresh)
        val index = jobs.flatMap { job ->
            engine().getCncSnapshot(job.folderName)?.searchIndex.orEmpty()
        }
        synchronized(cacheLock) {
            cachedSearchIndex = index
        }
        return index
    }

    private fun countPdfPagesForEngine(pdfFile: File): UnifiedPdfPageCountResult {
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            UnifiedPdfPageCountResult(pageCount = count)
        } catch (e: Exception) {
            UnifiedPdfPageCountResult(pageCount = 0, errorDetail = e.message)
        }
    }

    fun getPdfFile(jobFolderName: String, pdfFilename: String): File {
        return File(baseDir, "$jobFolderName/CNC/$pdfFilename")
    }

    fun getJobRootPdfFile(
        jobFolderName: String,
        pdfFilename: String,
        preferDarkMode: Boolean
    ): File? {
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

    fun getCabinetSheetIndex(jobFolderName: String): CabinetSheetIndex? {
        return engine().getCabinetSheetIndex(jobFolderName).index
    }

    fun hasReferenceDocument(jobFolderName: String, docType: ReferenceDocType): Boolean {
        return engine().hasReferenceDocument(jobFolderName, UnifiedReferenceQuery(docType)).exists
    }

    fun hasThreeDAssets(jobFolderName: String): Boolean {
        return engine().hasThreeDAssets(jobFolderName).exists
    }

    fun findReferencePdfFilename(jobFolderName: String, docType: ReferenceDocType): String? {
        return engine().findReferencePdfFilename(jobFolderName, UnifiedReferenceQuery(docType)).pdfFilename
    }

    fun getJobPdfCatalog(jobFolderName: String): JobPdfCatalog {
        return engine().getPdfCatalog(jobFolderName).catalog
    }
}
