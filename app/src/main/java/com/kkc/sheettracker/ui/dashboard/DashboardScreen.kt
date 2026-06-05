package com.kkc.sheettracker.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.isRecentInProgressMaterial
import com.kkc.sheettracker.data.models.DashboardFlaggedSheetItem
import com.kkc.sheettracker.data.models.DashboardRecentMaterialItem
import com.kkc.sheettracker.data.models.DashboardUiModel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.ProgressPill
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val DASHBOARD_PARITY_TAG = "KKC_APP_STATE_PARITY_DASH"
private const val DASHBOARD_RECENT_LIMIT = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    onNavigateToJobs: () -> Unit,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit,
    onOpenJob: (jobFolderName: String) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val appDashboard by appStateStore.dashboardUiModel.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }
    val useAppState = appFlags.dashboardEnabled
    val jobs = scanState.snapshot.jobs
    var stats by remember { mutableStateOf(DashboardUiModel()) }
    var showBadList by remember { mutableStateOf(false) }
    var showSkippedList by remember { mutableStateOf(false) }
    var isComputing by remember { mutableStateOf(!useAppState) }
    val recentThumbCache = remember { mutableStateMapOf<String, Bitmap?>() }

    fun refreshDashboard(forceRefresh: Boolean = false) {
        scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = forceRefresh)
        appStateStore.requestRecompute()
    }

    LaunchedEffect(useAppState, scanState.snapshot.generation, progressVersion, appUiState.scanGeneration, appUiState.progressVersion) {
        if (useAppState) {
            stats = appDashboard
            isComputing = false
            return@LaunchedEffect
        }

        isComputing = true
        val legacyModel = withContext(Dispatchers.IO) {
            var totalSheets = 0
            var completed = 0
            var bad = 0
            var skipped = 0
            val badItems = mutableListOf<DashboardFlaggedSheetItem>()
            val skippedItems = mutableListOf<DashboardFlaggedSheetItem>()
            val recentItems = mutableListOf<DashboardRecentMaterialItem>()

            for (job in jobs) {
                progressStore.pruneLocalStateForJob(job.folderName, job.materials)
                val touchesByPdf = progressStore.getMaterialLastTouches(job.folderName)
                val counts = progressStore.getJobStatusCounts(job.folderName, job.materials)
                totalSheets += counts.total
                completed += counts.complete
                bad += counts.bad
                skipped += counts.skipped
                for (material in job.materials) {
                    val materialCounts = progressStore.getMaterialStatusCounts(job.folderName, material)
                    val touch = touchesByPdf[material.pdfFilename]
                    if (touch != null && isRecentInProgressMaterial(materialCounts)) {
                        val trackablePages = progressStore.getMaterialTrackablePages(material)
                        val clampedPage = if (touch.page in trackablePages) {
                            touch.page
                        } else {
                            trackablePages.minByOrNull { kotlin.math.abs(it - touch.page) } ?: touch.page.coerceIn(1, material.pageCount.coerceAtLeast(1))
                        }
                        val nextIncompletePage = nextIncompletePage(
                            trackablePages = trackablePages,
                            progressStore = progressStore,
                            jobFolderName = job.folderName,
                            material = material,
                            fallbackPage = clampedPage
                        )
                        val pageMeta = material.metadata?.pages?.firstOrNull { it.pageNumber == nextIncompletePage }
                            ?: material.metadata?.pages?.getOrNull((nextIncompletePage - 1).coerceAtLeast(0))
                        recentItems += DashboardRecentMaterialItem(
                            jobFolderName = job.folderName,
                            jobNumber = job.jobNumber,
                            materialName = material.materialName,
                            pdfFilename = material.pdfFilename,
                            fileFingerprint = material.fileFingerprint,
                            lastTouchedPage = clampedPage,
                            nextIncompletePage = nextIncompletePage,
                            lastTouchedAtMs = touch.touchedAtMs,
                            counts = materialCounts,
                            completionFraction = if (materialCounts.total > 0) materialCounts.complete.toFloat() / materialCounts.total.toFloat() else 0f,
                            thumbnailPath = pageMeta?.thumbnailPath
                        )
                    }
                    for (page in progressStore.getMaterialTrackablePages(material)) {
                        when (progressStore.getSheetStatus(job.folderName, material.pdfFilename, page, material.fileFingerprint)) {
                            SheetStatus.HAS_BAD_PARTS -> {
                                badItems += DashboardFlaggedSheetItem(
                                    jobFolderName = job.folderName,
                                    materialName = material.materialName,
                                    pdfFilename = material.pdfFilename,
                                    fileFingerprint = material.fileFingerprint,
                                    sheetPage = page,
                                    committedBadCount = progressStore.getBadParts(
                                        job.folderName,
                                        material.pdfFilename,
                                        page,
                                        material.fileFingerprint,
                                        includeDraft = false
                                    ).size,
                                    hasDraftBadParts = progressStore.getDraftBadParts(
                                        job.folderName,
                                        material.pdfFilename,
                                        page,
                                        material.fileFingerprint
                                    ).isNotEmpty()
                                )
                            }
                            SheetStatus.SKIPPED -> {
                                skippedItems += DashboardFlaggedSheetItem(
                                    jobFolderName = job.folderName,
                                    materialName = material.materialName,
                                    pdfFilename = material.pdfFilename,
                                    fileFingerprint = material.fileFingerprint,
                                    sheetPage = page,
                                    committedBadCount = 0,
                                    hasDraftBadParts = progressStore.getDraftBadParts(
                                        job.folderName,
                                        material.pdfFilename,
                                        page,
                                        material.fileFingerprint
                                    ).isNotEmpty()
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }
            DashboardUiModel(
                totalJobs = jobs.size,
                totalSheets = totalSheets,
                completedSheets = completed,
                badPartsSheets = bad,
                skippedSheets = skipped,
                badItems = badItems.sortedBy { "${it.jobFolderName}|${it.materialName}|${it.sheetPage}" },
                skippedItems = skippedItems.sortedBy { "${it.jobFolderName}|${it.materialName}|${it.sheetPage}" },
                recentInProgressMaterials = recentItems
                    .sortedByDescending { it.lastTouchedAtMs }
                    .take(DASHBOARD_RECENT_LIMIT)
            )
        }
        stats = legacyModel

        val legacyStats = legacyModel
        if (appFlags.shadowEnabled) {
            val badParity = appDashboard.badItems.size == legacyStats.badItems.size
            val skippedParity = appDashboard.skippedItems.size == legacyStats.skippedItems.size
            val totalsParity =
                appDashboard.totalJobs == legacyStats.totalJobs &&
                    appDashboard.totalSheets == legacyStats.totalSheets &&
                    appDashboard.completedSheets == legacyStats.completedSheets &&
                    appDashboard.badPartsSheets == legacyStats.badPartsSheets &&
                    appDashboard.skippedSheets == legacyStats.skippedSheets
            if (!badParity || !skippedParity || !totalsParity) {
                Log.w(
                    DASHBOARD_PARITY_TAG,
                    "mismatch totals=$totalsParity bad=$badParity skipped=$skippedParity appGen=${appUiState.scanGeneration} legacyGen=${scanState.snapshot.generation} appProgress=${appUiState.progressVersion} legacyProgress=$progressVersion"
                )
            }
        }
        isComputing = false
    }

    val completionFraction = if (stats.totalSheets > 0)
        stats.completedSheets.toFloat() / stats.totalSheets.toFloat()
    else 0f
    // stats is always the authoritative source: equals appDashboard when useAppState=true,
    // equals the freshly computed legacy model when useAppState=false.
    val recentInProgress = stats.recentInProgressMaterials

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { refreshDashboard(forceRefresh = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        val gradientEndPx = with(LocalDensity.current) { 300.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = KKCAlpha.gradientAccentTop),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = gradientEndPx
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            if (scanState.status == ScanStatus.LOADING || isComputing || appUiState.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val heroTint = if (isSystemInDarkTheme()) KKCAlpha.cardHeroTint else KKCAlpha.lightCardHeroTint
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = heroTint)
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 6.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Overall Progress",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${stats.completedSheets} of ${stats.totalSheets} sheets",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${stats.totalJobs} jobs tracked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { completionFraction },
                            modifier = Modifier.size(80.dp),
                            strokeWidth = 8.dp,
                            color = KKCThemeColors.statusColors.complete,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                        Text(
                            "${(completionFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Completed",
                    value = "${stats.completedSheets}",
                    color = KKCThemeColors.statusColors.complete,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Bad Parts",
                    value = "${stats.badPartsSheets}",
                    color = KKCThemeColors.statusColors.bad,
                    modifier = Modifier.weight(1f),
                    onClick = { showBadList = true }
                )
                StatCard(
                    label = "Skipped",
                    value = "${stats.skippedSheets}",
                    color = KKCThemeColors.statusColors.skip,
                    modifier = Modifier.weight(1f),
                    onClick = { showSkippedList = true }
                )
            }

            if (recentInProgress.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Recent In-Progress Materials",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            recentInProgress.forEach { item ->
                                val thumbKey = "${item.jobFolderName}|${item.pdfFilename}|${item.nextIncompletePage}|${item.thumbnailPath.orEmpty()}"
                                LaunchedEffect(thumbKey) {
                                    if (recentThumbCache.containsKey(thumbKey)) return@LaunchedEffect
                                    recentThumbCache[thumbKey] = withContext(Dispatchers.IO) {
                                        loadRecentMaterialThumbnail(jobRepository, item)
                                    }
                                }
                                RecentMaterialCard(
                                    item = item,
                                    thumbnail = recentThumbCache[thumbKey],
                                    onClick = {
                                        onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            QualityAlertCard(
                badCount = stats.badPartsSheets,
                skippedCount = stats.skippedSheets,
                onOpenBad = { showBadList = true },
                onOpenSkipped = { showSkippedList = true }
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToJobs),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "View All Jobs",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Browse and continue working on job sheets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go to jobs",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            }
        }
    }

    if (showBadList) {
        FlaggedSheetsSheet(
            title = "Bad Parts Sheets",
            items = stats.badItems,
            onDismiss = { showBadList = false },
            onOpen = { item ->
                showBadList = false
                onOpenSheet(item.jobFolderName, item.pdfFilename, item.sheetPage)
            },
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
        FlaggedSheetsSheet(
            title = "Skipped Sheets",
            items = stats.skippedItems,
            onDismiss = { showSkippedList = false },
            onOpen = { item ->
                showSkippedList = false
                onOpenSheet(item.jobFolderName, item.pdfFilename, item.sheetPage)
            },
            onResolve = {},
            showResolve = false
        )
    }
}

@Composable
private fun QualityAlertCard(
    badCount: Int,
    skippedCount: Int,
    onOpenBad: () -> Unit,
    onOpenSkipped: () -> Unit
) {
    val hasQualityIssues = badCount > 0 || skippedCount > 0
    val status = when {
        badCount > 0 -> SheetStatus.HAS_BAD_PARTS
        skippedCount > 0 -> SheetStatus.SKIPPED
        else -> SheetStatus.COMPLETE
    }
    val accentColor = when (status) {
        SheetStatus.HAS_BAD_PARTS -> KKCThemeColors.statusColors.bad
        SheetStatus.SKIPPED -> KKCThemeColors.statusColors.skipBorder
        SheetStatus.COMPLETE -> KKCThemeColors.statusColors.completeBorder
        SheetStatus.NOT_STARTED -> KKCThemeColors.statusColors.notStarted
        SheetStatus.IN_PROGRESS -> KKCThemeColors.statusColors.inProgressBorder
    }

    StatusBorderedCard(
        status = status,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasQualityIssues) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = accentColor
                )
                Text(
                    if (hasQualityIssues) "Quality Alert" else "Quality Alert Clear",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                when {
                    badCount > 0 && skippedCount > 0 -> "$badCount bad-part sheets and $skippedCount skipped sheets need review"
                    badCount > 0 -> "$badCount sheets have flagged bad parts"
                    skippedCount > 0 -> "$skippedCount skipped sheets need review"
                    else -> "No active bad-part or skipped sheet alerts."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasQualityIssues) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (badCount > 0) {
                        TextButton(onClick = onOpenBad, contentPadding = PaddingValues(0.dp)) {
                            Text("Open Bad Parts")
                        }
                    }
                    if (skippedCount > 0) {
                        TextButton(onClick = onOpenSkipped, contentPadding = PaddingValues(0.dp)) {
                            Text("Open Skipped")
                        }
                    }
                }
            }
        }
    }
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
        val pageIndex = (item.nextIncompletePage - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        if (renderer.pageCount <= 0) {
            renderer.close()
            fd.close()
            return null
        }
        val page = renderer.openPage(pageIndex)
        val maxW = 520
        val scale = maxW.toFloat() / page.width.toFloat()
        val outW = (page.width * scale).toInt().coerceAtLeast(1)
        val outH = (page.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        fd.close()
        bmp
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun RecentMaterialCard(
    item: DashboardRecentMaterialItem,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val status = inferStatusFromCounts(item.counts)
    StatusBorderedCard(
        status = status,
        modifier = Modifier.width(250.dp),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Recent material preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
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
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill("C ${item.counts.complete}", KKCThemeColors.statusColors.complete)
                StatusPill("B ${item.counts.bad}", KKCThemeColors.statusColors.bad)
                StatusPill("S ${item.counts.skipped}", KKCThemeColors.statusColors.skip)
                StatusPill("R ${item.counts.notStarted}", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun inferStatusFromCounts(counts: StatusCounts): SheetStatus {
    if (counts.total <= 0) return SheetStatus.NOT_STARTED
    return when {
        counts.bad > 0 -> SheetStatus.HAS_BAD_PARTS
        counts.skipped >= counts.total -> SheetStatus.SKIPPED
        counts.complete >= counts.total -> SheetStatus.COMPLETE
        counts.complete > 0 || counts.skipped > 0 -> SheetStatus.IN_PROGRESS
        else -> SheetStatus.NOT_STARTED
    }
}

private fun nextIncompletePage(
    trackablePages: List<Int>,
    progressStore: ProgressStore,
    jobFolderName: String,
    material: com.kkc.sheettracker.data.models.Material,
    fallbackPage: Int
): Int {
    return trackablePages.firstOrNull { page ->
        when (
            progressStore.getSheetStatus(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = material.fileFingerprint
            )
        ) {
            SheetStatus.NOT_STARTED, SheetStatus.IN_PROGRESS -> true
            SheetStatus.COMPLETE, SheetStatus.SKIPPED, SheetStatus.HAS_BAD_PARTS -> false
        }
    } ?: fallbackPage
}

@Composable
private fun StatusPill(
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlaggedSheetsSheet(
    title: String,
    items: List<DashboardFlaggedSheetItem>,
    onDismiss: () -> Unit,
    onOpen: (DashboardFlaggedSheetItem) -> Unit,
    onResolve: (DashboardFlaggedSheetItem) -> Unit,
    showResolve: Boolean
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                if (items.isEmpty()) "No sheets currently in this section." else "${items.size} sheet${if (items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (items.isEmpty()) {
                Spacer(Modifier.height(8.dp))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(item) },
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.jobFolderName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${item.materialName} • Sheet ${item.sheetPage} • ${item.pdfFilename}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (showResolve) {
                                    Text(
                                        "Flagged parts: ${item.committedBadCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KKCThemeColors.statusColors.bad
                                    )
                                    TextButton(
                                        onClick = { onResolve(item) },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Mark Resolved")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
