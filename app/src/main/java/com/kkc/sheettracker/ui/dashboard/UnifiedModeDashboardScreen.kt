package com.kkc.sheettracker.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.DashboardFlaggedSheetItem
import com.kkc.sheettracker.data.models.DashboardRecentMaterialItem
import com.kkc.sheettracker.data.models.DashboardUiModel
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodJobSummary
import com.kkc.sheettracker.data.models.HardwoodScanState
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.JobMaterialKey
import com.kkc.sheettracker.data.models.MaterialUiModel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SheetStatusKey
import com.kkc.sheettracker.data.models.SheetStatusSnapshot
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.SpecialtyJob
import com.kkc.sheettracker.data.models.SpecialtyScanState
import com.kkc.sheettracker.ui.components.ProgressPill
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UnifiedModeDashboardMode {
    CNC,
    HARDWOODS,
    ASSEMBLY,
    SPECIALTY
}

sealed interface UnifiedModeDashboardSpec {
    data class Cnc(
        val scanCoordinator: ScanCoordinator,
        val appStateStore: AppStateStore,
        val jobRepository: JobRepository,
        val progressStore: ProgressStore,
        val appStateFlags: AppStateFeatureFlags,
        val onNavigateToJobs: () -> Unit,
        val onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
    ) : UnifiedModeDashboardSpec

    data class Hardwoods(
        val scanCoordinator: HardwoodsScanCoordinator,
        val progressStore: HardwoodsProgressStore,
        val onOpenJob: (HardwoodJob) -> Unit
    ) : UnifiedModeDashboardSpec

    data class Assembly(
        val scanCoordinator: AssemblyScanCoordinator,
        val assemblyStateStore: AssemblyStateStore,
        val cncProgressStore: ProgressStore,
        val hardwoodsProgressStore: HardwoodsProgressStore,
        val specialtyStateStore: SpecialtyStateStore,
        val onOpenJob: (String) -> Unit
    ) : UnifiedModeDashboardSpec

    data class Specialty(
        val specialtyStateStore: SpecialtyStateStore,
        val onNavigateToJobs: () -> Unit,
        val onOpenJob: (String) -> Unit
    ) : UnifiedModeDashboardSpec
}

@Composable
fun UnifiedModeDashboardScreen(spec: UnifiedModeDashboardSpec) {
    when (spec) {
        is UnifiedModeDashboardSpec.Cnc -> CncDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            appStateStore = spec.appStateStore,
            jobRepository = spec.jobRepository,
            progressStore = spec.progressStore,
            appStateFlags = spec.appStateFlags,
            onNavigateToJobs = spec.onNavigateToJobs,
            onOpenSheet = spec.onOpenSheet
        )
        is UnifiedModeDashboardSpec.Hardwoods -> HardwoodsDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            progressStore = spec.progressStore,
            onOpenJob = spec.onOpenJob
        )
        is UnifiedModeDashboardSpec.Assembly -> AssemblyDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            assemblyStateStore = spec.assemblyStateStore,
            cncProgressStore = spec.cncProgressStore,
            hardwoodsProgressStore = spec.hardwoodsProgressStore,
            specialtyStateStore = spec.specialtyStateStore,
            onOpenJob = spec.onOpenJob
        )
        is UnifiedModeDashboardSpec.Specialty -> SpecialtyDashboardContent(
            specialtyStateStore = spec.specialtyStateStore,
            onNavigateToJobs = spec.onNavigateToJobs,
            onOpenJob = spec.onOpenJob
        )
    }
}

