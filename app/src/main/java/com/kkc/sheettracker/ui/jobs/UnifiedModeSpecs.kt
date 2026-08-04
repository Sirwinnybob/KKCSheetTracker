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
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import kotlinx.coroutines.flow.stateIn

@Composable
fun rememberCncJobsSpec(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    progressStore: ProgressStore,
    jobRepository: JobRepository,
    hardwoodsRepository: HardwoodsRepository,
    engine: UnifiedMetadataEngine,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobsSpec {
    val scanState by scanCoordinator.state.collectAsState()
    val jobInfos = engine.getCachedJobInfos()
    
    return remember(scanState, jobInfos) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_cnc"
            override val scanStatus = scanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = scanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = progressStore.progressVersion
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return jobInfos.map { info ->
                    val indexProgress = info.indexProgress
                    if (indexProgress?.cnc != null) {
                        val cncProgress = indexProgress.cnc
                        val liveCounts = progressStore.getIndexJobStatusCountsOrNull(
                            jobFolderName = info.folderName,
                            canonicalTotal = cncProgress.totalSheets
                        )
                        val useLive = liveCounts != null && liveCounts.total > 0
                        val counts = if (useLive) liveCounts else StatusCounts(
                            total = cncProgress.totalSheets - cncProgress.renested,
                            complete = cncProgress.done,
                            bad = cncProgress.bad,
                            skipped = cncProgress.skipped,
                            reNested = cncProgress.renested
                        )
                        val fraction = if (counts.total <= 0) 0f else counts.complete.toFloat() / counts.total.toFloat()
                        val materialSegments = cncProgress.materials.map { material ->
                            MaterialSegmentData(
                                materialName = material.materialName,
                                counts = material.toStatusCounts(),
                                isRemake = material.isRemake
                            )
                        }
                        makeCncJobCard(
                            info = info,
                            counts = counts,
                            fraction = fraction,
                            materialSegments = materialSegments,
                            isPinned = false,
                            onCardClick = { onJobClick(info.folderName) },
                            onView3DClick = { onView3D(info.folderName) },
                            onViewCoverSheetClick = { onViewCoverSheet(info.folderName) },
                            onHistoryClick = onHistoryClick
                        )
                    } else {
                        // A malformed/old index may omit CNC progress. Keep the job visible, but
                        // never substitute an AppState/full-cache model on the Jobs screen.
                        val counts = StatusCounts()
                        val fraction = 0f
                        val materialSegments = emptyList<MaterialSegmentData>()
                        makeCncJobCard(
                            info = info,
                            counts = counts,
                            fraction = fraction,
                            materialSegments = materialSegments,
                            isPinned = false,
                            onCardClick = { onJobClick(info.folderName) },
                            onView3DClick = { onView3D(info.folderName) },
                            onViewCoverSheetClick = { onViewCoverSheet(info.folderName) },
                            onHistoryClick = onHistoryClick
                        )
                    }
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                scanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val progress = jobInfos.firstOrNull { it.folderName == folderName }?.indexProgress
                val badges = mutableSetOf<JobBadge>()
                if (progress?.hasDeliverySheet == true) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (progress?.has3DAssets == true) badges.add(JobBadge.HAS_3D_ASSETS)
                return badges
            }
        }
    }
}

