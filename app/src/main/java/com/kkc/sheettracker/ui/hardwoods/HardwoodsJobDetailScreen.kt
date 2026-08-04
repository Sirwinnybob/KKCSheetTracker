package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import com.kkc.sheettracker.ui.components.PrintDocumentsBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.components.ClockInButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusSummaryRow
import com.kkc.sheettracker.ui.specialty.CompactSpecialtySection
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.specialty.SpecialtySurfaceMode
import java.io.File

internal fun HardwoodStatusCounts.toStatusCounts(): StatusCounts {
    val effectiveTotal = effectiveTotalPieces
    val complete = (donePieces + badPieces).coerceAtMost(effectiveTotal)
    val notStarted = (effectiveTotal - complete).coerceAtLeast(0)
    return StatusCounts(
        total = effectiveTotal,
        complete = complete,
        skipped = 0
    )
}

private data class HardwoodsDetailProgress(
    val summary: com.kkc.sheettracker.data.models.HardwoodJobSummary? = null,
    val rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress> = emptyMap(),
    val totalsDoneMap: Map<String, Int> = emptyMap()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsJobDetailScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    specialtyStateStore: SpecialtyStateStore,
    jobFolderName: String,
    onOpenWorkspace: (HardwoodDocType) -> Unit,
    onOpenRipCutList: () -> Unit,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onBack: () -> Unit,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onLeaveWhileClockedIn: () -> Unit = {},
    clockInState: ClockInState? = null
) {
    val navBarDeco = LocalNavBarDecoration.current
    LaunchedEffect(Unit) {
        navBarDeco.searchDecoration = null
        navBarDeco.keepSearchDeco = false
    }

    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val engine = remember(scanState.snapshot.basePath) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), BuildConfig.DEBUG)
    }
    val emptyJob = remember(jobFolderName) { HardwoodJob(jobFolderName, "", "") }
    val job by produceState<HardwoodJob?>(
        initialValue = null,
        engine,
        scanState.snapshot.generation,
        jobFolderName
    ) {
        value = withContext(Dispatchers.IO) {
            engine.getHardwoodsSnapshot(jobFolderName)?.job
        }
    }
    val resolvedJob = job ?: emptyJob
    val detailProgress by produceState(
        initialValue = HardwoodsDetailProgress(),
        resolvedJob,
        jobFolderName,
        progressVersion
    ) {
        value = withContext(Dispatchers.IO) {
            HardwoodsDetailProgress(
                summary = progressStore.summarizeJob(resolvedJob),
                rowProgressMap = progressStore.getRowProgressMap(jobFolderName),
                totalsDoneMap = progressStore.getTotalsRip10DoneMap(jobFolderName)
            )
        }
    }
    val summary = detailProgress.summary ?: com.kkc.sheettracker.data.models.HardwoodJobSummary(resolvedJob)
    var progressExpanded by rememberSaveable(jobFolderName) { mutableStateOf(true) }
    var expandedDocs by rememberSaveable(jobFolderName) { mutableStateOf(setOf<String>()) }
    val rowProgressMap = detailProgress.rowProgressMap
    val totalsDoneMap = detailProgress.totalsDoneMap
    val docsByType = remember(resolvedJob.index) {
        resolvedJob.index?.documents.orEmpty().associateBy { it.docType }
    }
    val availableDocsByType by produceState(
        initialValue = emptyMap<HardwoodDocType, HardwoodDocumentIndex>(),
        docsByType,
        jobFolderName
    ) {
        value = withContext(Dispatchers.IO) {
            docsByType.filterValues { doc ->
                doc.pdfFilename.isNotBlank() &&
                    jobRepository.getJobRootPdfFile(
                        jobFolderName = jobFolderName,
                        pdfFilename = doc.pdfFilename,
                        preferDarkMode = false
                    ) != null
            }
        }
    }
    // Document availability loaded async — avoids blocking the composition thread on I/O
    var hasDeliverySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasAssemblySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPlansElevations by remember(jobFolderName) { mutableStateOf(false) }
    var hasThreeDAssets by remember(jobFolderName) { mutableStateOf(false) }
    LaunchedEffect(jobFolderName) {
        withContext(Dispatchers.IO) {
            hasDeliverySheet = jobRepository.getJobPdfCatalog(jobFolderName).deliverySheet != null
            hasAssemblySheet = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY)
            hasPlansElevations = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            hasThreeDAssets = jobRepository.hasThreeDAssets(jobFolderName)
        }
    }
    val docSummariesByType = remember(summary.documents) {
        summary.documents.associateBy { it.docType }
    }
    var suppressLeavePrompt by remember { mutableStateOf(false) }
    var showPrintDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(isClockedInHere) {
        val shouldNotify = isClockedInHere
        val notifyFn = onLeaveWhileClockedIn
        onDispose { if (shouldNotify && !suppressLeavePrompt) notifyFn() }
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        resolvedJob.folderName.ifBlank { "Hardwoods Job" },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                
                
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (clockInState != null) {
                        ClockInButton(
                            clockInState = clockInState,
                            isClockedInHere = isClockedInHere,
                            onClockInClick = { onClockIn(resolvedJob.jobNumber, resolvedJob.jobName) }
                        )
                    } else {
                        Button(
                            onClick = { onClockIn(resolvedJob.jobNumber, resolvedJob.jobName) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF38A169),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                if (isClockedInHere) "● CLOCKED IN" else "CLOCK IN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAssemblySheet) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1)
                    }) {
                        Text("View Assembly")
                    }
                }
                if (hasPlansElevations) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1)
                    }) {
                        Text("View Plans & Elevations")
                    }
                }
                if (hasDeliverySheet) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1)
                    }) {
                        Text("View Cover Sheet")
                    }
                }
                if (hasThreeDAssets) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenThreeD()
                    }) {
                        Text("View 3D")
                    }
                }
                Button(onClick = { showPrintDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Print")
                }
            }

            val jobStatusCounts = summary.counts.toStatusCounts()
            ProgressCard(
                title = "Hardwoods Progress",
                subtitle = "${summary.counts.donePieces}/${summary.counts.effectiveTotalPieces} done",
                fraction = summary.counts.completionFraction,
                expanded = progressExpanded,
                segmentedStatusCounts = jobStatusCounts,
                showBottomProgressBar = true,
                onToggleExpanded = { progressExpanded = !progressExpanded },
                onClick = {}
            ) {
                StatusSummaryRow(jobStatusCounts)
                Text(
                    "Bad ${summary.counts.badPieces} • Skipped ${summary.counts.skippedPieces}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var boardStockCounts by remember(resolvedJob.index, totalsDoneMap, scanState.snapshot.basePath, rowProgressMap) {
                mutableStateOf(HardwoodStatusCounts(0, 0))
            }
            LaunchedEffect(resolvedJob.index, totalsDoneMap, scanState.snapshot.basePath, rowProgressMap) {
                val calculated = withContext(Dispatchers.IO) {
                    val rows = applySkippedPartRowsToBoardStockRows(
                        rows = buildBoardStockRows(scanState.snapshot.basePath, resolvedJob.folderName, resolvedJob.index),
                        index = resolvedJob.index,
                        rowProgressMap = rowProgressMap
                    )
                    val total = rows.sumOf { row ->
                        val materialSkippedKey = progressStore.makeBoardStockMaterialSkipKey(row.material)
                        val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(row.material, row.normalizedWidth, row.source.name)
                        val skipped = (totalsDoneMap[materialSkippedKey] ?: 0) > 0 || (totalsDoneMap[lineSkippedKey] ?: 0) > 0
                        if (skipped) 0 else row.neededRips.coerceAtLeast(0)
                    }
                    val done = rows.sumOf { row ->
                        val key = progressStore.makeBoardStockTallyKey(row.material, row.normalizedWidth, row.source.name)
                        val materialSkippedKey = progressStore.makeBoardStockMaterialSkipKey(row.material)
                        val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(row.material, row.normalizedWidth, row.source.name)
                        val skipped = (totalsDoneMap[materialSkippedKey] ?: 0) > 0 || (totalsDoneMap[lineSkippedKey] ?: 0) > 0
                        if (skipped) 0 else (totalsDoneMap[key] ?: 0).coerceIn(0, row.neededRips.coerceAtLeast(0))
                    }
                    HardwoodStatusCounts(totalPieces = total, donePieces = done)
                }
                boardStockCounts = calculated
            }
            ProgressCard(
                title = "Rip Cut List",
                subtitle = "${boardStockCounts.donePieces}/${boardStockCounts.effectiveTotalPieces} done",
                fraction = boardStockCounts.completionFraction,
                expanded = false,
                segmentedStatusCounts = boardStockCounts.toStatusCounts(),
                showBottomProgressBar = true,
                onToggleExpanded = {},
                onClick = {
                    suppressLeavePrompt = true
                    onOpenRipCutList()
                }
            )

            val visibleDocTypes = HardwoodDocType.entries.filter { it in availableDocsByType.keys }
            for (docType in visibleDocTypes) {
                val doc = availableDocsByType[docType] ?: continue
                val docSummary = docSummariesByType[docType]
                val available = true
                val counts = docSummary?.counts ?: com.kkc.sheettracker.data.models.HardwoodStatusCounts()
                val statusCounts = counts.toStatusCounts()
                val materialSegments = if (available && docType != HardwoodDocType.DOOR_LIST) {
                    buildHardwoodsMaterialSegments(doc, rowProgressMap)
                } else {
                    null
                }
                val expanded = docType.name in expandedDocs
                ProgressCard(
                    title = docType.uiLabel(),
                    subtitle = "${counts.donePieces}/${counts.effectiveTotalPieces} done • ${doc.rows.size} rows",
                    fraction = if (!available) 0f else counts.completionFraction,
                    expanded = expanded,
                    segmentedStatusCounts = statusCounts,
                    materialSegments = materialSegments,
                    hidePrimaryProgressBar = docType == HardwoodDocType.DOOR_LIST,
                    showBottomProgressBar = true,
                    onToggleExpanded = {
                        expandedDocs = if (expanded) {
                            expandedDocs - docType.name
                        } else {
                            expandedDocs + docType.name
                        }
                    },
                    onClick = {
                        if (available) {
                            suppressLeavePrompt = true
                            onOpenWorkspace(docType)
                        }
                    }
                ) {
                    StatusSummaryRow(statusCounts)
                    if (available) {
                        Text(
                            "${doc.totals.size} totals block" + if (doc.totals.size == 1) "" else "s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            CompactSpecialtySection(
                jobFolderName = jobFolderName,
                specialtyStateStore = specialtyStateStore,
                mode = SpecialtySurfaceMode.HARDWOODS
            )
        }
        if (showPrintDialog) {
            PrintDocumentsBottomSheet(
                jobFolderName = jobFolderName,
                jobRepository = jobRepository,
                onDismissRequest = { showPrintDialog = false }
            )
        }
    }
}

internal fun HardwoodDocType.uiLabel(): String = when (this) {
    HardwoodDocType.FACE_FRAME_CUT_LIST -> "Face Frame Cut List"
    HardwoodDocType.NAILER_CUT_LIST -> "Nailer Cut List"
    HardwoodDocType.DOOR_CUT_LIST -> "Door Cut List"
    HardwoodDocType.CLOSET_ROD_CUT_LIST -> "Closet Rod Cut List"
    HardwoodDocType.DOOR_LIST -> "Door List"
}

private fun buildHardwoodsMaterialSegments(
    document: HardwoodDocumentIndex,
    rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress>
): List<MaterialSegmentData> {
    if (document.rows.isEmpty()) return emptyList()
    val grouped = linkedMapOf<String, HardwoodStatusCounts>()
    document.rows.forEach { row ->
        val qty = row.qty.coerceAtLeast(0)
        val materialKey = row.material?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown"
        val raw = rowProgressMap[document.docType.name to row.rowId] ?: HardwoodRowProgress()
        val normalized = normalizeRowProgress(qty = qty, progress = raw)
        val current = grouped[materialKey] ?: HardwoodStatusCounts()
        grouped[materialKey] = HardwoodStatusCounts(
            totalPieces = current.totalPieces + qty,
            donePieces = current.donePieces + if (normalized.skipped) 0 else normalized.doneCount,
            badPieces = current.badPieces + if (normalized.skipped) 0 else normalized.badCount,
            skippedPieces = current.skippedPieces + if (normalized.skipped) qty else 0
        )
    }
    return grouped.entries
        .map { (material, counts) ->
            MaterialSegmentData(materialName = material, counts = counts.toStatusCounts())
        }
        .filter { it.counts.total > 0 }
}

private fun normalizeRowProgress(qty: Int, progress: HardwoodRowProgress): HardwoodRowProgress {
    val clampedQty = qty.coerceAtLeast(0)
    var done = progress.doneCount.coerceIn(0, clampedQty)
    var bad = progress.badCount.coerceIn(0, clampedQty)
    if (done + bad > clampedQty) {
        val overflow = done + bad - clampedQty
        if (done >= bad) {
            done = (done - overflow).coerceAtLeast(0)
        } else {
            bad = (bad - overflow).coerceAtLeast(0)
        }
    }
    return HardwoodRowProgress(doneCount = done, badCount = bad, skipped = progress.skipped)
}