@Composable
private fun CncDashboardContent(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    onNavigateToJobs: () -> Unit,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val dashboard by appStateStore.dashboardUiModel.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appFlags = appStateFlags.snapshot()
    var showBadList by rememberSaveable { mutableStateOf(false) }
    var showSkippedList by rememberSaveable { mutableStateOf(false) }

    // Built on demand only while a dialog is open — the full per-sheet snapshot map is already
    // maintained for Job Browser/Detail, so this is a cheap filter rather than a fresh derivation.
    val sheetStatusSnapshots by appStateStore.sheetStatusSnapshots.collectAsState()
    val materialUiModels by appStateStore.materialUiModels.collectAsState()
    val badItems = remember(showBadList, sheetStatusSnapshots, materialUiModels) {
        if (!showBadList) emptyList() else flaggedSheetItems(sheetStatusSnapshots, materialUiModels, SheetStatus.HAS_BAD_PARTS)
    }
    val skippedItems = remember(showSkippedList, sheetStatusSnapshots, materialUiModels) {
        if (!showSkippedList) emptyList() else flaggedSheetItems(sheetStatusSnapshots, materialUiModels, SheetStatus.SKIPPED)
    }

    LaunchedEffect(appFlags.dashboardEnabled, scanState.snapshot.generation, appUiState.scanGeneration, appUiState.progressVersion) {
        if (appFlags.dashboardEnabled) {
            appStateStore.requestRecompute()
        }
    }

    val widgets = remember(dashboard) { buildCncDashboardWidgets(dashboard) }
    val nonRecentWidgets = remember(widgets) {
        widgets.filterNot { widget ->
            widget is DashboardWidgetModel.RecentItemsBlock && widget.key == "cnc-recents"
        }
    }

    DashboardShell(
        title = "Dashboard",
        subtitle = "CNC",
        loading = scanState.status == ScanStatus.LOADING || appUiState.isRefreshing,
        errorMessage = scanState.errorMessage ?: appUiState.errorMessage,
        emptyMessage = "No CNC dashboard widgets are available yet.",
        hasContent = widgets.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
        DashboardWidgetRenderer(
            widgets = nonRecentWidgets,
            onStatAction = {
                when (it) {
                    DashboardStatAction.BAD_PARTS -> showBadList = true
                    DashboardStatAction.SKIPPED -> showSkippedList = true
                }
            },
            onAlertAction = { showBadList = true }
        )
        CncRecentMaterialsSection(
            items = dashboard.recentInProgressMaterials,
            jobRepository = jobRepository,
            onOpenSheet = onOpenSheet
        )
        if (dashboard.incompleteRemakeMaterials.isNotEmpty()) {
            CncRemakesSection(
                items = dashboard.incompleteRemakeMaterials,
                onOpenSheet = onOpenSheet
            )
        }
        TextButton(onClick = onNavigateToJobs) { Text("View All Jobs") }
    }

    if (showBadList) {
        DashboardFlagSheetDialog(
            title = "Bad Parts Sheets",
            items = badItems,
            onDismiss = { showBadList = false },
            onOpen = { item -> onOpenSheet(item.jobFolderName, item.pdfFilename, item.sheetPage) },
            onResolve = { item ->
                progressStore.resolveBadPartsOnSheet(
                    item.jobFolderName,
                    item.pdfFilename,
                    item.sheetPage,
                    item.fileFingerprint
                )
            },
            showResolve = true
        )
    }

    if (showSkippedList) {
        DashboardFlagSheetDialog(
            title = "Skipped Sheets",
            items = skippedItems,
            onDismiss = { showSkippedList = false },
            onOpen = { item -> onOpenSheet(item.jobFolderName, item.pdfFilename, item.sheetPage) },
            onResolve = {},
            showResolve = false
        )
    }
}

