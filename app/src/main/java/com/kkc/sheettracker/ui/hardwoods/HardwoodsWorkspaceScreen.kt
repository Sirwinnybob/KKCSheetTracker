package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.HardwoodTotalsBlock
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.components.AdaptiveSplitLayout
import com.kkc.sheettracker.ui.components.ProgressPill
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.components.ReferencePdfPane
import com.kkc.sheettracker.ui.components.SectionProgressHeader
import com.kkc.sheettracker.ui.theme.DimensionTextStyle
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

private enum class HardwoodsListSegment {
    PART_LIST,
    TOTALS
}

private data class HardwoodsTotalsLine(
    val key: String,
    val blockIndex: Int,
    val lineIndex: Int,
    val material: String,
    val pagesLabel: String,
    val width: String,
    val length: String,
    val ripsRaw: String,
    val ripsLinearFeet: Double,
    val targetCount: Int
)

private data class HardwoodsTotalsSection(
    val material: String,
    val pagesLabel: String,
    val lines: List<HardwoodsTotalsLine>
)

private data class HardwoodsPartSection(
    val material: String,
    val pagesLabel: String,
    val rows: List<HardwoodCutlistRow>
)

private data class HardwoodsRowUiModel(
    val row: HardwoodCutlistRow,
    val normalizedCabs: List<String>,
    val isMultiCab: Boolean,
    val widthKey: String,
    val cabDisplayText: String
)

