package com.kkc.sheettracker.ui.jobs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.SpecialtyScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.hardwoods.applySkippedPartRowsToBoardStockRows
import com.kkc.sheettracker.ui.hardwoods.buildBoardStockRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Composable
fun rememberCncJobsSpec(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    progressStore: ProgressStore,
    jobRepository: JobRepository,
    hardwoodsRepository: HardwoodsRepository,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobsSpec {
    val scanState by scanCoordinator.state.collectAsState()
    val appJobModelsByFolder = appStateStore.jobUiModels.collectAsState().value.associateBy { it.folderName }
    val filteredJobs = remember(scanState.snapshot.jobs) { scanState.snapshot.jobs }
    
    return remember(scanState, appJobModelsByFolder) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_cnc"
            override val scanStatus = scanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = scanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = progressStore.progressVersion
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return filteredJobs.map { job ->
                    val appModel = appJobModelsByFolder[job.folderName]
                    val counts = appModel?.counts ?: StatusCounts()
                    val fraction = appModel?.completionFraction ?: if (counts.total <= 0) 0f else counts.complete.toFloat() / counts.total.toFloat()
                    val materialSegments = appModel?.materials?.map { material ->
                        val jobMaterial = job.materials.find { it.pdfFilename == material.pdfFilename }
                        MaterialSegmentData(
                            materialName = material.materialName,
                            counts = material.counts,
                            isRemake = jobMaterial?.metadata?.remakeLabel != null
                        )
                    } ?: job.materials.map { material ->
                        MaterialSegmentData(
                            materialName = material.materialName,
                            counts = StatusCounts(),
                            isRemake = material.metadata?.remakeLabel != null
                        )
                    }
                    JobBrowserItemUiState(
                        job = job,
                        counts = counts,
                        completionFraction = fraction,
                        materialSegments = materialSegments,
                        hasDeliverySheet = null,
                        hasThreeDAssets = null,
                        revisionCount = null
                    ).toUnifiedModel(
                        isPinned = false,
                        onCardClick = { onJobClick(job.folderName) },
                        onView3DClick = { onView3D(job.folderName) },
                        onViewCoverSheetClick = { onViewCoverSheet(job.folderName) },
                        onHistoryClick = onHistoryClick
                    )
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                scanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val hasDelivery = jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
                val has3D = jobRepository.hasThreeDAssets(folderName)
                val badges = mutableSetOf<JobBadge>()
                if (hasDelivery) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (has3D) badges.add(JobBadge.HAS_3D_ASSETS)
                return badges
            }
        }
    }
}

