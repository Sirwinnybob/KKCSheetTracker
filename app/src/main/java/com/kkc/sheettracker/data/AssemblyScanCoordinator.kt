package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.AssemblyScanSnapshot
import com.kkc.sheettracker.data.models.AssemblyScanState
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class AssemblyScanCoordinator(
    private val baseDir: File,
    private val jobRepository: JobRepository,
    private val hardwoodsRepository: HardwoodsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)
    private val gson = Gson()

    private val _state = MutableStateFlow(AssemblyScanState(status = ScanStatus.IDLE))
    val state: StateFlow<AssemblyScanState> = _state.asStateFlow()

    fun refresh(reason: RefreshReason, force: Boolean = false) {
        scope.launch {
            val previous = _state.value
            _state.value = previous.copy(status = ScanStatus.LOADING, errorMessage = null)
            try {
                val started = System.currentTimeMillis()
                val jobs = scanJobs()
                _state.value = AssemblyScanState(
                    status = ScanStatus.READY,
                    snapshot = AssemblyScanSnapshot(
                        generation = generation.incrementAndGet(),
                        basePath = baseDir.absolutePath,
                        jobs = jobs,
                        startedAt = started,
                        completedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                _state.value = previous.copy(
                    status = ScanStatus.ERROR,
                    errorMessage = e.message ?: "Assembly scan failed"
                )
            }
        }
    }

    private fun scanJobs(): List<AssemblyJob> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val jobNameRegex = Regex("""^(\d+)\s*-\s*(.+)$""")
        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { jobDir ->
                val match = jobNameRegex.find(jobDir.name) ?: return@mapNotNull null
                val jobNumber = match.groupValues[1]
                val jobName = match.groupValues[2].trim()
                val cncSummary = buildCncSummary(jobDir, jobNumber)
                val hardwoodsSummary = buildHardwoodsSummary(jobDir.name)
                val cabinetSheetIndex = jobRepository.getCabinetSheetIndex(jobDir.name)
                if (cncSummary == null && hardwoodsSummary == null && cabinetSheetIndex == null) {
                    return@mapNotNull null
                }
                AssemblyJob(
                    folderName = jobDir.name,
                    jobNumber = jobNumber,
                    jobName = jobName,
                    cabinetSheetIndex = cabinetSheetIndex,
                    cncSummary = cncSummary,
                    hardwoodsSummary = hardwoodsSummary
                )
            }
            ?.sortedByDescending { it.jobNumber.toIntOrNull() ?: 0 }
            ?: emptyList()
    }

    private fun buildCncSummary(jobDir: File, jobNumber: String): AssemblyCncSummary? {
        val cncDir = File(jobDir, "CNC")
        if (!cncDir.isDirectory) return null
        val pdfs = cncDir.listFiles()
            ?.filter {
                it.extension.equals("pdf", ignoreCase = true) &&
                    "ALL SHEETS" !in it.name &&
                    it.name.startsWith("$jobNumber - ")
            }
            ?: return null
        if (pdfs.isEmpty()) return null

        val metadataDir = File(cncDir, ".metadata")
        var totalSheets = 0
        for (pdf in pdfs) {
            val metaFile = File(metadataDir, "${pdf.nameWithoutExtension}.json")
            val trackable = if (metaFile.exists()) {
                try {
                    val meta = gson.fromJson(metaFile.readText(), MaterialMetadata::class.java)
                    val visible = meta.pages.filterNot {
                        it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation
                    }
                    visible.size.takeIf { it > 0 } ?: meta.pages.size.takeIf { it > 0 } ?: countPdfPages(pdf)
                } catch (_: Exception) {
                    countPdfPages(pdf)
                }
            } else {
                countPdfPages(pdf)
            }
            totalSheets += trackable
        }
        return AssemblyCncSummary(totalSheets = totalSheets)
    }

    private fun buildHardwoodsSummary(jobFolderName: String): AssemblyHardwoodsSummary? {
        val index = hardwoodsRepository.loadHardwoodsIndex(jobFolderName) ?: return null
        if (index.documents.isEmpty()) return null
        val total = index.documents.sumOf { doc -> doc.rows.sumOf { row -> row.qty } }
        return AssemblyHardwoodsSummary(totalPieces = total)
    }

    private fun countPdfPages(file: File): Int {
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (_: Exception) {
            0
        }
    }
}
