package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.filterDoorCutRowsToSheets
import com.kkc.sheettracker.data.loadHardwoodsCutlistIndexRawJson
import com.kkc.sheettracker.data.parseDoorCutUnitTypeMetadata
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.HardwoodRowRevisionState
import com.kkc.sheettracker.data.models.HardwoodTotalsBlock
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.components.AdaptiveSplitLayout
import com.kkc.sheettracker.ui.components.ChangedBadge
import com.kkc.sheettracker.ui.components.ProgressPill
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.components.RevisionBadge
import com.kkc.sheettracker.ui.components.SectionProgressHeader
import com.kkc.sheettracker.ui.theme.DimensionTextStyle
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewer
import com.kkc.sheettracker.ui.viewer.UnifiedVirtualPageMapping
import com.kkc.sheettracker.ui.viewer.UnifiedVirtualPageSource
import com.kkc.sheettracker.ui.viewer.buildPlanViewLabelsFromPageToRoom
import com.kkc.sheettracker.ui.viewer.extractRoomDisplayName
import com.kkc.sheettracker.ui.viewer.sanitizeVirtualAssemblyData
import com.kkc.sheettracker.viewer3d.Model3DPane
import com.kkc.sheettracker.viewer3d.ViewerServer
import com.kkc.sheettracker.data.loadAdminBoardStock
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

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

private enum class HardwoodsJumpTarget {
    ASSEMBLY,
    PLANS,
    THREE_D
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HardwoodsWorkspaceScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsRepository: HardwoodsRepository,
    hardwoodsProgressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    jobFolderName: String,
    initialDocType: HardwoodDocType,
    initialRowId: String?,
    isDarkTheme: Boolean,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onOpenThreeDTarget: (cabinet: String?, assemblyPage: Int?, plansPage: Int?, room: String?) -> Unit,
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
    val availableDocuments = remember(documents, jobFolderName, isDarkTheme) {
        documents.filter { doc ->
            doc.pdfFilename.isNotBlank() &&
                jobRepository.getJobRootPdfFile(
                    jobFolderName = jobFolderName,
                    pdfFilename = doc.pdfFilename,
                    preferDarkMode = isDarkTheme
                ) != null
        }
    }
    val isRipCutEntry = initialRowId == HARDWOODS_RIP_CUT_LIST_ROW_ID
    val isDoorPanelsEntry = initialRowId == HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
    var selectedDocType by remember(jobFolderName) { mutableStateOf(initialDocType) }

    val availableDocTypes = remember(availableDocuments) { availableDocuments.map { it.docType }.toSet() }
    if (selectedDocType !in availableDocTypes && availableDocTypes.isNotEmpty()) {
        selectedDocType = availableDocTypes.first()
    }