private data class HardwoodsSectionProgress(
    val donePieces: Int,
    val totalPieces: Int
) {
    val fraction: Float
        get() = if (totalPieces <= 0) 0f else donePieces.toFloat() / totalPieces.toFloat()
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HardwoodsWorkspaceScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsProgressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    jobFolderName: String,
    initialDocType: HardwoodDocType,
    initialRowId: String?,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("kkc_tracker", android.content.Context.MODE_PRIVATE) }
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by hardwoodsProgressStore.progressVersion.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val statusColors = KKCThemeColors.statusColors

    val job = remember(scanState.snapshot.generation, jobFolderName) {
        scanState.snapshot.jobs.firstOrNull { it.folderName == jobFolderName }
    }
    val documents = remember(job?.index) { job?.index?.documents.orEmpty() }
    var selectedDocType by remember(jobFolderName) { mutableStateOf(initialDocType) }

    val availableDocTypes = remember(documents) { documents.map { it.docType }.toSet() }
    if (selectedDocType !in availableDocTypes && availableDocTypes.isNotEmpty()) {
        selectedDocType = availableDocTypes.first()
    }

    val listState = rememberLazyListState()
    val selectedDoc = remember(documents, selectedDocType) { documents.firstOrNull { it.docType == selectedDocType } }
    val selectedDocName = selectedDoc?.docType?.name.orEmpty()
    val rows = selectedDoc?.rows.orEmpty()
    val totals = selectedDoc?.totals.orEmpty()
    var listSegment by remember(jobFolderName, selectedDocType) { mutableStateOf(HardwoodsListSegment.PART_LIST) }
    val partSections = remember(rows, totals) {
        buildHardwoodsPartSections(rows, totals, HardwoodsRowSortMode.CutlistOrder)
    }
    val displayRows = remember(partSections) { partSections.flatMap { it.rows } }
    val lazyIndexByRowId = remember(partSections) {
        val indexById = mutableMapOf<String, Int>()
        var index = 0
        partSections.forEach { section ->
            index += 1 // header row
            section.rows.forEach { row ->
                indexById[row.rowId] = index
                index += 1
            }
        }
        indexById
    }
    val rowProgressMap = remember(progressVersion, jobFolderName) { hardwoodsProgressStore.getRowProgressMap(jobFolderName) }
    var highlightedRowId by remember(jobFolderName, initialRowId) { mutableStateOf(initialRowId) }
    val widthColorBands = remember(displayRows, statusColors.widthBandPalette) {
        val palette = statusColors.widthBandPalette
        val seen = LinkedHashMap<String, Color>()
        var next = 0
        displayRows.forEach { row ->
            val key = normalizeWidthForGrouping(row.width)
            if (key.isNotEmpty() && !seen.containsKey(key)) {
                seen[key] = palette[next % palette.size]
                next++
            }
        }
        seen
    }
    val totalsLines = remember(selectedDocType, totals) {
        buildHardwoodsTotalsLines(selectedDocType, totals)
    }
    val totalsDoneMap = remember(progressVersion, jobFolderName) {
        hardwoodsProgressStore.getTotalsRip10DoneMap(jobFolderName)
    }
    val rowDisplayMap = remember(displayRows) {
        displayRows.associate { row ->
            val normalizedCabs = row.cabinets.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            row.rowId to HardwoodsRowUiModel(
                row = row,
                normalizedCabs = normalizedCabs,
                isMultiCab = normalizedCabs.size > 1,
                widthKey = normalizeWidthForGrouping(row.width),
                cabDisplayText = formatCabinetDisplay(row.rawCabinetText, row.cabinets)
            )
        }
    }
    val sectionProgressByKey = remember(partSections, rowProgressMap, selectedDocName) {
        partSections.associate { section ->
            val totalPieces = section.rows.sumOf { it.qty.coerceAtLeast(0) }
            val donePieces = section.rows.sumOf { row ->
                val done = rowProgressMap[selectedDocName to row.rowId]?.doneCount ?: 0
                done.coerceIn(0, row.qty.coerceAtLeast(0))
            }
            "${section.material}|${section.pagesLabel}" to HardwoodsSectionProgress(donePieces = donePieces, totalPieces = totalPieces)
        }
    }

    LaunchedEffect(initialRowId, selectedDocType, partSections.size, displayRows.size) {
        val target = initialRowId ?: return@LaunchedEffect
        val idx = lazyIndexByRowId[target]
        if (idx != null) {
            listState.animateScrollToItem(idx)
            highlightedRowId = target
            delay(1800)
            if (highlightedRowId == target) highlightedRowId = null
        }
    }

    var referenceDocType by remember(jobFolderName) {
        val stored = prefs.getString("hardwoods_last_ref_$jobFolderName", ReferenceDocType.ASSEMBLY.name)
        mutableStateOf(runCatching { ReferenceDocType.valueOf(stored ?: ReferenceDocType.ASSEMBLY.name) }.getOrDefault(ReferenceDocType.ASSEMBLY))
    }
    LaunchedEffect(referenceDocType) {
        prefs.edit().putString("hardwoods_last_ref_$jobFolderName", referenceDocType.name).apply()
    }

    var cabPickerRow by remember { mutableStateOf<HardwoodCutlistRow?>(null) }
    var cabSkipRow by remember { mutableStateOf<HardwoodCutlistRow?>(null) }
    var promptSwitchDocForCab by remember { mutableStateOf<String?>(null) }
    var promptSwitchTarget by remember { mutableStateOf<ReferenceDocType?>(null) }
    var lastJumpCab by remember(jobFolderName) { mutableStateOf<String?>(null) }
    var collapsedPartSectionsByDoc by rememberSaveable(jobFolderName) { mutableStateOf(mapOf<String, Set<String>>()) }
    var showReferencePane by remember(jobFolderName) { mutableStateOf(true) }
    val skippedCabinetMap = remember(progressVersion, jobFolderName) { hardwoodsProgressStore.getSkippedCabinetMap(jobFolderName) }

    var referencePage by remember(jobFolderName) { mutableIntStateOf(1) }
    val cabinetIndex = remember(jobFolderName) { jobRepository.getCabinetSheetIndex(jobFolderName) }

    fun mappedPage(docType: ReferenceDocType, cab: String): Int? {
        val map = when (docType) {
            ReferenceDocType.ASSEMBLY -> cabinetIndex?.documents?.assembly?.cabinetToPages
            ReferenceDocType.PLANS_ELEVATIONS -> cabinetIndex?.documents?.plansElevations?.cabinetToPages
        }
        return map?.get(cab)?.firstOrNull()
    }

    suspend fun jumpToCab(cab: String) {
        val normalizedCab = cab.trim()
        if (normalizedCab.isBlank()) {
            snackbar.showSnackbar("No cabinet mapping on this row")
            return
        }
        lastJumpCab = normalizedCab
        showReferencePane = true
        val current = mappedPage(referenceDocType, normalizedCab)
        if (current != null) {
            referencePage = current
            return
        }
        val fallbackType = if (referenceDocType == ReferenceDocType.ASSEMBLY) ReferenceDocType.PLANS_ELEVATIONS else ReferenceDocType.ASSEMBLY
        val fallback = mappedPage(fallbackType, normalizedCab)
        if (fallback != null) {
            promptSwitchDocForCab = normalizedCab
            promptSwitchTarget = fallbackType
        } else {
            snackbar.showSnackbar("Not found in Assembly or Plans/Elevations for cabinet $normalizedCab")
        }
    }

    fun startRowJump(row: HardwoodCutlistRow) {
        val cabs = row.cabinets.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        when {
            cabs.isEmpty() -> {
                scope.launch {
                    snackbar.showSnackbar("No cabinet mapping on this row")
                }
            }
            cabs.size == 1 -> {
                scope.launch { jumpToCab(cabs.first()) }
            }
            else -> {
                cabPickerRow = row
            }
        }
    }

    if (cabPickerRow != null) {
        val row = cabPickerRow!!
        AlertDialog(
            onDismissRequest = { cabPickerRow = null },
            title = { Text("Choose Cabinet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.cabinets.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { cab ->
                        Button(
                            onClick = {
                                cabPickerRow = null
                                scope.launch { jumpToCab(cab) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cabinet $cab")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { cabPickerRow = null }) { Text("Close") }
            }
        )
    }

    if (promptSwitchDocForCab != null && promptSwitchTarget != null) {
        AlertDialog(
            onDismissRequest = {
                promptSwitchDocForCab = null
                promptSwitchTarget = null
            },
            title = { Text("Not found in selected document") },
            text = {
                Text("Cabinet ${promptSwitchDocForCab ?: ""} is mapped in ${if (promptSwitchTarget == ReferenceDocType.ASSEMBLY) "Assembly Sheets" else "Plans & Elevations"}. Switch?")
            },
            confirmButton = {
                Button(onClick = {
                    val cab = promptSwitchDocForCab
                    val target = promptSwitchTarget
                    if (cab != null && target != null) {
                        referenceDocType = target
                        mappedPage(target, cab)?.let { referencePage = it }
                    }
                    promptSwitchDocForCab = null
                    promptSwitchTarget = null
                }) { Text("Switch") }
            },
            dismissButton = {
                Button(onClick = {
                    promptSwitchDocForCab = null
                    promptSwitchTarget = null
                }) { Text("Stay") }
            }
        )
    }

    if (cabSkipRow != null) {
        val row = cabSkipRow!!
        val cabs = row.cabinets.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val skippedSet = skippedCabinetMap[selectedDocType.name to row.rowId].orEmpty()
        AlertDialog(
            onDismissRequest = { cabSkipRow = null },
            title = { Text("Skip Cabinets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cabs.forEach { cab ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cab $cab", modifier = Modifier.weight(1f))
                            Button(
                                onClick = {
                                    hardwoodsProgressStore.setCabinetSkipped(
                                        jobFolderName = jobFolderName,
                                        docType = selectedDocType.name,
                                        rowId = row.rowId,
                                        cabinet = cab,
                                        skipped = !skippedSet.contains(cab)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (skippedSet.contains(cab)) {
                                        statusColors.skipBorder.copy(alpha = 0.72f)
                                    } else {
                                        statusColors.skipBg.copy(alpha = 0.62f)
                                    },
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(if (skippedSet.contains(cab)) "Skipped" else "Skip", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { cabSkipRow = null }) { Text("Done") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${job?.folderName ?: jobFolderName} - Hardwoods") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showReferencePane = !showReferencePane }) {
                        Text(if (showReferencePane) "Hide PDF" else "Show PDF")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val cutlistPane: @Composable (Modifier) -> Unit = { firstMod ->
            Column(
                modifier = firstMod
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    items(HardwoodDocType.entries, key = { it.name }) { docType ->
                        val available = documents.any { it.docType == docType }
                        FilterChip(
                            selected = selectedDocType == docType,
                            onClick = { if (available) selectedDocType = docType },
                            label = {
                                Text(
                                    if (available) docType.uiLabel() else "${docType.uiLabel()} (Missing)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            enabled = available
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = listSegment == HardwoodsListSegment.PART_LIST,
                        onClick = { listSegment = HardwoodsListSegment.PART_LIST },
                        label = { Text("Part List") }
                    )
                    FilterChip(
                        selected = listSegment == HardwoodsListSegment.TOTALS,
                        onClick = { listSegment = HardwoodsListSegment.TOTALS },
                        label = { Text("Totals") }
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (selectedDoc == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No metadata for selected cut list", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (listSegment == HardwoodsListSegment.PART_LIST) {
                        val isDoorListDoc = selectedDoc.docType == HardwoodDocType.DOOR_LIST
                        val collapsedPartSections = remember(selectedDoc.docType.name, partSections, collapsedPartSectionsByDoc) {
                            collapsedPartSectionsByDoc[selectedDoc.docType.name]
                                ?: partSections.mapTo(linkedSetOf()) { section ->
                                    "${selectedDoc.docType.name}|${section.material}|${section.pagesLabel}"
                                }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            partSections.forEach { section ->
                                val sectionKey = "${section.material}|${section.pagesLabel}"
                                val sectionStateKey = "${selectedDoc.docType.name}|$sectionKey"
                                val isCollapsed = sectionStateKey in collapsedPartSections
                                val sectionProgress = sectionProgressByKey[sectionKey] ?: HardwoodsSectionProgress(0, 0)
                                stickyHeader(key = "part-section:${selectedDoc.docType.name}:$sectionKey") {
                                    SectionProgressHeader(
                                        title = if (section.pagesLabel.isBlank()) {
                                            section.material
                                        } else {
                                            "${section.material} • Pg ${section.pagesLabel}"
                                        },
                                        itemCount = section.rows.size,
                                        done = sectionProgress.donePieces,
                                        total = sectionProgress.totalPieces,
                                        expanded = !isCollapsed,
                                        onToggleExpanded = {
                                            val updated = if (isCollapsed) {
                                                collapsedPartSections - sectionStateKey
                                            } else {
                                                collapsedPartSections + sectionStateKey
                                            }
                                            collapsedPartSectionsByDoc =
                                                collapsedPartSectionsByDoc + (selectedDoc.docType.name to updated)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (!isCollapsed) {
                                    items(section.rows, key = { it.rowId }) { row ->
                                        val rowUi = rowDisplayMap[row.rowId] ?: return@items
                                        val progress = remember(progressVersion, selectedDoc.docType.name, row.rowId) {
                                            rowProgressMap[selectedDoc.docType.name to row.rowId] ?: HardwoodRowProgress()
                                        }
                                        val qty = row.qty.coerceAtLeast(0)
                                        val skippedCabs = remember(progressVersion, selectedDoc.docType.name, row.rowId) {
                                            skippedCabinetMap[selectedDoc.docType.name to row.rowId].orEmpty()
                                        }
                                        val skippedCabCount = skippedCabs.size
                                        val isHighlighted = highlightedRowId == row.rowId
                                        val widthBand = widthColorBands[rowUi.widthKey] ?: statusColors.notStarted
                                        val onIncrement = remember(row.rowId, qty, progress.doneCount, selectedDoc.docType.name, jobFolderName) {
                                            {
                                                hardwoodsProgressStore.setDoneCount(
                                                    jobFolderName = jobFolderName,
                                                    docType = selectedDoc.docType.name,
                                                    rowId = row.rowId,
                                                    qty = qty,
                                                    doneCount = progress.doneCount + 1
                                                )
                                            }
                                        }
                                        val onDecrement = remember(row.rowId, qty, progress.doneCount, selectedDoc.docType.name, jobFolderName) {
                                            {
                                                hardwoodsProgressStore.setDoneCount(
                                                    jobFolderName = jobFolderName,
                                                    docType = selectedDoc.docType.name,
                                                    rowId = row.rowId,
                                                    qty = qty,
                                                    doneCount = progress.doneCount - 1
                                                )
                                            }
                                        }
                                        val onSkipToggle = remember(row.rowId, progress.skipped, rowUi.isMultiCab, selectedDoc.docType.name, jobFolderName) {
                                            {
                                                if (rowUi.isMultiCab) {
                                                    cabSkipRow = row
                                                } else {
                                                    hardwoodsProgressStore.setSkipped(
                                                        jobFolderName = jobFolderName,
                                                        docType = selectedDoc.docType.name,
                                                        rowId = row.rowId,
                                                        skipped = !progress.skipped
                                                    )
                                                }
                                            }
                                        }
                                        HardwoodsPartRow(
                                            rowUi = rowUi,
                                            qty = qty,
                                            progress = progress,
                                            skippedCabs = skippedCabs,
                                            isHighlighted = isHighlighted,
                                            widthBand = widthBand,
                                            isDoorListDoc = isDoorListDoc,
                                            onIncrement = onIncrement,
                                            onDecrement = onDecrement,
                                            onSkipToggle = onSkipToggle,
                                            onJump = { startRowJump(row) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        HardwoodsTotalsList(
                            sections = buildHardwoodsTotalsSections(totalsLines),
                            docType = selectedDoc.docType,
                            jobFolderName = jobFolderName,
                            progressStore = hardwoodsProgressStore,
                            totalsDoneMap = totalsDoneMap,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        val referencePane: @Composable (Modifier) -> Unit = { secondMod ->
            ReferencePane(
                modifier = secondMod.fillMaxSize(),
                jobRepository = jobRepository,
                jobFolderName = jobFolderName,
                isDarkTheme = isDarkTheme,
                referenceDocType = referenceDocType,
                onReferenceDocTypeChange = { target ->
                    referenceDocType = target
                    val cab = lastJumpCab
                    if (cab != null) {
                        mappedPage(target, cab)?.let { page ->
                            referencePage = page
                        }
                    }
                },
                currentPage = referencePage,
                onCurrentPageChange = { referencePage = it }
            )
        }

        if (showReferencePane) {
            AdaptiveSplitLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                firstContent = if (isLandscape) cutlistPane else referencePane,
                secondContent = if (isLandscape) referencePane else cutlistPane
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                cutlistPane(Modifier.fillMaxSize())
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HardwoodsPartRow(
    rowUi: HardwoodsRowUiModel,
    qty: Int,
    progress: HardwoodRowProgress,
    skippedCabs: Set<String>,
    isHighlighted: Boolean,
    widthBand: Color,
    isDoorListDoc: Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSkipToggle: () -> Unit,
    onJump: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val row = rowUi.row
    val done = progress.doneCount.coerceIn(0, qty.coerceAtLeast(0))
    val skippedCabCount = skippedCabs.size
    val rowState = remember(progress, qty, rowUi.isMultiCab, skippedCabCount, rowUi.normalizedCabs.size) {
        deriveHardwoodsRowState(
            progress = progress,
            qty = qty,
            isMultiCab = rowUi.isMultiCab,
            skippedCabCount = skippedCabCount,
            totalCabs = rowUi.normalizedCabs.size
        )
    }
    val visuals = hardwoodsRowVisualStyle(state = rowState, widthBand = widthBand)
    val statusColors = KKCThemeColors.statusColors
    val completionFlash = remember(rowUi.row.rowId) { Animatable(0f) }
    LaunchedEffect(rowState, done, qty) {
        if (qty > 0 && done == qty && rowState == HardwoodsRowState.COMPLETE) {
            completionFlash.snapTo(0f)
            completionFlash.animateTo(1f, animationSpec = tween(durationMillis = 100))
            completionFlash.animateTo(0f, animationSpec = tween(durationMillis = 200))
        }
    }
    val highlightColor = if (isHighlighted) {
        val animated by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f),
            label = "hardwoodsRowHighlight"
        )
        animated
    } else {
        Color.Transparent
    }
    val baseRowColor = if (isHighlighted) highlightColor else visuals.backgroundTint
    val completionTint = statusColors.completeBorder.copy(alpha = 0.22f * completionFlash.value)
    val rowColor = completionTint.compositeOver(baseRowColor)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .drawBehind {
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1f
                )
            }
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onJump()
                }
            ),
        shape = RoundedCornerShape(6.dp),
        color = rowColor,
        tonalElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(visuals.leftBorderWidth)
                    .fillMaxHeight()
                    .drawBehind {
                        if (rowState == HardwoodsRowState.PARTIAL_SKIP) {
                            drawLine(
                                color = visuals.leftBorderColor,
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = size.width,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
                            )
                        } else {
                            drawRect(color = visuals.leftBorderColor)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(visuals.widthBandColor)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDoorListDoc) {
                            Text(
                                "$qty pcs • ${row.width} x ${row.length}",
                                style = DimensionTextStyle
                            )
                        } else {
                            Text(
                                "${row.width} x ${row.length}",
                                style = DimensionTextStyle
                            )
                            Text(
                                row.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontStyle = FontStyle.Italic,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        if (isDoorListDoc) {
                            "Door Type: ${(row.material ?: row.description).ifBlank { "Door" }}"
                        } else {
                            "Cab(s) ${rowUi.cabDisplayText}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isDoorListDoc) {
                        Text(
                            "Cab(s) ${rowUi.cabDisplayText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (isDoorListDoc) {
                    ProgressPill(
                        done = done,
                        total = qty,
                        state = rowState.asProgressState(),
                        modifier = Modifier
                    )
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onJump()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) { Text("Open Ref", style = MaterialTheme.typography.labelSmall) }
                } else {
                    ProgressPill(
                        done = done,
                        total = qty,
                        state = rowState.asProgressState(),
                        modifier = Modifier
                    )
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDecrement()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = statusColors.bad, contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .widthIn(min = 32.dp)
                    ) { Icon(Icons.Default.Remove, contentDescription = "Done -", modifier = Modifier.size(14.dp)) }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onIncrement()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = statusColors.completeBorder, contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .widthIn(min = 32.dp)
                    ) { Icon(Icons.Default.Add, contentDescription = "Done +", modifier = Modifier.size(14.dp)) }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onJump()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) { Text("Jump", style = MaterialTheme.typography.labelSmall) }
                    if (visuals.skipOn) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSkipToggle()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = statusColors.skipBorder,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            if (rowUi.isMultiCab) {
                                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "$skippedCabCount/${rowUi.normalizedCabs.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text("SKIPPED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSkipToggle()
                            },
                            border = BorderStroke(1.dp, statusColors.skipBorder.copy(alpha = 0.85f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = statusColors.skipBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            Text("Skip", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun HardwoodsRowState.asProgressState(): ProgressState = when (this) {
    HardwoodsRowState.NOT_STARTED -> ProgressState.NOT_STARTED
    HardwoodsRowState.IN_PROGRESS -> ProgressState.IN_PROGRESS
    HardwoodsRowState.COMPLETE -> ProgressState.COMPLETE
    HardwoodsRowState.SKIPPED,
    HardwoodsRowState.PARTIAL_SKIP -> ProgressState.SKIPPED
}

@Composable
private fun ReferencePane(
    modifier: Modifier,
    jobRepository: JobRepository,
    jobFolderName: String,
    isDarkTheme: Boolean,
    referenceDocType: ReferenceDocType,
    onReferenceDocTypeChange: (ReferenceDocType) -> Unit,
    currentPage: Int,
    onCurrentPageChange: (Int) -> Unit
) {
    val cabinetIndex = remember(jobFolderName) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    val docIndex = remember(cabinetIndex, referenceDocType) {
        when (referenceDocType) {
            ReferenceDocType.ASSEMBLY -> cabinetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> cabinetIndex?.documents?.plansElevations
        }
    }
    val pdfFilename = remember(docIndex, referenceDocType, jobFolderName) {
        docIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, referenceDocType)
            ?: ""
    }
    val pdfFile = remember(jobFolderName, pdfFilename, isDarkTheme) {
        if (pdfFilename.isBlank()) null else jobRepository.getJobRootPdfFile(jobFolderName, pdfFilename, preferDarkMode = isDarkTheme)
    }

    ReferencePdfPane(
        modifier = modifier,
        pdfFile = pdfFile,
        currentPage = currentPage,
        onCurrentPageChange = onCurrentPageChange,
        showDocControls = {
            FilterChip(
                selected = referenceDocType == ReferenceDocType.ASSEMBLY,
                onClick = { onReferenceDocTypeChange(ReferenceDocType.ASSEMBLY) },
                label = { Text("Assembly") }
            )
            FilterChip(
                selected = referenceDocType == ReferenceDocType.PLANS_ELEVATIONS,
                onClick = { onReferenceDocTypeChange(ReferenceDocType.PLANS_ELEVATIONS) },
                label = { Text("Plans & Elevations") }
            )
        }
    )
}

@Composable
private fun HardwoodsTotalsList(
    sections: List<HardwoodsTotalsSection>,
    docType: HardwoodDocType,
    jobFolderName: String,
    progressStore: HardwoodsProgressStore,
    totalsDoneMap: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    val statusColors = KKCThemeColors.statusColors
    if (sections.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No totals lines found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sections, key = { "${it.material}|${it.pagesLabel}" }) { section ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(section.material, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("Pg ${section.pagesLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    section.lines.forEach { line ->
                        val done = (totalsDoneMap[line.key] ?: 0).coerceIn(0, line.targetCount)
                        val remaining = (line.targetCount - done).coerceAtLeast(0)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    "${line.width} x ${line.length}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Rips ${line.ripsRaw.ifBlank { "0" }} • T ${line.targetCount} • D $done • R $remaining",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    progressStore.setTotalsRip10Done(
                                        jobFolderName = jobFolderName,
                                        docType = docType.name,
                                        blockIndex = line.blockIndex,
                                        lineIndex = line.lineIndex,
                                        doneCount = done - 1
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = statusColors.bad,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(22.dp)
                            ) { Icon(Icons.Default.Remove, contentDescription = "Totals done -", modifier = Modifier.size(12.dp)) }
                            Button(
                                onClick = {
                                    progressStore.setTotalsRip10Done(
                                        jobFolderName = jobFolderName,
                                        docType = docType.name,
                                        blockIndex = line.blockIndex,
                                        lineIndex = line.lineIndex,
                                        doneCount = done + 1
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = statusColors.completeBorder,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(22.dp)
                            ) { Icon(Icons.Default.Add, contentDescription = "Totals done +", modifier = Modifier.size(12.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeWidthForGrouping(width: String): String {
    val parsed = parseDimensionForSort(width)
    if (parsed != null) return "%.4f".format(parsed)
    return width.trim().lowercase()
}

private fun parseDimensionForSort(raw: String): Double? {
    val text = raw.trim().replace("\"", "")
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return it }
    val mixedMatch = Regex("""^(-?\d+)\s+(\d+)\s*/\s*(\d+)$""").matchEntire(text)
    if (mixedMatch != null) {
        val whole = mixedMatch.groupValues[1].toDoubleOrNull() ?: return null
        val num = mixedMatch.groupValues[2].toDoubleOrNull() ?: return null
        val den = mixedMatch.groupValues[3].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        val frac = num / den
        return if (whole >= 0) whole + frac else whole - frac
    }
    val fracMatch = Regex("""^(-?\d+)\s*/\s*(\d+)$""").matchEntire(text)
    if (fracMatch != null) {
        val num = fracMatch.groupValues[1].toDoubleOrNull() ?: return null
        val den = fracMatch.groupValues[2].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        return num / den
    }
    val dashMixed = Regex("""^(-?\d+)-(\d+)\s*/\s*(\d+)$""").matchEntire(text)
    if (dashMixed != null) {
        val whole = dashMixed.groupValues[1].toDoubleOrNull() ?: return null
        val num = dashMixed.groupValues[2].toDoubleOrNull() ?: return null
        val den = dashMixed.groupValues[3].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        val frac = num / den
        return if (whole >= 0) whole + frac else whole - frac
    }
    return Regex("""-?\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull()
}

private fun buildHardwoodsTotalsLines(
    docType: HardwoodDocType,
    totals: List<HardwoodTotalsBlock>
): List<HardwoodsTotalsLine> {
    if (totals.isEmpty()) return emptyList()
    val lines = mutableListOf<HardwoodsTotalsLine>()
    totals.forEachIndexed { blockIndex, block ->
        val pages = block.sourcePages.ifEmpty { listOf(block.page) }.distinct().sorted()
        val material = block.material?.takeIf { it.isNotBlank() } ?: "Material ${blockIndex + 1}"
        val rowCount = max(block.widthValues.size, max(block.lengthValues.size, block.ripsValues.size))
        for (rowIndex in 0 until rowCount) {
            val width = block.widthValues.getOrNull(rowIndex).orEmpty()
            val length = block.lengthValues.getOrNull(rowIndex).orEmpty()
            val ripsRaw = block.ripsValues.getOrNull(rowIndex).orEmpty()
            if (width.isBlank() && length.isBlank() && ripsRaw.isBlank()) continue
            val ripsLinearFeet = parseLinearFeetValue(ripsRaw)
            val target = ceil(ripsLinearFeet / 10.0).toInt().coerceAtLeast(0)
            val key = "${docType.name}|$blockIndex|$rowIndex"
            lines += HardwoodsTotalsLine(
                key = key,
                blockIndex = blockIndex,
                lineIndex = rowIndex,
                material = material,
                pagesLabel = pages.joinToString(","),
                width = width.ifBlank { "-" },
                length = length.ifBlank { "-" },
                ripsRaw = ripsRaw,
                ripsLinearFeet = ripsLinearFeet,
                targetCount = target
            )
        }
    }
    return lines
}

private fun buildHardwoodsTotalsSections(lines: List<HardwoodsTotalsLine>): List<HardwoodsTotalsSection> {
    if (lines.isEmpty()) return emptyList()
    return lines
        .groupBy { it.material to it.pagesLabel }
        .map { (key, groupedLines) ->
            HardwoodsTotalsSection(
                material = key.first,
                pagesLabel = key.second,
                lines = groupedLines.sortedBy { it.lineIndex }
            )
        }
}

private fun buildHardwoodsPartSections(
    rows: List<HardwoodCutlistRow>,
    totals: List<HardwoodTotalsBlock>,
    sortMode: HardwoodsRowSortMode
): List<HardwoodsPartSection> {
    if (rows.isEmpty()) return emptyList()
    val rowsWithMaterial = rows.filter { !it.material.isNullOrBlank() }
    if (rowsWithMaterial.size == rows.size) {
        val grouped = LinkedHashMap<String, MutableList<HardwoodCutlistRow>>()
        rows.sortedWith(cutlistOrderComparator()).forEach { row ->
            val material = row.material?.trim().orEmpty().ifBlank { "Unassigned" }
            grouped.getOrPut(material) { mutableListOf() }.add(row)
        }
        return grouped.entries.map { (material, groupedRows) ->
            HardwoodsPartSection(
                material = material,
                pagesLabel = groupedRows.map { it.page }.distinct().sorted().joinToString(","),
                rows = groupedRows.sortedFor(sortMode)
            )
        }
    }
    if (totals.isEmpty()) {
        return listOf(
            HardwoodsPartSection(
                material = "Parts",
                pagesLabel = "",
                rows = rows.sortedFor(sortMode)
            )
        )
    }

    data class MaterialSpec(
        val material: String,
        val pagesLabel: String,
        val sourcePages: Set<Int>,
        val pairKeys: Set<String>
    )

    val specs = totals.mapIndexed { index, block ->
        val pages = block.sourcePages.ifEmpty { listOf(block.page) }.distinct().sorted()
        val pairCount = minOf(block.widthValues.size, block.lengthValues.size)
        val pairs = buildSet {
            for (i in 0 until pairCount) {
                val pair = dimensionPairKey(block.widthValues[i], block.lengthValues[i])
                if (pair.isNotEmpty()) add(pair)
            }
        }
        MaterialSpec(
            material = block.material?.takeIf { it.isNotBlank() } ?: "Material ${index + 1}",
            pagesLabel = pages.joinToString(","),
            sourcePages = pages.toSet(),
            pairKeys = pairs
        )
    }

    val sectionRows = LinkedHashMap<String, MutableList<HardwoodCutlistRow>>()
    val sectionPages = LinkedHashMap<String, String>()
    specs.forEach { spec ->
        sectionRows.putIfAbsent(spec.material, mutableListOf())
        sectionPages.putIfAbsent(spec.material, spec.pagesLabel)
    }
    val unassigned = mutableListOf<HardwoodCutlistRow>()
    var previousMaterial: String? = null

    val orderedRows = rows.sortedWith(cutlistOrderComparator())
    orderedRows.forEach { row ->
        val pair = dimensionPairKey(row.width, row.length)
        val pairMatches = specs.filter { pair.isNotEmpty() && pair in it.pairKeys }
        val pageMatches = specs.filter { row.page in it.sourcePages }

        val chosenMaterial = when {
            pairMatches.size == 1 -> pairMatches.first().material
            pairMatches.size > 1 -> pairMatches.firstOrNull { it.material == previousMaterial }?.material ?: pairMatches.first().material
            pageMatches.size == 1 -> pageMatches.first().material
            pageMatches.size > 1 -> pageMatches.firstOrNull { it.material == previousMaterial }?.material ?: pageMatches.first().material
            previousMaterial != null -> previousMaterial
            else -> null
        }

        if (chosenMaterial == null) {
            unassigned += row
        } else {
            sectionRows.getOrPut(chosenMaterial) { mutableListOf() }.add(row)
            previousMaterial = chosenMaterial
        }
    }

    val sections = mutableListOf<HardwoodsPartSection>()
    specs.forEach { spec ->
        val rowsForSection = sectionRows[spec.material].orEmpty()
        if (rowsForSection.isNotEmpty()) {
            sections += HardwoodsPartSection(
                material = spec.material,
                pagesLabel = sectionPages[spec.material].orEmpty(),
                rows = rowsForSection.sortedFor(sortMode)
            )
        }
    }

    if (unassigned.isNotEmpty()) {
        sections += HardwoodsPartSection(
            material = "Unassigned",
            pagesLabel = "",
            rows = unassigned.sortedFor(sortMode)
        )
    }

    return if (sections.isEmpty()) {
        listOf(
            HardwoodsPartSection(
                material = "Parts",
                pagesLabel = "",
                rows = rows.sortedFor(sortMode)
            )
        )
    } else {
        sections
    }
}

private fun dimensionPairKey(width: String, length: String): String {
    val w = dimensionKey(width)
    val l = dimensionKey(length)
    if (w.isEmpty() && l.isEmpty()) return ""
    return "$w|$l"
}

private fun dimensionKey(raw: String): String {
    val parsed = parseDimensionForSort(raw)
    if (parsed != null) return "%.4f".format(parsed)
    return raw.trim().lowercase()
}

private fun parseLinearFeetValue(raw: String): Double {
    return parseDimensionForSort(raw)?.coerceAtLeast(0.0) ?: 0.0
}