@Composable
private fun CncRecentMaterialsSection(
    items: List<DashboardRecentMaterialItem>,
    jobRepository: JobRepository,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    DashboardSurfaceCard {
        DashboardSectionHeader(
            title = "Recent In-Progress Materials",
            subtitle = if (items.isEmpty()) null else "${items.size} recent material${if (items.size == 1) "" else "s"}"
        )
        if (items.isEmpty()) {
            Text(
                "Nothing is in progress right now.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.forEach { item ->
                    val thumbnail by produceState<Bitmap?>(
                        initialValue = null,
                        item.jobFolderName,
                        item.pdfFilename,
                        item.thumbnailPath,
                        item.nextIncompletePage
                    ) {
                        value = withContext(Dispatchers.IO) {
                            loadRecentMaterialThumbnail(jobRepository, item)
                        }
                    }
                    CncRecentMaterialCard(
                        item = item,
                        thumbnail = thumbnail,
                        onClick = {
                            onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CncRemakesSection(
    items: List<DashboardRecentMaterialItem>,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    val remakeColor = KKCThemeColors.statusColors.remakeBg
    DashboardSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(remakeColor, CircleShape)
            )
            DashboardSectionHeader(
                title = "Incomplete Remakes",
                subtitle = "${items.size} remake${if (items.size == 1) "" else "s"} pending"
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.forEach { item ->
                CncRemakeMaterialCard(
                    item = item,
                    remakeColor = remakeColor,
                    onClick = { onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage) }
                )
            }
        }
    }
}

@Composable
private fun CncRemakeMaterialCard(
    item: DashboardRecentMaterialItem,
    remakeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onClick)
            .border(width = 2.dp, color = remakeColor, shape = tileShape),
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Text(
            item.jobFolderName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            item.materialName,
            style = MaterialTheme.typography.bodySmall,
            color = remakeColor,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Next sheet ${item.nextIncompletePage}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${item.counts.complete}/${item.counts.total}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ProgressPill(
                done = item.counts.complete,
                total = item.counts.total,
                state = ProgressState.from(item.counts.complete, item.counts.total)
            )
        }
        LinearProgressIndicator(
            progress = { item.completionFraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = remakeColor,
            trackColor = remakeColor.copy(alpha = 0.2f)
        )
    }
}

private fun flaggedSheetItems(
    snapshots: Map<SheetStatusKey, SheetStatusSnapshot>,
    materials: Map<JobMaterialKey, MaterialUiModel>,
    status: SheetStatus
): List<DashboardFlaggedSheetItem> {
    return snapshots.entries
        .filter { it.value.status == status }
        .map { (key, snapshot) ->
            val materialName = materials[JobMaterialKey(key.jobFolderName, key.pdfFilename)]?.materialName
                ?: key.pdfFilename
            DashboardFlaggedSheetItem(
                jobFolderName = key.jobFolderName,
                materialName = materialName,
                pdfFilename = key.pdfFilename,
                fileFingerprint = key.fileFingerprint,
                sheetPage = key.page,
                committedBadCount = snapshot.committedBadCount,
                hasDraftBadParts = snapshot.hasDraftBadParts
            )
        }
        .sortedBy { "${it.jobFolderName}|${it.materialName}|${it.sheetPage}" }
}

private fun loadRecentMaterialThumbnail(
    jobRepository: JobRepository,
    item: DashboardRecentMaterialItem
): Bitmap? {
    val relativeOrAbsolute = item.thumbnailPath?.trim().orEmpty()
    if (relativeOrAbsolute.isBlank()) return null
    val sidecarThumb = try {
        val pdfFile = jobRepository.getPdfFile(item.jobFolderName, item.pdfFilename)
        val thumbFile = if (File(relativeOrAbsolute).isAbsolute) {
            File(relativeOrAbsolute)
        } else {
            File(pdfFile.parentFile, relativeOrAbsolute)
        }
        if (thumbFile.exists() && thumbFile.isFile) {
            BitmapFactory.decodeFile(thumbFile.absolutePath)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
    if (sidecarThumb != null) return sidecarThumb
    return renderPdfThumbnail(jobRepository, item)
}

private fun renderPdfThumbnail(
    jobRepository: JobRepository,
    item: DashboardRecentMaterialItem
): Bitmap? {
    return try {
        val pdfFile = jobRepository.getPdfFile(item.jobFolderName, item.pdfFilename)
        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        if (renderer.pageCount <= 0) {
            renderer.close()
            fd.close()
            return null
        }
        val pageIndex = (item.nextIncompletePage - 1).coerceIn(0, renderer.pageCount - 1)
        val page = renderer.openPage(pageIndex)
        val maxWidth = 520
        val scale = maxWidth.toFloat() / page.width.toFloat()
        val outWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val outHeight = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        fd.close()
        bitmap
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun CncRecentMaterialCard(
    item: DashboardRecentMaterialItem,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val tileAccent = recentMaterialAccent(item.counts)
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(268.dp)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = DashboardSurfaceDefaults.outlineColor(tileAccent).copy(alpha = 0.45f),
                shape = tileShape
            ),
        accent = tileAccent,
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Recent material preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Description,
                        contentDescription = "Description icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item.materialName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                "${item.jobFolderName} • Next sheet ${item.nextIncompletePage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${item.counts.complete}/${item.counts.total} complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProgressPill(
                    done = item.counts.complete,
                    total = item.counts.total,
                    state = if (item.counts.skipped >= item.counts.total && item.counts.total > 0) {
                        ProgressState.SKIPPED
                    } else {
                        ProgressState.from(item.counts.complete, item.counts.total)
                    }
                )
            }
            LinearProgressIndicator(
                progress = { item.completionFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = KKCThemeColors.statusColors.completeBorder,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DashboardAccentPill("C ${item.counts.complete}", DashboardAccent.SUCCESS)
                DashboardAccentPill("B ${item.counts.bad}", DashboardAccent.DANGER)
                DashboardAccentPill("S ${item.counts.skipped}", DashboardAccent.WARNING)
                DashboardAccentPill("R ${item.counts.notStarted}", DashboardAccent.INFO)
            }
        }
    }
}

private fun recentMaterialAccent(counts: StatusCounts): DashboardAccent = when {
    counts.bad > 0 -> DashboardAccent.DANGER
    counts.skipped > 0 -> DashboardAccent.WARNING
    counts.complete > 0 -> DashboardAccent.INFO
    else -> DashboardAccent.NEUTRAL
}

@Composable
private fun HardwoodsDashboardContent(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    onOpenJob: (HardwoodJob) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val jobs = scanState.snapshot.jobs
    val summaries = remember(jobs, progressStore) {
        jobs.map { job -> progressStore.summarizeJob(job) }
    }
    val totalCounts = summaries.fold(HardwoodStatusCounts()) { acc, entry ->
        HardwoodStatusCounts(
            totalPieces = acc.totalPieces + entry.counts.totalPieces,
            donePieces = acc.donePieces + entry.counts.donePieces,
            badPieces = acc.badPieces + entry.counts.badPieces,
            skippedPieces = acc.skippedPieces + entry.counts.skippedPieces
        )
    }
    val recentJobs = remember(jobs) {
        jobs.take(6).map { job ->
            DashboardProgressItemModel(
                id = job.folderName,
                title = job.jobNumber.ifBlank { job.folderName },
                subtitle = job.folderName,
                supportingText = null,
                accent = DashboardAccent.INFO
            )
        }
    }
    DashboardShell(
        title = "Hardwoods Dashboard",
        subtitle = "Hardwoods",
        loading = scanState.status == ScanStatus.LOADING,
        errorMessage = scanState.errorMessage,
        emptyMessage = "No hardwood jobs are available yet.",
        hasContent = jobs.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
        DashboardWidgetRenderer(
            widgets = buildHardwoodsDashboardWidgets(
                totalJobs = jobs.size,
                totalCounts = totalCounts,
                recentJobs = recentJobs
            ),
            onItemClick = { item ->
                if (item is DashboardProgressItemModel) {
                    jobs.firstOrNull { it.folderName == item.id }?.let(onOpenJob)
                }
            }
        )
    }
}

@Composable
private fun AssemblyDashboardContent(
    scanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    cncProgressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyStateStore: SpecialtyStateStore,
    onOpenJob: (String) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val cncProgressVersion by cncProgressStore.progressVersion.collectAsState()
    val hardwoodsProgressVersion by hardwoodsProgressStore.progressVersion.collectAsState()
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    var cardItems by remember { mutableStateOf<List<AssemblyJobCard>>(emptyList()) }

    LaunchedEffect(scanState.snapshot.generation, cncProgressVersion, hardwoodsProgressVersion) {
        val jobs = withContext(Dispatchers.IO) {
            assemblyStateStore.deriveJobCards()
        }
        cardItems = jobs
    }

    val specialtyJobs = specialtyStateStore.getJobs()
    val specialtySummary = remember(specialtyJobs) {
        SpecialtySummary(
            jobCount = specialtyJobs.size,
            completedItems = specialtyJobs.sumOf { it.completedItems },
            totalItems = specialtyJobs.sumOf { it.totalItems }
        )
    }

    DashboardShell(
        title = "Assembly Dashboard",
        subtitle = "Assembly",
        loading = scanState.status == ScanStatus.LOADING,
        errorMessage = scanState.errorMessage,
        emptyMessage = "No assembly jobs are available yet.",
        hasContent = cardItems.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
        DashboardWidgetRenderer(
            widgets = buildAssemblyDashboardWidgets(
                cards = cardItems,
                specialtyStatus = scanState.status,
                specialtySummary = specialtySummary,
                totalCabinets = cardItems.size
            ),
            onItemClick = { item ->
                if (item is DashboardProgressItemModel) {
                    onOpenJob(item.id)
                }
            }
        )
    }
}

@Composable
private fun SpecialtyDashboardContent(
    specialtyStateStore: SpecialtyStateStore,
    onNavigateToJobs: () -> Unit,
    onOpenJob: (String) -> Unit
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val jobs = remember(scanState.snapshot.generation, progressVersion) { specialtyStateStore.getJobs() }

    val recentJobs = remember(jobs) {
        jobs.take(8).map { job ->
            DashboardProgressItemModel(
                id = job.folderName,
                title = job.jobNumber.ifBlank { job.folderName },
                subtitle = job.folderName,
                accent = DashboardAccent.INFO
            )
        }
    }
    val inProgressItems = remember(jobs) {
        jobs.flatMap { job ->
            job.resolvedItems.mapNotNull { resolved ->
                val totalSteps = resolved.completionByKey.size.coerceAtLeast(1)
                val completedSteps = resolved.completionByKey.values.count { it.completed }
                if (completedSteps <= 0 || completedSteps >= totalSteps) return@mapNotNull null
                DashboardProgressItemModel(
                    id = "${job.folderName}::${resolved.item.id}",
                    title = resolved.item.name,
                    subtitle = job.folderName,
                    supportingText = "$completedSteps/$totalSteps steps complete",
                    accent = DashboardAccent.INFO
                )
            }
        }
    }
    val jobItems = remember(jobs) {
        jobs.map { job ->
            DashboardProgressItemModel(
                id = job.folderName,
                title = job.jobNumber.ifBlank { job.folderName },
                subtitle = job.folderName,
                accent = DashboardAccent.INFO
            )
        }
    }

    DashboardShell(
        title = "Specialty Dashboard",
        subtitle = "Specialty",
        loading = scanState.status == ScanStatus.LOADING,
        errorMessage = scanState.errorMessage,
        emptyMessage = "No specialty jobs are available yet.",
        hasContent = jobs.isNotEmpty() || inProgressItems.isNotEmpty() || recentJobs.isNotEmpty(),
        onRefresh = { specialtyStateStore.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
        DashboardWidgetRenderer(
            widgets = buildSpecialtyDashboardWidgets(
                totalJobs = jobs.size,
                totalItems = jobs.sumOf { it.totalItems },
                completedItems = jobs.sumOf { it.completedItems },
                recentJobs = recentJobs,
                jobItems = jobItems,
                inProgressItems = inProgressItems
            ),
            onItemClick = { item ->
                if (item is DashboardProgressItemModel) {
                    onOpenJob(item.subtitle.ifBlank { item.id.substringBefore("::").ifBlank { item.id } })
                }
            },
            onAlertAction = onNavigateToJobs
        )
    }
}

@Composable
private fun DashboardFlagSheetDialog(
    title: String,
    items: List<DashboardFlaggedSheetItem>,
    onDismiss: () -> Unit,
    onOpen: (DashboardFlaggedSheetItem) -> Unit,
    onResolve: (DashboardFlaggedSheetItem) -> Unit,
    showResolve: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (items.isEmpty()) {
                    Text("No sheets currently in this section.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${items.size} sheet${if (items.size == 1) "" else "s"}")
                    items.take(6).forEach { item ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(item.jobFolderName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${item.materialName} • Sheet ${item.sheetPage}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { onOpen(item) }) { Text("Open") }
                                if (showResolve) {
                                    Text(
                                        "Flagged parts: ${item.committedBadCount}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Button(onClick = { onResolve(item) }) { Text("Mark Resolved") }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