    val listState = rememberLazyListState()
    val selectedDoc = remember(availableDocuments, selectedDocType) { availableDocuments.firstOrNull { it.docType == selectedDocType } }
    val selectedDocName = selectedDoc?.docType?.name.orEmpty()
    val rawRows = selectedDoc?.rows.orEmpty()
    val totals = selectedDoc?.totals.orEmpty()
    var showRipCutList by rememberSaveable(jobFolderName) { mutableStateOf(isRipCutEntry) }
    val useDoorPanelsSheetFilter = rememberSaveable(jobFolderName) {
        mutableStateOf(isDoorPanelsEntry)
    }
    var doorPanelGroupMode by rememberSaveable(jobFolderName) {
        mutableStateOf(DoorPanelGroupMode.ByMaterial)
    }
    val rows = remember(
        rawRows,
        selectedDocType,
        useDoorPanelsSheetFilter.value,
        scanState.snapshot.basePath,
        jobFolderName
    ) {
        applyDoorPanelsSheetFilter(
            rows = rawRows,
            selectedDocType = selectedDocType,
            enabled = useDoorPanelsSheetFilter.value,
            rawCutlistIndexJson = loadHardwoodsCutlistIndexRawJson(
                basePath = scanState.snapshot.basePath,
                jobFolderName = jobFolderName
            )
        )
    }
    var showChangedOnly by rememberSaveable(jobFolderName) { mutableStateOf(false) }
    LaunchedEffect(jobFolderName, initialDocType, initialRowId) {
        when {
            isDoorPanelsEntry -> {
                // Door Panels must always enter on DOOR_CUT_LIST with sheet filter on.
                selectedDocType = initialDocType
                showRipCutList = false
                showChangedOnly = false
                useDoorPanelsSheetFilter.value = true
            }
            isRipCutEntry -> {
                selectedDocType = initialDocType
                showRipCutList = true
                showChangedOnly = false
                useDoorPanelsSheetFilter.value = false
            }
            else -> {
                if (useDoorPanelsSheetFilter.value) {
                    useDoorPanelsSheetFilter.value = false
                }
            }
        }
    }
    val isDoorPanelsActive = useDoorPanelsSheetFilter.value
    val rowProgressMap = remember(progressVersion, jobFolderName) { hardwoodsProgressStore.getRowProgressMap(jobFolderName) }
    val rowRevisionStateMap = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        hardwoodsRepository.getRowRevisionStates(jobFolderName)
    }
    var highlightedRowId by remember(jobFolderName, initialRowId) {
        mutableStateOf(
            initialRowId.takeUnless {
                it == HARDWOODS_RIP_CUT_LIST_ROW_ID || it == HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
            }
        )
    }
    val boardStockRows = remember(scanState.snapshot.basePath, jobFolderName, job?.index, rowProgressMap) {
        applySkippedPartRowsToBoardStockRows(
            rows = buildBoardStockRows(scanState.snapshot.basePath, jobFolderName, job?.index),
            index = job?.index,
            rowProgressMap = rowProgressMap
        )
    }
    val adminBoardStock = remember(scanState.snapshot.basePath, jobFolderName) {
        loadAdminBoardStock(baseDir = File(scanState.snapshot.basePath), jobFolderName = jobFolderName)
    }
    val totalsDoneMap = remember(progressVersion, jobFolderName) {
        hardwoodsProgressStore.getTotalsRip10DoneMap(jobFolderName)
    }
    val pendingChangedByDoc = remember(availableDocuments, rowRevisionStateMap, rowProgressMap) {
        availableDocuments.associate { doc ->
            val pending = doc.rows.filter { row ->
                val state = rowRevisionStateMap[doc.docType.name to row.rowId] ?: return@filter false
                if (state.latestRevision <= 0) return@filter false
                val qty = row.qty.coerceAtLeast(0)
                val done = rowProgressMap[doc.docType.name to row.rowId]?.doneCount ?: 0
                qty > 0 && done < qty
            }.map { it.rowId }.toSet()
            doc.docType to pending
        }
    }
    val hasAnyPendingChanged = remember(pendingChangedByDoc) {
        pendingChangedByDoc.values.any { it.isNotEmpty() }
    }
    val selectedDocPendingChanged = pendingChangedByDoc[selectedDocType].orEmpty()

    LaunchedEffect(hasAnyPendingChanged) {
        if (!hasAnyPendingChanged && showChangedOnly) {
            showChangedOnly = false
        }
    }

    var referenceDocType by remember(jobFolderName) {
        val stored = prefs.getString("hardwoods_last_ref_$jobFolderName", ReferenceDocType.ASSEMBLY.name)
        mutableStateOf(runCatching { ReferenceDocType.valueOf(stored ?: ReferenceDocType.ASSEMBLY.name) }.getOrDefault(ReferenceDocType.ASSEMBLY))
    }
    var jumpTarget by remember(jobFolderName) {
        mutableStateOf(
            if (referenceDocType == ReferenceDocType.PLANS_ELEVATIONS) HardwoodsJumpTarget.PLANS
            else HardwoodsJumpTarget.ASSEMBLY
        )
    }
    var serverPort by remember { mutableIntStateOf(0) }
    var viewerServerError by remember { mutableStateOf<String?>(null) }
    var detectedRoom by rememberSaveable(jobFolderName) { mutableStateOf<String?>(null) }

    val viewerBasePath = scanState.snapshot.basePath
    DisposableEffect(viewerBasePath, jobFolderName) {
        if (viewerBasePath.isBlank()) {
            serverPort = 0
            viewerServerError = "Base path is blank"
            return@DisposableEffect onDispose {}
        }
        val server = ViewerServer(context, File(viewerBasePath))
        val result = server.startWithRetry()
        serverPort = result.port
        viewerServerError = result.error
        onDispose { server.stop() }
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

    val resumePrefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val referenceResumeKey = remember(jobFolderName) { "hardwoods_reference_page_v1_${jobFolderName}" }
    var referencePage by remember(jobFolderName) { mutableIntStateOf(resumePrefs.getInt(referenceResumeKey, 1).coerceAtLeast(1)) }
    LaunchedEffect(referencePage, referenceResumeKey) {
        resumePrefs.edit().putInt(referenceResumeKey, referencePage).apply()
    }
    val cabinetIndex = remember(jobFolderName) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    val assemblyCabinetToPages = remember(cabinetIndex) {
        cabinetIndex?.documents?.assembly?.virtualCombined?.cabinetToPages
            ?.takeIf { it.isNotEmpty() }
            ?: cabinetIndex?.documents?.assembly?.cabinetToPages.orEmpty()
    }
    val assemblyPageDetails = remember(cabinetIndex) {
        cabinetIndex?.documents?.assembly?.virtualCombined?.pageDetails
            ?.takeIf { it.isNotEmpty() }
            ?: cabinetIndex?.documents?.assembly?.pageDetails.orEmpty()
    }

    fun mappedPage(docType: ReferenceDocType, cab: String): Int? {
        val map = when (docType) {
            ReferenceDocType.ASSEMBLY -> assemblyCabinetToPages
            ReferenceDocType.PLANS_ELEVATIONS -> cabinetIndex?.documents?.plansElevations?.cabinetToPages
            ReferenceDocType.DELIVERY_SHEETS -> null
        }
        return map?.get(cab)?.firstOrNull()
    }

    fun normalizeRoomFolder(roomText: String?): String? {
        val raw = roomText?.let {
            Regex("""\(([^)]+)\)""").find(it)?.groupValues?.get(1)?.uppercase()
                ?: it.uppercase().takeIf { s -> s.isNotBlank() }
        } ?: return null
        return raw.replace(Regex("""[/\\:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    // Cabinet number → normalized room name, built once from the assembly page index.
    val cabinetToRoom = remember(assemblyCabinetToPages, assemblyPageDetails) {
        assemblyCabinetToPages.mapValues { (_, pages) ->
            pages.firstOrNull()
                ?.let { page -> normalizeRoomFolder(assemblyPageDetails[page.toString()]?.room) }
                ?: ""
        }
    }
    val partSections = remember(rows, totals, isDoorPanelsActive, doorPanelGroupMode, cabinetToRoom) {
        when {
            isDoorPanelsActive && doorPanelGroupMode == DoorPanelGroupMode.ByCabinet ->
                buildCabinetSections(rows)
            isDoorPanelsActive && doorPanelGroupMode == DoorPanelGroupMode.ByRoom ->
                buildRoomSections(rows, cabinetToRoom)
            else ->
                buildHardwoodsPartSections(rows, totals, HardwoodsRowSortMode.CutlistOrder)
        }
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
    LaunchedEffect(initialRowId, selectedDocType, partSections.size, displayRows.size) {
        val target = initialRowId
            ?.takeUnless { it == HARDWOODS_RIP_CUT_LIST_ROW_ID || it == HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID }
            ?: return@LaunchedEffect
        val idx = lazyIndexByRowId[target]
        if (idx != null) {
            listState.animateScrollToItem(idx)
            highlightedRowId = target
            delay(1800)
            if (highlightedRowId == target) highlightedRowId = null
        }
    }

    fun firstAlphabeticalRoomFromIndex(): Pair<String, Int>? {
        return assemblyPageDetails
            .mapNotNull { (pageKey, detail) ->
                val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                val room = normalizeRoomFolder(detail.room) ?: return@mapNotNull null
                room to page
            }
            .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            .firstOrNull()
    }

    fun roomForCurrentReferencePage(): Pair<String, Int>? {
        if (referenceDocType == ReferenceDocType.ASSEMBLY) {
            val room = normalizeRoomFolder(assemblyPageDetails[referencePage.toString()]?.room)
            if (room != null) return room to referencePage
        }
        if (referenceDocType == ReferenceDocType.PLANS_ELEVATIONS) {
            val cab = cabinetIndex?.documents?.plansElevations?.cabinetToPages
                ?.entries
                ?.firstOrNull { it.value.contains(referencePage) }
                ?.key
            val assemblyPage = cab?.let { mappedPage(ReferenceDocType.ASSEMBLY, it) }
            val room = assemblyPage?.let { page ->
                normalizeRoomFolder(assemblyPageDetails[page.toString()]?.room)
            }
            if (room != null) return room to assemblyPage
        }
        return null
    }

    fun resolveRoomDae(room: String?): File? {
        if (room == null || viewerBasePath.isBlank()) return null
        val roomDir = File("$viewerBasePath/$jobFolderName/3D/$room")
        if (!roomDir.isDirectory) return null
        val preferred = listOf("3d.dae", "3D.dae")
            .map { File(roomDir, it) }
            .firstOrNull { it.exists() && it.isFile }
        if (preferred != null) return preferred
        return roomDir.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("dae", ignoreCase = true) }
    }

    fun openIn3DApp(room: String?) {
        val daeFile = resolveRoomDae(room) ?: return
        val oldPolicy = android.os.StrictMode.getVmPolicy()
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.fromFile(daeFile), "application/octet-stream")
                setPackage("com.anandmuralidhar.assimpandroid")
            })
        } finally {
            android.os.StrictMode.setVmPolicy(oldPolicy)
        }
    }

    data class ThreeDTarget(
        val cabinet: String?,
        val assemblyPage: Int?,
        val plansPage: Int?,
        val room: String?
    )

    fun resolveThreeDTarget(cabinetHint: String? = lastJumpCab, roomHint: String? = detectedRoom): ThreeDTarget {
        val cabinet = cabinetHint?.trim()?.takeIf { it.isNotBlank() }
        val assemblyTarget = cabinet?.let { mappedPage(ReferenceDocType.ASSEMBLY, it) }
        val plansTarget = cabinet?.let { mappedPage(ReferenceDocType.PLANS_ELEVATIONS, it) }
        val fallbackRoom = roomForCurrentReferencePage()?.first ?: firstAlphabeticalRoomFromIndex()?.first
        val room = roomHint ?: assemblyTarget?.let { page ->
            normalizeRoomFolder(cabinetIndex?.documents?.assembly?.pageDetails?.get(page.toString())?.room)
        } ?: fallbackRoom
        val fallbackAssemblyPage = roomForCurrentReferencePage()?.second ?: firstAlphabeticalRoomFromIndex()?.second
        return ThreeDTarget(
            cabinet = cabinet,
            assemblyPage = assemblyTarget ?: fallbackAssemblyPage,
            plansPage = plansTarget,
            room = room
        )
    }

    suspend fun jumpToCab(cab: String) {
        val normalizedCab = cab.trim()
        if (normalizedCab.isBlank()) {
            snackbar.showSnackbar("No cabinet mapping on this row")
            return
        }
        lastJumpCab = normalizedCab
        if (jumpTarget != HardwoodsJumpTarget.THREE_D) {
            showReferencePane = true
        }
        if (jumpTarget == HardwoodsJumpTarget.THREE_D) {
            val assemblyTarget = mappedPage(ReferenceDocType.ASSEMBLY, normalizedCab)
            val plansTarget = mappedPage(ReferenceDocType.PLANS_ELEVATIONS, normalizedCab)
            if (assemblyTarget == null && plansTarget == null) {
                snackbar.showSnackbar("Not found in Assembly or Plans/Elevations for cabinet $normalizedCab")
                return
            }
            val room = assemblyTarget?.let { page ->
                normalizeRoomFolder(cabinetIndex?.documents?.assembly?.pageDetails?.get(page.toString())?.room)
            } ?: roomForCurrentReferencePage()?.first
                ?: firstAlphabeticalRoomFromIndex()?.first
            detectedRoom = room
            when (referenceDocType) {
                ReferenceDocType.ASSEMBLY -> assemblyTarget?.let { referencePage = it }
                ReferenceDocType.PLANS_ELEVATIONS -> plansTarget?.let { referencePage = it }
                ReferenceDocType.DELIVERY_SHEETS -> Unit
            }
            showReferencePane = true
            return
        }
        val current = mappedPage(referenceDocType, normalizedCab)
        if (current != null) {
            referencePage = current
            return
        }
        val fallbackType = if (referenceDocType == ReferenceDocType.ASSEMBLY) {
            ReferenceDocType.PLANS_ELEVATIONS
        } else {
            ReferenceDocType.ASSEMBLY
        }
        val fallback = mappedPage(fallbackType, normalizedCab)
        if (fallback != null) {
            promptSwitchDocForCab = normalizedCab
            promptSwitchTarget = fallbackType
        } else {
            snackbar.showSnackbar("Not found in Assembly or Plans/Elevations for cabinet $normalizedCab")
        }
    }

    LaunchedEffect(jumpTarget, referenceDocType, referencePage) {
        if (jumpTarget == HardwoodsJumpTarget.THREE_D && detectedRoom.isNullOrBlank()) {
            val fallback = roomForCurrentReferencePage() ?: firstAlphabeticalRoomFromIndex()
            detectedRoom = fallback?.first
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
                    val clockInJob = job
                    if (clockInJob != null) {
                        Button(
                            onClick = { onClockIn(clockInJob.jobNumber, clockInJob.jobName) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF38A169),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                if (isClockedInHere) "● CLOCKED IN" else "CLOCK IN",
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
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
                    items(HardwoodDocType.entries.filter { it in availableDocTypes }, key = { it.name }) { docType ->
                        FilterChip(
                            selected = !showRipCutList && !showChangedOnly && selectedDocType == docType,
                            onClick = {
                                selectedDocType = docType
                                showRipCutList = false
                                showChangedOnly = false
                            },
                            label = {
                                Text(
                                    docType.uiLabel(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                    item(key = "rip-cut-list") {
                        FilterChip(
                            selected = showRipCutList,
                            onClick = {
                                showRipCutList = true
                                showChangedOnly = false
                            },
                            label = { Text("Rip Cut List") }
                        )
                    }
                    if (hasAnyPendingChanged) {
                        item(key = "changed-parts") {
                            FilterChip(
                                selected = !showRipCutList && showChangedOnly,
                                onClick = {
                                    showRipCutList = false
                                    showChangedOnly = true
                                    if (selectedDocPendingChanged.isEmpty()) {
                                        val firstDocWithPending = pendingChangedByDoc
                                            .entries
                                            .firstOrNull { it.value.isNotEmpty() }
                                            ?.key
                                        if (firstDocWithPending != null) {
                                            selectedDocType = firstDocWithPending
                                        }
                                    }
                                },
                                label = { Text("CHANGED") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f),
                                    selectedLabelColor = MaterialTheme.colorScheme.tertiary,
                                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                                    labelColor = MaterialTheme.colorScheme.tertiary
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isDoorPanelsActive) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Group by:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                        DoorPanelGroupMode.entries.forEach { mode ->
                            FilterChip(
                                selected = doorPanelGroupMode == mode,
                                onClick = { doorPanelGroupMode = mode },
                                label = {
                                    Text(
                                        when (mode) {
                                            DoorPanelGroupMode.ByMaterial -> "Material"
                                            DoorPanelGroupMode.ByCabinet -> "Cabinet #"
                                            DoorPanelGroupMode.ByRoom -> "Room"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (showRipCutList) {
                    HardwoodsBoardStockList(
                        sections = buildBoardStockSourceSections(boardStockRows),
                        adminItems = adminBoardStock,
                        jobFolderName = jobFolderName,
                        progressStore = hardwoodsProgressStore,
                        totalsDoneMap = totalsDoneMap,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (selectedDoc == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No metadata for selected cut list", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val isDoorListDoc = selectedDoc.docType == HardwoodDocType.DOOR_LIST
                    val collapsedPartSections = remember(selectedDoc.docType.name, partSections, collapsedPartSectionsByDoc) {
                        collapsedPartSectionsByDoc[selectedDoc.docType.name]
                            ?: partSections.mapTo(linkedSetOf()) { section ->
                                "${selectedDoc.docType.name}|${section.material}"
                            }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        partSections.forEach { section ->
                            val sectionRows = if (showChangedOnly) {
                                section.rows.filter { row ->
                                    row.rowId in selectedDocPendingChanged
                                }
                            } else {
                                section.rows
                            }
                            if (sectionRows.isEmpty()) {
                                return@forEach
                            }
                            val sectionKey = section.material
                            val sectionStateKey = "${selectedDoc.docType.name}|$sectionKey"
                            val isCollapsed = sectionStateKey in collapsedPartSections
                            val sectionProgress = run {
                                val totalPieces = sectionRows.sumOf { row ->
                                    val qty = row.qty.coerceAtLeast(0)
                                    val skipped = rowProgressMap[selectedDoc.docType.name to row.rowId]?.skipped == true
                                    if (skipped) 0 else qty
                                }
                                val donePieces = sectionRows.sumOf { row ->
                                    if (rowProgressMap[selectedDoc.docType.name to row.rowId]?.skipped == true) return@sumOf 0
                                    val done = rowProgressMap[selectedDoc.docType.name to row.rowId]?.doneCount ?: 0
                                    done.coerceIn(0, row.qty.coerceAtLeast(0))
                                }
                                HardwoodsSectionProgress(donePieces = donePieces, totalPieces = totalPieces)
                            }
                            val isNailerDoc = selectedDoc.docType == HardwoodDocType.NAILER_CUT_LIST
                            val sectionAllSkipped = sectionRows.all { row ->
                                (rowProgressMap[selectedDoc.docType.name to row.rowId]?.skipped == true)
                            }
                            stickyHeader(key = "part-section:${selectedDoc.docType.name}:$sectionKey") {
                                SectionProgressHeader(
                                    title = section.material,
                                    itemCount = section.rows.size,
                                    done = sectionProgress.donePieces,
                                    total = sectionProgress.totalPieces,
                                    dimmed = sectionAllSkipped,
                                    skipped = sectionAllSkipped,
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
                                    headerActions = if (isNailerDoc) {
                                        {
                                            MaterialSkipPill(
                                                skipped = sectionAllSkipped,
                                                onClick = {
                                                    val nextSkipped = !sectionAllSkipped
                                                    sectionRows.forEach { row ->
                                                        hardwoodsProgressStore.setSkipped(
                                                            jobFolderName = jobFolderName,
                                                            docType = selectedDoc.docType.name,
                                                            rowId = row.rowId,
                                                            skipped = nextSkipped
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (!isCollapsed) {
                                items(sectionRows, key = { it.rowId }) { row ->
                                    val rowUi = rowDisplayMap[row.rowId] ?: return@items
                                    val progress = remember(progressVersion, selectedDoc.docType.name, row.rowId) {
                                        rowProgressMap[selectedDoc.docType.name to row.rowId] ?: HardwoodRowProgress()
                                    }
                                    val qty = row.qty.coerceAtLeast(0)
                                    val skippedCabs = remember(progressVersion, selectedDoc.docType.name, row.rowId) {
                                        skippedCabinetMap[selectedDoc.docType.name to row.rowId].orEmpty()
                                    }
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
                                        revisionState = rowRevisionStateMap[selectedDoc.docType.name to row.rowId],
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
                jumpTarget = jumpTarget,
                onReferenceDocTypeChange = { target ->
                    referenceDocType = target
                    jumpTarget = if (target == ReferenceDocType.PLANS_ELEVATIONS) {
                        HardwoodsJumpTarget.PLANS
                    } else {
                        HardwoodsJumpTarget.ASSEMBLY
                    }
                    val cab = lastJumpCab
                    if (cab != null) {
                        mappedPage(target, cab)?.let { page ->
                            referencePage = page
                        }
                    }
                },
                onJumpTargetChange = { target ->
                    jumpTarget = target
                    if (target == HardwoodsJumpTarget.THREE_D) {
                        val cab = lastJumpCab
                        if (cab != null) {
                            scope.launch { jumpToCab(cab) }
                        } else {
                            scope.launch {
                                val roomTarget = roomForCurrentReferencePage() ?: firstAlphabeticalRoomFromIndex()
                                if (roomTarget != null) {
                                    detectedRoom = roomTarget.first
                                    if (referenceDocType == ReferenceDocType.ASSEMBLY) {
                                        referencePage = roomTarget.second
                                    }
                                } else {
                                    snackbar.showSnackbar("No room model mapping found for this job.")
                                }
                            }
                        }
                    }
                },
                currentPage = referencePage,
                onCurrentPageChange = { referencePage = it },
                roomName = detectedRoom,
                serverPort = serverPort,
                serverError = viewerServerError,
                onThreeDFullScreen = {
                    scope.launch {
                        val target = resolveThreeDTarget()
                        if (target.room == null) {
                            snackbar.showSnackbar("No room model mapping found for this job.")
                        } else {
                            onOpenThreeDTarget(
                                target.cabinet,
                                target.assemblyPage,
                                target.plansPage,
                                target.room
                            )
                        }
                    }
                },
                onOpenIn3DApp = { openIn3DApp(detectedRoom ?: resolveThreeDTarget().room) }
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
    revisionState: HardwoodRowRevisionState?,
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
    val isChangedPendingRecut = revisionState?.changedPendingRecut == true && done < qty.coerceAtLeast(0)
    val changedTint = if (isChangedPendingRecut) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }
    val rowColor = completionTint.compositeOver(changedTint.compositeOver(baseRowColor))
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
                        if (revisionState != null && revisionState.latestRevision > 0) {
                            RevisionBadge(
                                state = revisionState,
                                isChangedPendingRecut = isChangedPendingRecut
                            )
                        }
                        if (isChangedPendingRecut) {
                            ChangedBadge()
                        }
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
                        skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f),
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
                    ProgressPill(
                        done = done,
                        total = qty,
                        state = rowState.asProgressState(),
                        skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f),
                        modifier = Modifier
                    )
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
                    ) { Text("View", style = MaterialTheme.typography.labelSmall) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferencePane(
    modifier: Modifier,
    jobRepository: JobRepository,
    jobFolderName: String,
    isDarkTheme: Boolean,
    referenceDocType: ReferenceDocType,
    jumpTarget: HardwoodsJumpTarget,
    onReferenceDocTypeChange: (ReferenceDocType) -> Unit,
    onJumpTargetChange: (HardwoodsJumpTarget) -> Unit,
    currentPage: Int,
    onCurrentPageChange: (Int) -> Unit,
    roomName: String?,
    serverPort: Int,
    serverError: String?,
    onThreeDFullScreen: () -> Unit,
    onOpenIn3DApp: () -> Unit
) {
    val cabinetIndex = remember(jobFolderName) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    val hasAssemblyReference = remember(jobFolderName) {
        jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY)
    }
    val hasPlansReference = remember(jobFolderName) {
        jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
    }
    val hasThreeDAssets = remember(jobFolderName) {
        jobRepository.hasThreeDAssets(jobFolderName)
    }
    val docIndex = remember(cabinetIndex, referenceDocType) {
        when (referenceDocType) {
            ReferenceDocType.ASSEMBLY -> cabinetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> cabinetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
        }
    }
    val assemblyVirtualRawMap = remember(cabinetIndex) {
        cabinetIndex?.documents?.assembly?.virtualCombined?.virtualPageToSource
            ?.mapNotNull { (virtualPageKey, source) ->
                val page = virtualPageKey.toIntOrNull() ?: return@mapNotNull null
                if (page <= 0) return@mapNotNull null
                page to UnifiedVirtualPageSource(
                    pdfFilename = source.pdfFilename,
                    page = source.page,
                    cabinet = source.cabinet,
                    sourceVariant = source.variant
                )
            }
            ?.toMap()
            .orEmpty()
    }
    val assemblyVirtualTotalPages = remember(cabinetIndex) {
        (cabinetIndex?.documents?.assembly?.virtualCombined?.totalVirtualPages ?: 0).coerceAtLeast(0)
    }
    val assemblyVirtualSanitized = remember(
        referenceDocType,
        assemblyVirtualTotalPages,
        assemblyVirtualRawMap,
        cabinetIndex,
        docIndex
    ) {
        sanitizeVirtualAssemblyData(
            totalVirtualPages = assemblyVirtualTotalPages,
            defaultPdfFilename = docIndex?.pdfFilename?.takeIf { it.isNotBlank() }
                ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY).orEmpty(),
            sourceByDisplayPage = assemblyVirtualRawMap,
            cabinetToPages = cabinetIndex?.documents?.assembly?.virtualCombined?.cabinetToPages.orEmpty()
        )
    }
    val virtualMapping = remember(referenceDocType, assemblyVirtualSanitized) {
        if (referenceDocType != ReferenceDocType.ASSEMBLY || assemblyVirtualTotalPages <= 0) {
            null
        } else {
            assemblyVirtualSanitized.mapping
        }
    }
    val navigatorCabinetToPages = remember(referenceDocType, docIndex, assemblyVirtualSanitized, virtualMapping) {
        when (referenceDocType) {
            ReferenceDocType.ASSEMBLY -> if (virtualMapping != null) {
                assemblyVirtualSanitized.cabinetToPages
            } else {
                docIndex?.cabinetToPages.orEmpty()
            }
            ReferenceDocType.PLANS_ELEVATIONS -> docIndex?.cabinetToPages.orEmpty()
            ReferenceDocType.DELIVERY_SHEETS -> emptyMap()
        }
    }
    val navigatorPlanViewLabels = remember(referenceDocType, docIndex) {
        if (referenceDocType != ReferenceDocType.PLANS_ELEVATIONS) {
            emptyMap()
        } else {
            val pageToRoom = docIndex?.pageDetails
                .orEmpty()
                .mapNotNull { (pageKey, detail) ->
                    val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                    val room = extractRoomDisplayName(detail.room) ?: return@mapNotNull null
                    page to room
                }
                .toMap()
            buildPlanViewLabelsFromPageToRoom(pageToRoom)
        }
    }

    val defaultPdfFilename = remember(docIndex, referenceDocType, jobFolderName) {
        docIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, referenceDocType)
            ?: ""
    }

    val docControls: @Composable RowScope.() -> Unit = {
            if (hasAssemblyReference) {
            FilterChip(
                selected = referenceDocType == ReferenceDocType.ASSEMBLY,
                onClick = {
                    onReferenceDocTypeChange(ReferenceDocType.ASSEMBLY)
                    onJumpTargetChange(HardwoodsJumpTarget.ASSEMBLY)
                },
                label = { Text("Assembly") }
            )
            }
            if (hasPlansReference) {
            FilterChip(
                selected = referenceDocType == ReferenceDocType.PLANS_ELEVATIONS,
                onClick = {
                    onReferenceDocTypeChange(ReferenceDocType.PLANS_ELEVATIONS)
                    onJumpTargetChange(HardwoodsJumpTarget.PLANS)
                },
                label = { Text("Plans & Elevations") }
            )
            }
            if (hasThreeDAssets) {
            FilterChip(
                selected = jumpTarget == HardwoodsJumpTarget.THREE_D,
                onClick = { onJumpTargetChange(HardwoodsJumpTarget.THREE_D) },
                label = { Text("View 3D") }
            )
            }
    }

    if (jumpTarget == HardwoodsJumpTarget.THREE_D && hasThreeDAssets) {
        Model3DPane(
            modifier = modifier,
            folderName = jobFolderName,
            roomName = roomName,
            serverPort = serverPort,
            serverError = serverError,
            isDarkTheme = isDarkTheme,
            onFullScreen = onThreeDFullScreen,
            onOpenIn3DApp = onOpenIn3DApp,
            headerSlot = docControls
        )
    } else {
        UnifiedReferenceViewer(
            modifier = modifier,
            displayPage = currentPage,
            onDisplayPageChange = onCurrentPageChange,
            defaultPdfFilename = defaultPdfFilename,
            pdfFileForFilename = { filename ->
                jobRepository.getJobRootPdfFile(
                    jobFolderName = jobFolderName,
                    pdfFilename = filename,
                    preferDarkMode = isDarkTheme
                )
            },
            virtualMapping = virtualMapping,
            navigatorCabinetToPages = navigatorCabinetToPages,
            navigatorPlanViewLabels = navigatorPlanViewLabels,
            navigatorWarningMessage = if (referenceDocType == ReferenceDocType.ASSEMBLY) {
                assemblyVirtualSanitized.warningMessage
            } else {
                null
            },
            showDocControls = docControls
        )
    }
}

@Composable
private fun MaterialSkipPill(
    skipped: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KKCThemeColors.statusColors
    val borderColor = colors.skipBorder
    val fillColor = if (skipped) colors.skipBorder.copy(alpha = 0.88f) else Color.Transparent
    Surface(
        modifier = modifier
            .height(22.dp)
            .widthIn(min = 24.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(11.dp),
        color = fillColor,
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.85f))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (skipped) "SKIPPED" else "SKIP",
                style = MaterialTheme.typography.labelSmall,
                color = if (skipped) Color.White else borderColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HardwoodsBoardStockList(
    sections: List<BoardStockSourceSection>,
    adminItems: List<AdminBoardStockItem> = emptyList(),
    jobFolderName: String,
    progressStore: HardwoodsProgressStore,
    totalsDoneMap: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    val statusColors = KKCThemeColors.statusColors
    if (sections.isEmpty() && adminItems.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No rip cut lines found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var collapsedSourceSections by rememberSaveable(jobFolderName) {
        mutableStateOf(sections.map { it.source.name }.toSet())
    }
    var collapsedMaterialSections by rememberSaveable(jobFolderName) {
        mutableStateOf(
            sections
                .flatMap { section ->
                    section.materials.map { materialSection ->
                        "${section.source.name}|${materialSection.material}"
                    }
                }
                .toSet()
        )
    }
    val childSectionIndent = 14.dp
    val widthBandPalette = statusColors.widthBandPalette
    val widthColorBands = remember(sections, widthBandPalette) {
        val seen = LinkedHashMap<String, Color>()
        var next = 0
        sections.forEach { section ->
            section.materials.forEach { materialSection ->
                materialSection.rows.forEach { line ->
                    val key = normalizeWidthForGrouping(line.width)
                    if (key.isNotEmpty() && !seen.containsKey(key)) {
                        seen[key] = widthBandPalette[next % widthBandPalette.size]
                        next++
                    }
                }
            }
        }
        seen
    }

    // State for admin board stock collapse (source + per-material)
    var adminSourceCollapsed by rememberSaveable(jobFolderName) { mutableStateOf(false) }
    var adminCollapsedMaterials by rememberSaveable(jobFolderName) { mutableStateOf(emptySet<String>()) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // ── Admin board stock section (server-entered items) ──────────────────
        if (adminItems.isNotEmpty()) {
            val adminGroups = adminItems
                .groupBy { it.material.ifBlank { "—" } }
                .entries.sortedBy { it.key.lowercase() }
            val adminTotalTarget = adminGroups.sumOf { (mat, rows) ->
                if (progressStore.isAdminBoardStockMaterialSkipped(jobFolderName, mat)) 0
                else rows.sumOf { item ->
                    val itemSkipped = (totalsDoneMap[progressStore.makeAdminBoardStockSkipKey(mat, item.id)] ?: 0) > 0
                    if (itemSkipped) 0 else kotlin.math.ceil(item.feet / 10.0).toInt().coerceAtLeast(0)
                }
            }
            val adminTotalDone = adminGroups.sumOf { (mat, rows) ->
                if (progressStore.isAdminBoardStockMaterialSkipped(jobFolderName, mat)) 0
                else rows.sumOf { item ->
                    val boards = kotlin.math.ceil(item.feet / 10.0).toInt().coerceAtLeast(0)
                    val itemSkipped = (totalsDoneMap[progressStore.makeAdminBoardStockSkipKey(mat, item.id)] ?: 0) > 0
                    if (itemSkipped) 0
                    else (totalsDoneMap[progressStore.makeAdminBoardStockTallyKey(mat, item.id)] ?: 0).coerceIn(0, boards)
                }
            }
            stickyHeader(key = "admin-board-stock-source") {
                SectionProgressHeader(
                    title = "Board Stock",
                    itemCount = adminItems.size,
                    done = adminTotalDone,
                    total = adminTotalTarget,
                    dimmed = false,
                    skipped = false,
                    expanded = !adminSourceCollapsed,
                    onToggleExpanded = { adminSourceCollapsed = !adminSourceCollapsed },
                    headerActions = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!adminSourceCollapsed) {
                adminGroups.forEach { (material, groupItems) ->
                    val matSkipped = progressStore.isAdminBoardStockMaterialSkipped(jobFolderName, material)
                    val matTarget = if (matSkipped) 0 else groupItems.sumOf { item ->
                        val itemSkipped = (totalsDoneMap[progressStore.makeAdminBoardStockSkipKey(material, item.id)] ?: 0) > 0
                        if (itemSkipped) 0 else kotlin.math.ceil(item.feet / 10.0).toInt().coerceAtLeast(0)
                    }
                    val matDone = if (matSkipped) 0 else groupItems.sumOf { item ->
                        val boards = kotlin.math.ceil(item.feet / 10.0).toInt().coerceAtLeast(0)
                        val itemSkipped = (totalsDoneMap[progressStore.makeAdminBoardStockSkipKey(material, item.id)] ?: 0) > 0
                        if (itemSkipped) 0
                        else (totalsDoneMap[progressStore.makeAdminBoardStockTallyKey(material, item.id)] ?: 0).coerceIn(0, boards)
                    }
                    val matKey = "admin-mat-$material"
                    val matCollapsed = matKey in adminCollapsedMaterials
                    stickyHeader(key = "admin-mat-header:$material") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            SectionProgressHeader(
                                title = material,
                                itemCount = groupItems.size,
                                done = matDone,
                                total = matTarget,
                                dimmed = matSkipped,
                                skipped = matSkipped,
                                expanded = !matCollapsed,
                                onToggleExpanded = {
                                    adminCollapsedMaterials = if (matCollapsed)
                                        adminCollapsedMaterials - matKey
                                    else
                                        adminCollapsedMaterials + matKey
                                },
                                headerActions = {
                                    MaterialSkipPill(
                                        skipped = matSkipped,
                                        onClick = {
                                            progressStore.setAdminBoardStockMaterialSkipped(
                                                jobFolderName, material, !matSkipped
                                            )
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (!matCollapsed) {
                        items(groupItems, key = { "admin-item:${it.id}" }) { item ->
                            val boards = kotlin.math.ceil(item.feet / 10.0).toInt().coerceAtLeast(0)
                            val tallyKey = progressStore.makeAdminBoardStockTallyKey(material, item.id)
                            val skipKey = progressStore.makeAdminBoardStockSkipKey(material, item.id)
                            val itemSkipped = matSkipped || ((totalsDoneMap[skipKey] ?: 0) > 0)
                            val done = if (itemSkipped) 0 else (totalsDoneMap[tallyKey] ?: 0).coerceIn(0, boards)
                            val rowState = when {
                                itemSkipped -> ProgressState.SKIPPED
                                boards <= 0 -> ProgressState.NOT_STARTED
                                done >= boards -> ProgressState.COMPLETE
                                done > 0 -> ProgressState.IN_PROGRESS
                                else -> ProgressState.NOT_STARTED
                            }
                            val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp)
                                    .heightIn(min = 48.dp)
                                    .drawBehind {
                                        drawLine(
                                            color = dividerColor,
                                            start = Offset(0f, size.height - 1f),
                                            end = Offset(size.width, size.height - 1f),
                                            strokeWidth = 1f
                                        )
                                    },
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    matSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                    itemSkipped -> statusColors.completeBgRow.copy(alpha = 0.96f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
                                },
                                tonalElevation = 0.5.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .fillMaxHeight()
                                            .background(statusColors.inProgress)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Text(
                                                item.name.ifBlank { "—" },
                                                style = DimensionTextStyle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "Need $boards boards  ·  ${item.feet.toInt()} ft",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                progressStore.setAdminBoardStockDone(
                                                    jobFolderName, material, item.id, done - 1
                                                )
                                            },
                                            enabled = !itemSkipped && done > 0,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = statusColors.bad,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp)
                                        ) { Icon(Icons.Default.Remove, contentDescription = "Done -", modifier = Modifier.size(14.dp)) }
                                        ProgressPill(
                                            done = done,
                                            total = boards,
                                            state = rowState,
                                            skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f)
                                        )
                                        Button(
                                            onClick = {
                                                progressStore.setAdminBoardStockDone(
                                                    jobFolderName, material, item.id, done + 1
                                                )
                                            },
                                            enabled = !itemSkipped && done < boards,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = statusColors.completeBorder,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp)
                                        ) { Icon(Icons.Default.Add, contentDescription = "Done +", modifier = Modifier.size(14.dp)) }
                                        if (!matSkipped) {
                                            if (itemSkipped) {
                                                Button(
                                                    onClick = {
                                                        progressStore.setAdminBoardStockSkipped(
                                                            jobFolderName, material, item.id, false
                                                        )
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = statusColors.skipBorder,
                                                        contentColor = Color.White
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                    modifier = Modifier.heightIn(min = 32.dp)
                                                ) {
                                                    Text("SKIPPED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                                }
                                            } else {
                                                MaterialSkipPill(
                                                    skipped = false,
                                                    onClick = {
                                                        progressStore.setAdminBoardStockSkipped(
                                                            jobFolderName, material, item.id, true
                                                        )
                                                    }
                                                )
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
        // ── Auto-calculated rip cut sections ──────────────────────────────────
        sections.forEach { sourceSection ->
            val sourceKey = sourceSection.source.name
            val sourceRows = sourceSection.materials.flatMap { it.rows }
            val sourceCollapsed = sourceKey in collapsedSourceSections
            val parentSectionBottomBuffer = 14.dp
            val totalTarget = sourceRows.sumOf { line ->
                val materialSkipped = progressStore.isBoardStockMaterialSkipped(
                    jobFolderName = jobFolderName,
                    material = line.material,
                    source = sourceSection.source.name
                )
                val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(line.material, line.normalizedWidth, line.source.name)
                val lineSkipped = materialSkipped || ((totalsDoneMap[lineSkippedKey] ?: 0) > 0)
                if (lineSkipped) 0 else line.neededRips
            }
            val totalDone = sourceRows.sumOf { line ->
                val materialSkipped = progressStore.isBoardStockMaterialSkipped(
                    jobFolderName = jobFolderName,
                    material = line.material,
                    source = sourceSection.source.name
                )
                val key = progressStore.makeBoardStockTallyKey(line.material, line.normalizedWidth, line.source.name)
                val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(line.material, line.normalizedWidth, line.source.name)
                val lineSkipped = materialSkipped || ((totalsDoneMap[lineSkippedKey] ?: 0) > 0)
                if (lineSkipped) 0 else (totalsDoneMap[key] ?: 0).coerceIn(0, line.neededRips)
            }
            val sourceAllSkipped = sourceSection.materials.isNotEmpty() &&
                sourceSection.materials.all { materialSection ->
                    progressStore.isBoardStockMaterialSkipped(
                        jobFolderName = jobFolderName,
                        material = materialSection.material,
                        source = sourceSection.source.name
                    )
                }
            stickyHeader(key = "totals-source:$sourceKey") {
                SectionProgressHeader(
                    title = sourceSection.title,
                    itemCount = sourceRows.size,
                    done = totalDone,
                    total = totalTarget,
                    dimmed = sourceAllSkipped,
                    skipped = sourceAllSkipped,
                    expanded = !sourceCollapsed,
                    onToggleExpanded = {
                        collapsedSourceSections = if (sourceCollapsed) {
                            collapsedSourceSections - sourceKey
                        } else {
                            collapsedSourceSections + sourceKey
                        }
                    },
                    headerActions = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!sourceCollapsed) {
                sourceSection.materials.forEach { materialSection ->
                    val materialKey = "$sourceKey|${materialSection.material}"
                    val materialCollapsed = materialKey in collapsedMaterialSections
                    val materialSkipped = progressStore.isBoardStockMaterialSkipped(
                        jobFolderName = jobFolderName,
                        material = materialSection.material,
                        source = sourceSection.source.name
                    )
                    val materialTarget = materialSection.rows.sumOf { line ->
                        val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(line.material, line.normalizedWidth, line.source.name)
                        val lineSkipped = materialSkipped || ((totalsDoneMap[lineSkippedKey] ?: 0) > 0)
                        if (lineSkipped) 0 else line.neededRips
                    }
                    val materialDone = materialSection.rows.sumOf { line ->
                        val key = progressStore.makeBoardStockTallyKey(line.material, line.normalizedWidth, line.source.name)
                        val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(line.material, line.normalizedWidth, line.source.name)
                        val lineSkipped = materialSkipped || ((totalsDoneMap[lineSkippedKey] ?: 0) > 0)
                        if (lineSkipped) 0 else (totalsDoneMap[key] ?: 0).coerceIn(0, line.neededRips)
                    }
                    stickyHeader(key = "totals-material:$sourceKey:${materialSection.material}") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = childSectionIndent),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            SectionProgressHeader(
                                title = materialSection.material,
                                itemCount = materialSection.rows.size,
                                done = materialDone,
                                total = materialTarget,
                                dimmed = materialSkipped,
                                skipped = materialSkipped,
                                expanded = !materialCollapsed,
                                onToggleExpanded = {
                                    collapsedMaterialSections = if (materialCollapsed) {
                                        collapsedMaterialSections - materialKey
                                    } else {
                                        collapsedMaterialSections + materialKey
                                    }
                                },
                                headerActions = {
                                    MaterialSkipPill(
                                        skipped = materialSkipped,
                                        onClick = {
                                            progressStore.setBoardStockMaterialSkipped(
                                                jobFolderName = jobFolderName,
                                                material = materialSection.material,
                                                source = sourceSection.source.name,
                                                skipped = !materialSkipped
                                            )
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (!materialCollapsed) {
                        items(materialSection.rows, key = { it.stableKey }) { line ->
                            val key = progressStore.makeBoardStockTallyKey(line.material, line.normalizedWidth, line.source.name)
                            val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(line.material, line.normalizedWidth, line.source.name)
                            val lineSkipped = materialSkipped || ((totalsDoneMap[lineSkippedKey] ?: 0) > 0)
                            val rawDone = (totalsDoneMap[key] ?: 0).coerceIn(0, line.neededRips)
                            val done = rawDone
                            val widthBand = widthColorBands[normalizeWidthForGrouping(line.width)] ?: statusColors.notStarted
                            val rowState = when {
                                lineSkipped -> ProgressState.SKIPPED
                                line.neededRips <= 0 -> ProgressState.NOT_STARTED
                                done >= line.neededRips -> ProgressState.COMPLETE
                                done > 0 -> ProgressState.IN_PROGRESS
                                else -> ProgressState.NOT_STARTED
                            }
                            val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = childSectionIndent)
                                    .heightIn(min = 48.dp)
                                    .drawBehind {
                                        drawLine(
                                            color = dividerColor,
                                            start = Offset(0f, size.height - 1f),
                                            end = Offset(size.width, size.height - 1f),
                                            strokeWidth = 1f
                                        )
                                    },
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    materialSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                    lineSkipped -> statusColors.completeBgRow.copy(alpha = 0.96f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
                                },
                                tonalElevation = 0.5.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .fillMaxHeight()
                                            .background(widthBand)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    line.width,
                                                    style = DimensionTextStyle
                                                )
                                                Text(
                                                    "Need ${line.neededRips} rips",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontStyle = FontStyle.Italic
                                                )
                                            }
                                            Text(
                                                "Total ${formatLinearFeet(line.totalFeet)} ft",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                progressStore.setBoardStockRipDone(
                                                    jobFolderName = jobFolderName,
                                                    material = line.material,
                                                    normalizedWidth = line.normalizedWidth,
                                                    source = line.source.name,
                                                    doneCount = done - 1
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = statusColors.bad,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier
                                                .heightIn(min = 32.dp)
                                                .widthIn(min = 32.dp)
                                        ) { Icon(Icons.Default.Remove, contentDescription = "Rip done -", modifier = Modifier.size(14.dp)) }
                                        ProgressPill(
                                            done = done,
                                            total = line.neededRips,
                                            state = rowState,
                                            skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f)
                                        )
                                        Button(
                                            onClick = {
                                                progressStore.setBoardStockRipDone(
                                                    jobFolderName = jobFolderName,
                                                    material = line.material,
                                                    normalizedWidth = line.normalizedWidth,
                                                    source = line.source.name,
                                                    doneCount = done + 1
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = statusColors.completeBorder,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier
                                                .heightIn(min = 32.dp)
                                                .widthIn(min = 32.dp)
                                        ) { Icon(Icons.Default.Add, contentDescription = "Rip done +", modifier = Modifier.size(14.dp)) }
                                        if (lineSkipped) {
                                            Button(
                                                onClick = {
                                                    progressStore.setBoardStockRipSkipped(
                                                        jobFolderName = jobFolderName,
                                                        material = line.material,
                                                        normalizedWidth = line.normalizedWidth,
                                                        source = line.source.name,
                                                        skipped = false
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = statusColors.skipBorder,
                                                    contentColor = Color.White
                                                ),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.heightIn(min = 32.dp)
                                            ) {
                                                Text("SKIPPED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    progressStore.setBoardStockRipSkipped(
                                                        jobFolderName = jobFolderName,
                                                        material = line.material,
                                                        normalizedWidth = line.normalizedWidth,
                                                        source = line.source.name,
                                                        skipped = true
                                                    )
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
                }
                item(key = "source-buffer:$sourceKey") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(parentSectionBottomBuffer)
                    )
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

/** Groups door-panel rows into one section per cabinet number, sorted numerically. */
private fun buildCabinetSections(rows: List<HardwoodCutlistRow>): List<HardwoodsPartSection> {
    if (rows.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<HardwoodCutlistRow>>()
    rows.forEach { row ->
        val firstCab = row.cabinets.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: row.rawCabinetText.split(",").firstOrNull()
                ?.trim()?.replace(Regex("""\(.*"""), "")?.trim()?.takeIf { it.isNotBlank() }
            ?: "—"
        grouped.getOrPut(firstCab) { mutableListOf() }.add(row)
    }
    return grouped.entries
        .sortedWith(compareBy { it.key.toIntOrNull() ?: Int.MAX_VALUE })
        .map { (cab, groupedRows) ->
            HardwoodsPartSection(
                material = "Cabinet $cab",
                pagesLabel = "",
                rows = groupedRows.sortedWith(cutlistOrderComparator())
            )
        }
}

/** Groups door-panel rows into one section per room, using a cabinet→room lookup. */
private fun buildRoomSections(
    rows: List<HardwoodCutlistRow>,
    cabinetToRoom: Map<String, String>
): List<HardwoodsPartSection> {
    if (rows.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<HardwoodCutlistRow>>()
    rows.forEach { row ->
        val firstCab = row.cabinets.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: row.rawCabinetText.split(",").firstOrNull()
                ?.trim()?.replace(Regex("""\(.*"""), "")?.trim()
        val room = (firstCab?.let { cabinetToRoom[it] })?.takeIf { it.isNotBlank() }
            ?: "Unassigned"
        grouped.getOrPut(room) { mutableListOf() }.add(row)
    }
    return grouped.entries
        .sortedBy { it.key }
        .map { (room, groupedRows) ->
            HardwoodsPartSection(
                material = room,
                pagesLabel = "",
                rows = groupedRows.sortedWith(
                    compareBy<HardwoodCutlistRow> { it.cabinets.firstOrNull()?.toIntOrNull() ?: Int.MAX_VALUE }
                        .then(cutlistOrderComparator())
                )
            )
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

private fun formatLinearFeet(value: Double): String {
    val safe = value.coerceAtLeast(0.0)
    val whole = safe.toInt().toDouble()
    if (kotlin.math.abs(safe - whole) < 0.0001) {
        return whole.toInt().toString()
    }
    return "%.3f".format(safe).trimEnd('0').trimEnd('.')
}

internal fun applyDoorPanelsSheetFilter(
    rows: List<HardwoodCutlistRow>,
    selectedDocType: HardwoodDocType,
    enabled: Boolean,
    rawCutlistIndexJson: String?
): List<HardwoodCutlistRow> {
    if (!enabled || selectedDocType != HardwoodDocType.DOOR_CUT_LIST) return rows
    val metadata = parseDoorCutUnitTypeMetadata(rawCutlistIndexJson)
    return if (metadata.hasUnitTypeMetadata) {
        filterDoorCutRowsToSheets(rows, metadata)
    } else {
        rows
    }
}

