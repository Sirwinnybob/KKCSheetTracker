package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsJobDetailScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    jobFolderName: String,
    onOpenWorkspace: (HardwoodDocType) -> Unit,
    onOpenRipCutList: () -> Unit,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onBack: () -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val job: HardwoodJob = remember(scanState.snapshot.generation, jobFolderName) {
        scanState.snapshot.jobs.firstOrNull { it.folderName == jobFolderName }
            ?: HardwoodJob(jobFolderName, "", "")
    }
    val summary = remember(progressVersion, job.index) { progressStore.summarizeJob(job) }
    var progressExpanded by rememberSaveable(jobFolderName) { mutableStateOf(true) }
    var expandedDocs by rememberSaveable(jobFolderName) { mutableStateOf(setOf<String>()) }
    val rowProgressMap = remember(progressVersion, jobFolderName) { progressStore.getRowProgressMap(jobFolderName) }
    val totalsDoneMap = remember(progressVersion, jobFolderName) { progressStore.getTotalsRip10DoneMap(jobFolderName) }
    val docsByType = remember(job.index) {
        job.index?.documents.orEmpty().associateBy { it.docType }
    }
    val hasDeliverySheet = remember(jobFolderName) {
        jobRepository.getJobPdfCatalog(jobFolderName).deliverySheet != null
    }
    val docSummariesByType = remember(summary.documents) {
        summary.documents.associateBy { it.docType }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job.folderName.ifBlank { "Hardwoods Job" }) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1) }) {
                    Text("View Assembly")
                }
                Button(onClick = { onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1) }) {
                    Text("View Plans & Elevations")
                }
                if (hasDeliverySheet) {
                    Button(onClick = { onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1) }) {
                        Text("View Cover Sheet")
                    }
                }
                Button(onClick = onOpenThreeD) {
                    Text("View 3D")
                }
            }

            val jobStatusCounts = summary.counts.toStatusCounts()
            ProgressCard(
                title = "Hardwoods Progress",
                subtitle = "${summary.counts.donePieces}/${summary.counts.totalPieces} done",
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

            val boardStockCounts = remember(job.index, totalsDoneMap, scanState.snapshot.basePath) {
                val rows = buildBoardStockRows(scanState.snapshot.basePath, job.folderName, job.index)
                val total = rows.sumOf { it.neededRips.coerceAtLeast(0) }
                val done = rows.sumOf { row ->
                    val key = progressStore.makeBoardStockTallyKey(row.material, row.normalizedWidth, row.source.name)
                    (totalsDoneMap[key] ?: 0).coerceIn(0, row.neededRips.coerceAtLeast(0))
                }
                HardwoodStatusCounts(totalPieces = total, donePieces = done)
            }
            ProgressCard(
                title = "Rip Cut List",
                subtitle = "${boardStockCounts.donePieces}/${boardStockCounts.totalPieces} done",
                fraction = boardStockCounts.completionFraction,
                expanded = false,
                segmentedStatusCounts = boardStockCounts.toStatusCounts(),
                showBottomProgressBar = true,
                onToggleExpanded = {},
                onClick = onOpenRipCutList
            )

            for (docType in HardwoodDocType.entries) {
                val doc = docsByType[docType]
                val docSummary = docSummariesByType[docType]
                val available = doc != null
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
                    subtitle = if (available) {
                        "${counts.donePieces}/${counts.totalPieces} done • ${doc?.rows?.size ?: 0} rows"
                    } else {
                        "Not found"
                    },
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
                    onClick = { if (available) onOpenWorkspace(docType) }
                ) {
                    StatusSummaryRow(statusCounts)
                    if (available) {
                        Text(
                            "${doc?.totals?.size ?: 0} totals block" + if ((doc?.totals?.size ?: 0) == 1) "" else "s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Open after index refresh includes this document.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

internal fun HardwoodDocType.uiLabel(): String = when (this) {
    HardwoodDocType.FACE_FRAME_CUT_LIST -> "Face Frame Cut List"
    HardwoodDocType.NAILER_CUT_LIST -> "Nailer Cut List"
    HardwoodDocType.DOOR_CUT_LIST -> "Door Cut List"
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
