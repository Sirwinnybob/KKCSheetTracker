package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblySheetPart
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.AssemblyScanState
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.HardwoodTrackerActions
import com.kkc.sheettracker.data.models.SheetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ASSEMBLY_TAG = "KKC_ASSEMBLY"

class AssemblyStateStore(
    private val assemblyScanCoordinator: AssemblyScanCoordinator,
    private val scanCoordinator: ScanCoordinator,
    private val hardwoodsRepository: HardwoodsRepository,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore,
    private val jobRepository: JobRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _scanState = MutableStateFlow(AssemblyScanState())
    val scanState: StateFlow<AssemblyScanState> = _scanState.asStateFlow()

    private val _jobCards = MutableStateFlow<List<AssemblyJobCard>>(emptyList())
    val jobCards: StateFlow<List<AssemblyJobCard>> = _jobCards.asStateFlow()

    init {
        startDerivation()
    }

    fun refresh() {
        assemblyScanCoordinator.refresh(com.kkc.sheettracker.data.models.RefreshReason.USER_REFRESH, force = true)
    }

    @OptIn(FlowPreview::class)
    private fun startDerivation() {
        scope.launch {
            combine(
                assemblyScanCoordinator.state,
                scanCoordinator.state,
                progressStore.progressVersion.debounce(120L),
                hardwoodsProgressStore.progressVersion.debounce(120L)
            ) { assemblyState, _, _, _ -> assemblyState }
                .collectLatest { assemblyState ->
                    _scanState.value = assemblyState
                    val cards = withContext(Dispatchers.Default) {
                        deriveJobCards(assemblyState.snapshot.jobs)
                    }
                    if (_jobCards.value != cards) {
                        _jobCards.value = cards
                    }
                    Log.d(ASSEMBLY_TAG, "derive_done jobs=${assemblyState.snapshot.jobs.size}")
                }
        }
    }

    private fun deriveJobCards(jobs: List<AssemblyJob>): List<AssemblyJobCard> {
        val cncJobsByFolder = scanCoordinator.currentSnapshotJobs()
            .associateBy { it.folderName }

        return jobs.mapNotNull { job ->
            val cncSummary = deriveCncSummary(job, cncJobsByFolder[job.folderName])
            val hardwoodsSummary = deriveHardwoodsSummary(job)
            if (cncSummary == null && hardwoodsSummary == null) return@mapNotNull null
            AssemblyJobCard(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                cncSummary = cncSummary ?: AssemblyCncSummary(),
                hardwoodsSummary = hardwoodsSummary ?: AssemblyHardwoodsSummary(),
                hasBothModes = cncSummary != null && hardwoodsSummary != null
            )
        }
    }

    private fun deriveCncSummary(
        assemblyJob: AssemblyJob,
        cncJob: com.kkc.sheettracker.data.models.Job?
    ): AssemblyCncSummary? {
        val base = assemblyJob.cncSummary ?: return null
        if (cncJob == null) return base

        var completed = 0
        var bad = 0
        var skipped = 0
        for (material in cncJob.materials) {
            val trackable = trackablePages(material)
            for (page in trackable) {
                val status = progressStore.getSheetStatus(
                    jobFolderName = assemblyJob.folderName,
                    pdfFilename = material.pdfFilename,
                    page = page,
                    fileFingerprint = material.fileFingerprint
                )
                when (status) {
                    SheetStatus.COMPLETE -> completed++
                    SheetStatus.HAS_BAD_PARTS -> { completed++; bad++ }
                    SheetStatus.SKIPPED -> skipped++
                    else -> {}
                }
            }
        }
        return base.copy(completedSheets = completed, badPartsSheets = bad, skippedSheets = skipped)
    }

    private fun deriveHardwoodsSummary(assemblyJob: AssemblyJob): AssemblyHardwoodsSummary? {
        val base = assemblyJob.hardwoodsSummary ?: return null
        val index = hardwoodsRepository.loadHardwoodsIndex(assemblyJob.folderName) ?: return base
        val hwJob = com.kkc.sheettracker.data.models.HardwoodJob(
            folderName = assemblyJob.folderName,
            jobNumber = assemblyJob.jobNumber,
            jobName = assemblyJob.jobName,
            index = index
        )
        val summary = hardwoodsProgressStore.summarizeJob(hwJob)
        return AssemblyHardwoodsSummary(
            totalPieces = summary.counts.totalPieces,
            donePieces = summary.counts.donePieces,
            badPieces = summary.counts.badPieces,
            skippedPieces = summary.counts.skippedPieces
        )
    }

    fun deriveCabinetParts(jobFolderName: String, cabinetNumber: String): AssemblyCabinetParts {
        val cncJob = scanCoordinator.currentSnapshotJobs().firstOrNull { it.folderName == jobFolderName }
        val cncParts = if (cncJob != null) deriveCncPartsForCabinet(cncJob, cabinetNumber) else emptyList()

        val index = hardwoodsRepository.loadHardwoodsIndex(jobFolderName)
        val hardwoodRows = if (index != null) deriveHardwoodRowsForCabinet(jobFolderName, index, cabinetNumber) else emptyList()

        val bom = deriveBomForCabinet(jobFolderName, cabinetNumber, cncParts, hardwoodRows)

        return AssemblyCabinetParts(
            cabinetNumber = cabinetNumber,
            bom = bom,
            cncParts = cncParts,
            hardwoodRows = hardwoodRows
        )
    }

    private fun deriveBomForCabinet(
        jobFolderName: String,
        cabinetNumber: String,
        cncParts: List<AssemblyCncPart>,
        hardwoodRows: List<AssemblyHardwoodRow>
    ): List<AssemblyBomEntry> {
        val assemblyIndex = jobRepository.getCabinetSheetIndex(jobFolderName)
            ?.documents?.assembly ?: return emptyList()
        val pages = assemblyIndex.cabinetToPages[cabinetNumber] ?: return emptyList()
        val sheetParts: List<AssemblySheetPart> = pages
            .flatMap { page -> assemblyIndex.pageDetails[page.toString()]?.parts.orEmpty() }
        if (sheetParts.isEmpty()) return emptyList()
        val cncByDesc = cncParts.associateBy { it.partName.trim().lowercase() }
        val hwByDesc = hardwoodRows.associateBy { it.description.trim().lowercase() }
        return sheetParts.map { p ->
            val key = p.description.trim().lowercase()
            AssemblyBomEntry(part = p, cncPart = cncByDesc[key], hardwoodRow = hwByDesc[key])
        }
    }

    private fun deriveCncPartsForCabinet(
        cncJob: com.kkc.sheettracker.data.models.Job,
        cabinetNumber: String
    ): List<AssemblyCncPart> {
        val cabNum = cabinetNumber.toIntOrNull() ?: return emptyList()
        val result = mutableListOf<AssemblyCncPart>()
        for (material in cncJob.materials) {
            val pages = material.metadata?.pages ?: continue
            for (page in pages) {
                if (page.hiddenInApp || page.trackingExcluded || page.isPartListContinuation) continue
                for (part in page.parts) {
                    if (part.cabNumber != cabNum) continue
                    val sheetStatus = progressStore.getSheetStatus(
                        jobFolderName = cncJob.folderName,
                        pdfFilename = material.pdfFilename,
                        page = page.pageNumber,
                        fileFingerprint = material.fileFingerprint
                    )
                    val isBadPart = progressStore.getBadParts(
                        jobFolderName = cncJob.folderName,
                        pdfFilename = material.pdfFilename,
                        page = page.pageNumber,
                        fileFingerprint = material.fileFingerprint,
                        includeDraft = false
                    ).contains(part.number)
                    result += AssemblyCncPart(
                        materialName = material.materialName,
                        pdfFilename = material.pdfFilename,
                        pageNumber = page.pageNumber,
                        partNumber = part.number,
                        partName = part.name,
                        width = part.width,
                        length = part.length,
                        room = part.room,
                        sheetStatus = sheetStatus,
                        isBadPart = isBadPart
                    )
                }
            }
        }
        return result.sortedWith(compareBy({ it.materialName }, { it.pageNumber }, { it.partNumber }))
    }

    private fun deriveHardwoodRowsForCabinet(
        jobFolderName: String,
        index: com.kkc.sheettracker.data.models.HardwoodCutlistIndex,
        cabinetNumber: String
    ): List<AssemblyHardwoodRow> {
        val result = mutableListOf<AssemblyHardwoodRow>()
        for (doc in index.documents) {
            for (row in doc.rows) {
                if (!row.cabinets.contains(cabinetNumber)) continue
                val progress = hardwoodsProgressStore.getRowProgress(
                    jobFolderName = jobFolderName,
                    docType = doc.docType.name,
                    rowId = row.rowId
                )
                result += AssemblyHardwoodRow(
                    docType = doc.docType,
                    description = row.description,
                    material = row.material,
                    qty = row.qty,
                    width = row.width,
                    length = row.length,
                    doneCount = progress.doneCount,
                    badCount = progress.badCount,
                    skipped = progress.skipped
                )
            }
        }
        return result
    }

    private fun trackablePages(material: com.kkc.sheettracker.data.models.Material): List<Int> {
        val metadataPages = material.metadata?.pages.orEmpty()
        val visible = metadataPages
            .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
            .mapNotNull { it.pageNumber.takeIf { p -> p in 1..material.pageCount } }
            .distinct()
            .sorted()
        return if (visible.isNotEmpty()) visible else (1..material.pageCount).toList()
    }
}