@Composable
fun rememberHardwoodsJobsSpec(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsRepository: HardwoodsRepository,
    progressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobsSpec {
    val scanState by scanCoordinator.state.collectAsState()
    val filteredJobs = remember(scanState.snapshot.jobs) { scanState.snapshot.jobs }
    
    return remember(scanState) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_hardwoods"
            override val scanStatus = scanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = scanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = progressStore.progressVersion
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return filteredJobs.map { job ->
                    val summary = progressStore.summarizeJob(job)
                    val availableDocTypes = job.index?.documents
                        .orEmpty()
                        .filter { doc -> doc.pdfFilename.isNotBlank() }
                        .map { it.docType }
                        .toSet()
                    val includedDocSummaries = summary.documents.filter {
                        it.docType != HardwoodDocType.DOOR_LIST && it.docType in availableDocTypes
                    }
                    val includedCounts = includedDocSummaries.fold(HardwoodStatusCounts()) { acc, doc ->
                        HardwoodStatusCounts(
                            totalPieces = acc.totalPieces + doc.counts.totalPieces,
                            donePieces = acc.donePieces + doc.counts.donePieces,
                            badPieces = acc.badPieces + doc.counts.badPieces,
                            skippedPieces = acc.skippedPieces + doc.counts.skippedPieces
                        )
                    }
                    
                    val rowProgressMap = progressStore.getRowProgressMap(job.folderName)
                    val rows = applySkippedPartRowsToBoardStockRows(
                        rows = buildBoardStockRows(scanState.snapshot.basePath, job.folderName, job.index),
                        index = job.index,
                        rowProgressMap = rowProgressMap
                    )
                    val boardStockTotal = rows.sumOf { row ->
                        val bad = rowProgressMap[Pair(row.stableKey, "board_stock")]?.badCount ?: 0
                        if (bad > 0) row.neededRips + bad else row.neededRips
                    }
                    val boardStockDone = rows.sumOf { row -> rowProgressMap[Pair(row.stableKey, "board_stock")]?.doneCount ?: 0 }
                    val boardStockBad = rows.sumOf { row -> rowProgressMap[Pair(row.stableKey, "board_stock")]?.badCount ?: 0 }
                    val boardStockSkipped = rows.sumOf { row -> if (rowProgressMap[Pair(row.stableKey, "board_stock")]?.skipped == true) row.neededRips else 0 }
                    
                    val docSegments = mutableListOf<MaterialSegmentData>()
                    if (includedDocSummaries.isNotEmpty()) {
                        docSegments.add(MaterialSegmentData("Parts", StatusCounts(includedCounts.totalPieces, includedCounts.donePieces, includedCounts.badPieces, includedCounts.skippedPieces)))
                    }
                    if (rows.isNotEmpty()) {
                        docSegments.add(MaterialSegmentData("Board Stock", StatusCounts(boardStockTotal, boardStockDone, boardStockBad, boardStockSkipped)))
                    }
                    
                    val finalCounts = HardwoodStatusCounts(
                        totalPieces = includedCounts.totalPieces + boardStockTotal,
                        donePieces = includedCounts.donePieces + boardStockDone,
                        badPieces = includedCounts.badPieces + boardStockBad,
                        skippedPieces = includedCounts.skippedPieces + boardStockSkipped
                    )
                    
                    HardwoodsJobItemUiState(
                        job = job,
                        counts = finalCounts,
                        docCount = availableDocTypes.size,
                        docSegments = docSegments,
                        availableDocTypes = availableDocTypes
                    ).toUnifiedModel(
                        isPinned = false,
                        onCardClick = { onJobClick(job.folderName) },
                        onView3DClick = { onView3D(job.folderName) },
                        onViewCoverSheetClick = { onViewCoverSheet(job.folderName) },
                        onHistoryClick = onHistoryClick
                    )
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                scanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val hasDelivery = jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
                val has3D = jobRepository.hasThreeDAssets(folderName)
                val history = hardwoodsRepository.loadHardwoodsRevisionHistory(folderName)
                val badges = mutableSetOf<JobBadge>()
                if (hasDelivery) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (has3D) badges.add(JobBadge.HAS_3D_ASSETS)
                if (history != null && history.revisions.isNotEmpty()) {
                    badges.add(JobBadge.HAS_HISTORY)
                }
                return badges
            }
        }
    }
}

@Composable
fun rememberAssemblyJobsSpec(
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    jobRepository: JobRepository,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit
): UnifiedJobsSpec {
    val scanState by assemblyScanCoordinator.state.collectAsState()
    
    return remember(scanState) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_assembly"
            override val scanStatus = assemblyScanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = assemblyScanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion: StateFlow<Long> = MutableStateFlow(0L)
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return assemblyStateStore.deriveJobCards().map { card ->
                    card.toUnifiedModel(
                        isPinned = false,
                        onCardClick = { onJobClick(card.folderName) },
                        onView3DClick = { onView3D(card.folderName) },
                        onViewCoverSheetClick = { onViewCoverSheet(card.folderName) }
                    )
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                assemblyScanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val hasDelivery = jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
                val has3D = jobRepository.hasThreeDAssets(folderName)
                val badges = mutableSetOf<JobBadge>()
                if (hasDelivery) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (has3D) badges.add(JobBadge.HAS_3D_ASSETS)
                return badges
            }
        }
    }
}

@Composable
fun rememberSpecialtyJobsSpec(
    specialtyScanCoordinator: SpecialtyScanCoordinator,
    specialtyStateStore: SpecialtyStateStore,
    jobRepository: JobRepository,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit
): UnifiedJobsSpec {
    val scanState by specialtyScanCoordinator.state.collectAsState()
    
    return remember(scanState) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_specialty"
            override val scanStatus = specialtyScanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = specialtyScanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion: StateFlow<Long> = MutableStateFlow(0L)
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return specialtyStateStore.deriveJobCards().map { card ->
                    card.toUnifiedModel(
                        isPinned = false,
                        onCardClick = { onJobClick(card.folderName) }
                    )
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                specialtyScanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val hasDelivery = jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
                val has3D = jobRepository.hasThreeDAssets(folderName)
                val badges = mutableSetOf<JobBadge>()
                if (hasDelivery) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (has3D) badges.add(JobBadge.HAS_3D_ASSETS)
                return badges
            }
        }
    }
}