private fun makeCncJobCard(
    info: UnifiedJobInfo,
    counts: StatusCounts,
    fraction: Float,
    materialSegments: List<MaterialSegmentData>,
    isPinned: Boolean,
    onCardClick: () -> Unit,
    onView3DClick: (() -> Unit)?,
    onViewCoverSheetClick: (() -> Unit)?,
    onHistoryClick: ((String) -> Unit)?
): UnifiedJobUiModel {
    val badges = mutableSetOf<JobBadge>()
    if (info.isPending) badges.add(JobBadge.PENDING_DELIVERY)
    if (info.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

    return UnifiedJobUiModel(
        folderName = info.folderName,
        jobNumber = info.jobNumber,
        jobName = info.jobName,
        isPinned = isPinned,
        isPending = info.isPending,
        boardSection = info.boardSection,
        lineupPosition = info.lineupPosition,
        badges = badges,
        labels = info.labels,
        historyCount = null,
        progressStyle = ProgressStyle.Cnc(
            counts = counts,
            fraction = fraction,
            materialSegments = materialSegments
        ),
        onCardClick = onCardClick,
        onView3DClick = onView3DClick,
        onViewCoverSheetClick = onViewCoverSheetClick,
        onHistoryClick = onHistoryClick
    )
}

@Composable
fun rememberHardwoodsJobsSpec(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsRepository: HardwoodsRepository,
    progressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    engine: UnifiedMetadataEngine,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobsSpec {
    val scanState by scanCoordinator.state.collectAsState()
    val jobInfos = engine.getCachedJobInfos()
    
    return remember(scanState, jobInfos) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_hardwoods"
            override val scanStatus = scanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = scanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = progressStore.progressVersion
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return jobInfos.map { info ->
                    val indexProgress = info.indexProgress
                    val hwProgress = indexProgress?.hardwoods

                    if (hwProgress != null) {
                        val counts = HardwoodStatusCounts(
                            totalPieces = hwProgress.totalPieces,
                            donePieces = hwProgress.donePieces,
                            badPieces = hwProgress.badPieces,
                            skippedPieces = hwProgress.skippedPieces
                        )
                        val docSegments = mutableListOf<MaterialSegmentData>()
                        docSegments.addAll(hwProgress.docTypes.map { docType ->
                            MaterialSegmentData(
                                materialName = docType.docType,
                                counts = StatusCounts(
                                    total = docType.total,
                                    complete = docType.done,
                                    bad = docType.bad,
                                    skipped = docType.skipped
                                )
                            )
                        })
                        val fraction = if (counts.totalPieces <= 0) 0f else counts.donePieces.toFloat() / counts.totalPieces.toFloat()
                        val docCount = hwProgress.docTypes.size

                        val badges = mutableSetOf<JobBadge>()
                        if (info.isPending) badges.add(JobBadge.PENDING_DELIVERY)
                        if (info.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

                        UnifiedJobUiModel(
                            folderName = info.folderName,
                            jobNumber = info.jobNumber,
                            jobName = info.jobName,
                            isPinned = false,
                            isPending = info.isPending,
                            boardSection = info.boardSection,
                            lineupPosition = info.lineupPosition,
                            badges = badges,
                            labels = info.labels,
                            historyCount = null,
                            progressStyle = ProgressStyle.Hardwoods(
                                counts = counts,
                                fraction = fraction,
                                docCount = docCount,
                                docSegments = docSegments
                            ),
                            onCardClick = { onJobClick(info.folderName) },
                            onView3DClick = { onView3D(info.folderName) },
                            onViewCoverSheetClick = { onViewCoverSheet(info.folderName) },
                            onHistoryClick = onHistoryClick
                        )
                    } else {
                        val badges = mutableSetOf<JobBadge>()
                        if (info.isPending) badges.add(JobBadge.PENDING_DELIVERY)
                        if (info.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

                        UnifiedJobUiModel(
                            folderName = info.folderName,
                            jobNumber = info.jobNumber,
                            jobName = info.jobName,
                            isPinned = false,
                            isPending = info.isPending,
                            boardSection = info.boardSection,
                            lineupPosition = info.lineupPosition,
                            badges = badges,
                            labels = info.labels,
                            historyCount = null,
                            progressStyle = ProgressStyle.Hardwoods(
                                counts = HardwoodStatusCounts(),
                                fraction = 0f,
                                docCount = 0,
                                docSegments = emptyList()
                            ),
                            onCardClick = { onJobClick(info.folderName) },
                            onView3DClick = { onView3D(info.folderName) },
                            onViewCoverSheetClick = { onViewCoverSheet(info.folderName) },
                            onHistoryClick = onHistoryClick
                        )
                    }
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                scanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val progress = jobInfos.firstOrNull { it.folderName == folderName }?.indexProgress
                val badges = mutableSetOf<JobBadge>()
                if (progress?.hasDeliverySheet == true) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (progress?.has3DAssets == true) badges.add(JobBadge.HAS_3D_ASSETS)
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
    engine: UnifiedMetadataEngine,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: (String) -> Unit,
    onViewCoverSheet: (String) -> Unit
): UnifiedJobsSpec {
    val scanState by assemblyScanCoordinator.state.collectAsState()
    val jobInfos = engine.getCachedJobInfos()
    
    return remember(scanState, jobInfos) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_assembly"
            override val scanStatus = assemblyScanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = assemblyScanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = combine(
                progressStore.progressVersion,
                hardwoodsProgressStore.progressVersion
            ) { cnc, hw -> cnc + hw }
                .stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                return jobInfos.map { info ->
                    val indexProgress = info.indexProgress
                    val cncProg = indexProgress?.cnc
                    val hwProg = indexProgress?.hardwoods
                    val cncCounts = if (cncProg != null) {
                        StatusCounts(
                            total = cncProg.totalSheets,
                            complete = cncProg.done,
                            bad = cncProg.bad,
                            skipped = cncProg.skipped
                        )
                    } else StatusCounts()
                    val hardwoodCounts = hwProg?.let { hw ->
                        HardwoodStatusCounts(
                            totalPieces = hw.totalPieces,
                            donePieces = hw.donePieces,
                            badPieces = hw.badPieces,
                            skippedPieces = hw.skippedPieces
                        )
                    } ?: HardwoodStatusCounts()
                    val bothModes = cncProg != null && hwProg != null

                    val badges = mutableSetOf<JobBadge>()
                    if (info.isPending) badges.add(JobBadge.PENDING_DELIVERY)
                    if (info.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

                    UnifiedJobUiModel(
                        folderName = info.folderName,
                        jobNumber = info.jobNumber,
                        jobName = info.jobName,
                        isPinned = false,
                        isPending = info.isPending,
                        boardSection = info.boardSection,
                        lineupPosition = info.lineupPosition,
                        badges = badges,
                        labels = info.labels,
                        progressStyle = ProgressStyle.Assembly(
                            cncCounts = cncCounts,
                            hardwoodCounts = hardwoodCounts,
                            bothModes = bothModes
                        ),
                        onCardClick = { onJobClick(info.folderName) },
                        onView3DClick = { onView3D(info.folderName) },
                        onViewCoverSheetClick = { onViewCoverSheet(info.folderName) }
                    )
                }
            }
            
            override fun refresh(reason: RefreshReason, force: Boolean) {
                assemblyScanCoordinator.refresh(reason, force)
            }
            
            override suspend fun resolveBadges(folderName: String): Set<JobBadge> {
                val progress = jobInfos.firstOrNull { it.folderName == folderName }?.indexProgress
                val badges = mutableSetOf<JobBadge>()
                if (progress?.hasDeliverySheet == true) badges.add(JobBadge.HAS_DELIVERY_SHEET)
                if (progress?.has3DAssets == true) badges.add(JobBadge.HAS_3D_ASSETS)
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
    engine: UnifiedMetadataEngine,
    coroutineScope: CoroutineScope,
    onJobClick: (String) -> Unit,
    onView3D: ((String) -> Unit)? = null,
    onViewCoverSheet: ((String) -> Unit)? = null
): UnifiedJobsSpec {
    val scanState by specialtyScanCoordinator.state.collectAsState()
    val jobInfos = engine.getCachedJobInfos()
    
    return remember(scanState, jobInfos) {
        object : UnifiedJobsSpec {
            override val modeName = "jobs_specialty"
            override val scanStatus = specialtyScanCoordinator.state.map { it.status }.stateIn(coroutineScope, SharingStarted.Eagerly, ScanStatus.IDLE)
            override val scanGeneration = specialtyScanCoordinator.state.map { it.snapshot.generation }.stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override val progressVersion = specialtyStateStore.progressVersion
            
            override fun deriveJobCards(): List<UnifiedJobUiModel> {
                val snapshotCards = specialtyStateStore.deriveJobCards().associateBy { it.folderName }
                return jobInfos.map { info ->
                    snapshotCards[info.folderName]?.toUnifiedModel(
                        isPinned = false,
                        onCardClick = { onJobClick(info.folderName) }
                    ) ?: UnifiedJobUiModel(
                        folderName = info.folderName,
                        jobNumber = info.jobNumber,
                        jobName = info.jobName,
                        isPinned = false,
                        isPending = info.isPending,
                        boardSection = info.boardSection,
                        lineupPosition = info.lineupPosition,
                        labels = info.labels,
                        progressStyle = ProgressStyle.Specialty(
                            stationProgress = emptyList(),
                            totalItems = 0,
                            completedItems = 0,
                            fraction = 0f
                        ),
                        onCardClick = { onJobClick(info.folderName) }
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
