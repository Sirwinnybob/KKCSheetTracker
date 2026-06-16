package com.kkc.sheettracker.ui.assembly

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import com.kkc.sheettracker.ui.components.ImmersiveSystemBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.AssemblyViewLayout
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import androidx.compose.material3.Card
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.JobPdfCatalog
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.ui.components.AdaptiveSplitLayout
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.theme.KKCSpacing
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewer
import com.kkc.sheettracker.ui.viewer.UnifiedVirtualPageMapping
import com.kkc.sheettracker.ui.viewer.UnifiedVirtualPageSource
import com.kkc.sheettracker.ui.viewer.buildPlanViewLabelsFromPageToRoom
import com.kkc.sheettracker.ui.viewer.extractRoomDisplayName
import com.kkc.sheettracker.ui.viewer.sanitizeVirtualAssemblyData
import com.kkc.sheettracker.viewer3d.Model3DPane
import com.kkc.sheettracker.viewer3d.ViewerServer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROOM_PAREN_REGEX = Regex("""\(([^)]+)\)""")
private val ROOM_ILLEGAL_CHARS_REGEX = Regex("""[/\\:*?"<>|]""")
private val ROOM_WHITESPACE_REGEX = Regex("""\s+""")

private enum class FullscreenPane {
    NONE,
    FIRST,
    SECOND
}

private enum class PaneSource {
    PLANS,
    ASSEMBLY,
    DELIVERY,
    OTHER,
    THREE_D,
    CHECKLIST
}

private enum class PaneSlot {
    FIRST,
    SECOND
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyViewerScreen(
    jobRepository: JobRepository,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    jobFolderName: String,
    basePath: String = "",
    startPageAssembly: Int,
    startPagePlans: Int,
    initialSource: String? = null,
    initialCabinet: String? = null,
    initialRoom: String? = null,
    initialLayout: AssemblyViewLayout? = null,
    initialFirstPane: AssemblyPaneView? = null,
    initialSecondPane: AssemblyPaneView? = null,
    initialHideUi: Boolean = false,
    refreshGeneration: Long = 0L,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onLeaveWhileClockedIn: () -> Unit = {},
    onUiVisibilityChanged: (Boolean) -> Unit = {}
) {
    val sheetIndex by produceState<CabinetSheetIndex?>(
        initialValue = null,
        key1 = jobFolderName,
        key2 = refreshGeneration
    ) {
        value = withContext(Dispatchers.IO) {
            assemblyStateStore.getCabinetSheetIndex(jobFolderName)
        }
    }
    val assemblyJobInfo = remember(jobFolderName) {
        assemblyStateStore.getJobs().firstOrNull { it.folderName == jobFolderName }
    }
    val clockInJobNumber = assemblyJobInfo?.jobNumber ?: jobFolderName
    val clockInJobName = assemblyJobInfo?.jobName ?: ""
    fun parseInitialSource(raw: String?): PaneSource? = when (raw?.trim()?.lowercase()) {
        "3d", "three_d", "threed" -> PaneSource.THREE_D
        "assembly" -> PaneSource.ASSEMBLY
        "plans", "plans_elevations", "elevations" -> PaneSource.PLANS
        else -> null
    }
    val initialPaneSource = parseInitialSource(initialSource)

    val assemblyFilename by produceState<String>(
        initialValue = "",
        key1 = sheetIndex,
        key2 = jobFolderName,
        key3 = refreshGeneration
    ) {
        value = sheetIndex?.documents?.assembly?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) {
                jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY)
            }.orEmpty()
    }
    val assemblyVirtualRawMap = remember(sheetIndex) {
        sheetIndex?.documents?.assembly?.virtualCombined?.virtualPageToSource
            ?.mapNotNull { (virtualPageKey, source) ->
                val virtualPage = virtualPageKey.toIntOrNull() ?: return@mapNotNull null
                if (virtualPage <= 0) return@mapNotNull null
                virtualPage to UnifiedVirtualPageSource(
                    pdfFilename = source.pdfFilename,
                    page = source.page,
                    cabinet = source.cabinet,
                    sourceVariant = source.variant
                )
            }
            ?.toMap()
            .orEmpty()
    }
    val assemblyVirtualTotalPagesRaw = remember(sheetIndex) {
        (sheetIndex?.documents?.assembly?.virtualCombined?.totalVirtualPages ?: 0).coerceAtLeast(0)
    }
    val assemblyVirtualSanitized = remember(
        assemblyVirtualTotalPagesRaw,
        assemblyFilename,
        assemblyVirtualRawMap,
        sheetIndex
    ) {
        sanitizeVirtualAssemblyData(
            totalVirtualPages = assemblyVirtualTotalPagesRaw,
            defaultPdfFilename = assemblyFilename,
            sourceByDisplayPage = assemblyVirtualRawMap,
            cabinetToPages = sheetIndex?.documents?.assembly?.virtualCombined?.cabinetToPages.orEmpty()
        )
    }
    val assemblyVirtualMapping = remember(assemblyVirtualSanitized) { assemblyVirtualSanitized.mapping }
    val assemblyVirtualTotalPages = assemblyVirtualMapping?.totalDisplayPages ?: 0
    val hasVirtualAssembly = assemblyVirtualMapping != null && assemblyVirtualTotalPages > 0
    val assemblyNavigatorCabinetToPages = remember(sheetIndex, assemblyVirtualSanitized, hasVirtualAssembly) {
        if (hasVirtualAssembly) {
            assemblyVirtualSanitized.cabinetToPages
        } else {
            sheetIndex?.documents?.assembly?.cabinetToPages.orEmpty()
        }
    }
    val plansFilename by produceState<String>(
        initialValue = "",
        key1 = sheetIndex,
        key2 = jobFolderName,
        key3 = refreshGeneration
    ) {
        value = sheetIndex?.documents?.plansElevations?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) {
                jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            }.orEmpty()
    }
    val plansNavigatorPlanViewLabels = remember(sheetIndex) {
        val pageToRoom = sheetIndex?.documents?.plansElevations?.pageDetails
            .orEmpty()
            .mapNotNull { (pageKey, detail) ->
                val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                val room = extractRoomDisplayName(detail.room) ?: return@mapNotNull null
                page to room
            }
            .toMap()
        buildPlanViewLabelsFromPageToRoom(pageToRoom)
    }
    val pdfCatalog by produceState<JobPdfCatalog?>(
        initialValue = null,
        key1 = jobFolderName,
        key2 = refreshGeneration
    ) {
        value = withContext(Dispatchers.IO) {
            jobRepository.getJobPdfCatalog(jobFolderName)
        }
    }
    val deliveryFilename = remember(pdfCatalog?.deliverySheet) {
        pdfCatalog?.deliverySheet?.pdfFilename.orEmpty()
    }
    val unmanagedOtherPdfNames = remember(pdfCatalog?.otherDocs) {
        pdfCatalog?.otherDocs?.map { it.pdfFilename }.orEmpty()
    }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val resumePrefix = remember(jobFolderName) { "assembly_resume_v1_${jobFolderName}" }
    var assemblyPage by rememberSaveable(startPageAssembly) {
        mutableIntStateOf(prefs.getInt("${resumePrefix}_assembly_page", startPageAssembly).coerceAtLeast(1))
    }
    var plansPage by rememberSaveable(startPagePlans) {
        mutableIntStateOf(prefs.getInt("${resumePrefix}_plans_page", startPagePlans).coerceAtLeast(1))
    }
    var searchText by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var lastSearchedCabinet by rememberSaveable { mutableStateOf("") }
    var contextLine by remember { mutableStateOf("") }
    var showPartsSheet by remember { mutableStateOf(false) }
    var fullscreenPane by rememberSaveable(initialSource) {
        val saved = prefs.getString("${resumePrefix}_fullscreen", null)
        mutableStateOf(
            runCatching { saved?.let { FullscreenPane.valueOf(it) } }.getOrNull()
                ?: when {
                    initialPaneSource == PaneSource.THREE_D -> FullscreenPane.FIRST
                    initialLayout == AssemblyViewLayout.SINGLE -> FullscreenPane.FIRST
                    else -> FullscreenPane.NONE
                }
        )
    }

    // True fullscreen: hide system bars for the lifetime of this screen.
    ImmersiveSystemBars()
    // Tap-to-show/hide overlay UI (top bar + floating controls + bottom nav).
    var showUi by rememberSaveable { mutableStateOf(!initialHideUi) }
    // Restore bottom nav visibility when navigating back.
    DisposableEffect(Unit) { onDispose { onUiVisibilityChanged(true) } }

    var firstPaneSource by rememberSaveable(initialSource) {
        val saved = prefs.getString("${resumePrefix}_first_source", null)
        mutableStateOf(
            runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
                ?: initialPaneSource
                ?: initialFirstPane?.toPaneSource()
                ?: PaneSource.PLANS
        )
    }
    var secondPaneSource by rememberSaveable(initialSource) {
        val saved = prefs.getString("${resumePrefix}_second_source", null)
        mutableStateOf(
            runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
                ?: initialSecondPane?.toPaneSource()
                ?: PaneSource.ASSEMBLY
        )
    }
    var firstPaneOtherFilename by rememberSaveable { mutableStateOf<String?>(null) }
    var secondPaneOtherFilename by rememberSaveable { mutableStateOf<String?>(null) }
    var firstPaneDeliveryPage by rememberSaveable { mutableIntStateOf(1) }
    var secondPaneDeliveryPage by rememberSaveable { mutableIntStateOf(1) }
    var firstPaneOtherPage by rememberSaveable(firstPaneOtherFilename) { mutableIntStateOf(1) }
    var secondPaneOtherPage by rememberSaveable(secondPaneOtherFilename) { mutableIntStateOf(1) }
    var firstPaneTotalPages by remember { mutableIntStateOf(0) }
    var secondPaneTotalPages by remember { mutableIntStateOf(0) }
    var firstPaneTocRequestToken by remember { mutableIntStateOf(0) }
    var secondPaneTocRequestToken by remember { mutableIntStateOf(0) }
    var otherPickerTarget by remember { mutableStateOf<PaneSlot?>(null) }
    var serverPort by remember { mutableIntStateOf(0) }
    var viewerServerError by remember { mutableStateOf<String?>(null) }
    var detectedRoom by rememberSaveable(initialRoom) { mutableStateOf(initialRoom) }

    LaunchedEffect(assemblyPage, plansPage, firstPaneSource, secondPaneSource, fullscreenPane) {
        prefs.edit()
            .putInt("${resumePrefix}_assembly_page", assemblyPage)
            .putInt("${resumePrefix}_plans_page", plansPage)
            .putString("${resumePrefix}_first_source", firstPaneSource.name)
            .putString("${resumePrefix}_second_source", secondPaneSource.name)
            .putString("${resumePrefix}_fullscreen", fullscreenPane.name)
            .apply()
    }
    DisposableEffect(basePath, jobFolderName) {
        android.util.Log.d("AssemblyViewer", "DisposableEffect: basePath='$basePath' job='$jobFolderName'")
        if (basePath.isBlank()) {
            android.util.Log.w("AssemblyViewer", "basePath blank — skipping server start")
            serverPort = 0
            viewerServerError = "Base path is blank"
            return@DisposableEffect onDispose {}
        }
        val server = ViewerServer(context, File(basePath))
        val startResult = server.startWithRetry()
        serverPort = startResult.port
        viewerServerError = startResult.error
        android.util.Log.d("AssemblyViewer", "serverPort set to $serverPort, viewerServerError=${viewerServerError ?: "none"}")
        onDispose { server.stop() }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val cabinetParts by produceState<AssemblyCabinetParts?>(
        initialValue = null,
        key1 = jobFolderName,
        key2 = lastSearchedCabinet
    ) {
        value = if (lastSearchedCabinet.isBlank()) null
        else withContext(Dispatchers.IO) {
            assemblyStateStore.deriveCabinetParts(jobFolderName, lastSearchedCabinet)
        }
    }

    fun extractRoomFolder(roomText: String?): String? =
        roomText?.let {
            val raw = ROOM_PAREN_REGEX.find(it)?.groupValues?.get(1)?.uppercase()
                ?: it.uppercase().takeIf { it.isNotBlank() }
            raw?.replace(ROOM_ILLEGAL_CHARS_REGEX, " ")
                ?.replace(ROOM_WHITESPACE_REGEX, " ")
                ?.trim()
                ?.takeIf { s -> s.isNotBlank() }
        }

    val assemblyPageDetails = remember(sheetIndex) {
        sheetIndex?.documents?.assembly?.virtualCombined?.pageDetails
            ?.takeIf { it.isNotEmpty() }
            ?: sheetIndex?.documents?.assembly?.pageDetails.orEmpty()
    }

    fun roomForAssemblyPage(page: Int): String? =
        extractRoomFolder(assemblyPageDetails[page.toString()]?.room)

    fun firstAlphabeticalRoomFromIndex(): Pair<String, Int>? {
        return assemblyPageDetails
            .mapNotNull { (pageKey, detail) ->
                val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                val room = extractRoomFolder(detail.room) ?: return@mapNotNull null
                room to page
            }
            .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            .firstOrNull()
    }

    fun resolveRoomDae(room: String?): File? {
        if (room == null || basePath.isBlank()) return null
        val roomDir = File("$basePath/$jobFolderName/3D/$room")
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
        // AssimpAndroid calls uri.getPath() and needs a file:// URI with a real path.
        // Temporarily relax StrictMode to allow file:// URIs to cross process boundaries.
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

    fun jumpToCabinet(cab: String) {
        val normalized = cab.trim()
        if (normalized.isBlank()) return
        val (assemblyTarget, plansTarget) = assemblyStateStore.getCabinetJumpPages(jobFolderName, normalized)
        if (assemblyTarget != null) assemblyPage = assemblyTarget
        if (plansTarget != null) plansPage = plansTarget

        lastSearchedCabinet = normalized
        contextLine = assemblyStateStore.getCabinetContext(jobFolderName, normalized)

        detectedRoom = roomForAssemblyPage(assemblyTarget ?: assemblyPage)

        if (assemblyTarget == null && plansTarget == null) {
            scope.launch {
                snackbarHostState.showSnackbar("Cabinet $normalized not found in Assembly or Plans")
            }
        } else {
            if (assemblyTarget == null) {
                scope.launch {
                    snackbarHostState.showSnackbar("Cabinet $normalized not in Assembly Sheets")
                }
            }
            if (plansTarget == null) {
                scope.launch {
                    snackbarHostState.showSnackbar("Cabinet $normalized not in Plans & Elevations")
                }
            }
        }
    }

    LaunchedEffect(assemblyPage, firstPaneSource, secondPaneSource) {
        if (firstPaneSource == PaneSource.THREE_D || secondPaneSource == PaneSource.THREE_D) {
            roomForAssemblyPage(assemblyPage)?.let { detectedRoom = it }
        }
    }

    LaunchedEffect(initialCabinet, initialRoom, initialPaneSource) {
        val shouldAutoJumpCabinet = initialPaneSource != PaneSource.THREE_D || initialRoom.isNullOrBlank()
        if (shouldAutoJumpCabinet && !initialCabinet.isNullOrBlank() &&
            (lastSearchedCabinet.isBlank() || !initialCabinet.equals(lastSearchedCabinet, ignoreCase = true))) {
            searchText = androidx.compose.ui.text.input.TextFieldValue(initialCabinet)
            jumpToCabinet(initialCabinet)
        }
    }

    LaunchedEffect(initialPaneSource, sheetIndex, detectedRoom) {
        if (initialPaneSource == PaneSource.THREE_D && detectedRoom.isNullOrBlank()) {
            val fallback = firstAlphabeticalRoomFromIndex()
            if (fallback != null) {
                detectedRoom = fallback.first
                assemblyPage = fallback.second
            }
        }
    }

    fun sourceLabel(source: PaneSource): String = when (source) {
        PaneSource.PLANS -> "Plans"
        PaneSource.ASSEMBLY -> "Assembly"
        PaneSource.DELIVERY -> "Delivery"
        PaneSource.OTHER -> "Other"
        PaneSource.THREE_D -> "3D"
        PaneSource.CHECKLIST -> "Checklist"
    }

    fun sourceFilename(source: PaneSource, otherFilename: String?): String? = when (source) {
        PaneSource.PLANS -> plansFilename.takeIf { it.isNotBlank() }
        PaneSource.ASSEMBLY -> assemblyFilename.takeIf { it.isNotBlank() }
        PaneSource.DELIVERY -> deliveryFilename.takeIf { it.isNotBlank() }
        PaneSource.OTHER -> otherFilename?.takeIf { it.isNotBlank() }
        PaneSource.THREE_D -> null
        PaneSource.CHECKLIST -> null
    }

    fun sourcePage(source: PaneSource, otherPage: Int, deliveryPage: Int): Int = when (source) {
        PaneSource.PLANS -> plansPage
        PaneSource.ASSEMBLY -> assemblyPage
        PaneSource.DELIVERY -> deliveryPage
        PaneSource.OTHER -> otherPage
        PaneSource.THREE_D -> 1
        PaneSource.CHECKLIST -> 1
    }

    fun setSourcePage(source: PaneSource, nextPage: Int, setOther: (Int) -> Unit, setDelivery: (Int) -> Unit) {
        when (source) {
            PaneSource.PLANS -> plansPage = nextPage
            PaneSource.ASSEMBLY -> {
                if (hasVirtualAssembly) {
                    assemblyPage = nextPage.coerceIn(1, assemblyVirtualTotalPages.coerceAtLeast(1))
                } else {
                    assemblyPage = nextPage.coerceAtLeast(1)
                }
            }
            PaneSource.DELIVERY -> setDelivery(nextPage)
            PaneSource.OTHER -> setOther(nextPage)
            PaneSource.THREE_D -> Unit
            PaneSource.CHECKLIST -> Unit
        }
    }

    val firstSourceName = sourceLabel(firstPaneSource)
    val secondSourceName = sourceLabel(secondPaneSource)
    val firstSourceFilename = sourceFilename(firstPaneSource, firstPaneOtherFilename)
    val secondSourceFilename = sourceFilename(secondPaneSource, secondPaneOtherFilename)
    val firstMissingText = remember(firstPaneSource, firstPaneOtherFilename, firstSourceFilename) {
        when (firstPaneSource) {
            PaneSource.OTHER -> when {
                firstPaneOtherFilename.isNullOrBlank() -> "Select an Other PDF"
                firstSourceFilename.isNullOrBlank() -> "Selected Other file is unavailable"
                else -> "Selected Other file unavailable: $firstPaneOtherFilename"
            }
            PaneSource.THREE_D -> "Select a part/room target to load its 3D model"
            PaneSource.CHECKLIST -> ""
            else -> "$firstSourceName PDF not found"
        }
    }
    val secondMissingText = remember(secondPaneSource, secondPaneOtherFilename, secondSourceFilename) {
        when (secondPaneSource) {
            PaneSource.OTHER -> when {
                secondPaneOtherFilename.isNullOrBlank() -> "Select an Other PDF"
                secondSourceFilename.isNullOrBlank() -> "Selected Other file is unavailable"
                else -> "Selected Other file unavailable: $secondPaneOtherFilename"
            }
            PaneSource.THREE_D -> "Select a part/room target to load its 3D model"
            PaneSource.CHECKLIST -> ""
            else -> "$secondSourceName PDF not found"
        }
    }
    val firstUnreadableText = remember(firstPaneSource, firstSourceName, firstSourceFilename) {
        if (firstPaneSource == PaneSource.OTHER && !firstSourceFilename.isNullOrBlank()) {
            "Unable to read ${firstSourceFilename}"
        } else {
            "Unable to read $firstSourceName"
        }
    }
    val secondUnreadableText = remember(secondPaneSource, secondSourceName, secondSourceFilename) {
        if (secondPaneSource == PaneSource.OTHER && !secondSourceFilename.isNullOrBlank()) {
            "Unable to read ${secondSourceFilename}"
        } else {
            "Unable to read $secondSourceName"
        }
    }

    androidx.compose.runtime.DisposableEffect(isClockedInHere) {
        val shouldNotify = isClockedInHere
        val notifyFn = onLeaveWhileClockedIn
        onDispose { if (shouldNotify) notifyFn() }
    }

    val topBarAlpha by animateFloatAsState(if (showUi) 1f else 0f, tween(220), label = "topBarAlpha")

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            // graphicsLayer alpha — no layout shift, no PDF re-render during animation.
                TopAppBar(
                    modifier = Modifier.graphicsLayer { alpha = topBarAlpha },
                    title = {
                        Text(
                            "Assembly Viewer - $jobFolderName",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Button(
                            onClick = { onClockIn(clockInJobNumber, clockInJobName) },
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
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    windowInsets = WindowInsets.statusBars
                )
        }
    ) { padding ->
        val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        val animatedTopPad by animateDpAsState(
            targetValue = if (showUi) padding.calculateTopPadding() else 0.dp,
            animationSpec = tween(220),
            label = "scaffoldTopPad"
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = animatedTopPad, bottom = padding.calculateBottomPadding(), start = 0.dp, end = 0.dp)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (fullscreenPane) {
                    FullscreenPane.FIRST -> {
                        PdfPaneWithFloatingControls(
                            jobRepository = jobRepository,
                            jobFolderName = jobFolderName,
                            isDarkTheme = isDarkTheme,
                            title = firstSourceName,
                            pdfFilename = firstSourceFilename,
                            currentPage = if (firstPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                assemblyPage
                            } else {
                                sourcePage(firstPaneSource, firstPaneOtherPage, firstPaneDeliveryPage)
                            },
                            totalPages = firstPaneTotalPages,
                            onCurrentPageChange = { nextPage ->
                                setSourcePage(
                                    source = firstPaneSource,
                                    nextPage = nextPage,
                                    setOther = { firstPaneOtherPage = it },
                                    setDelivery = { firstPaneDeliveryPage = it }
                                )
                            },
                            onTotalPagesChanged = {
                                firstPaneTotalPages = if (firstPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                    assemblyVirtualTotalPages
                                } else {
                                    it
                                }
                            },
                            tocRequestToken = firstPaneTocRequestToken,
                            onOpenToc = {
                                firstPaneTocRequestToken++
                            },
                            virtualMapping = if (firstPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                assemblyVirtualMapping
                            } else {
                                null
                            },
                            navigatorCabinetToPages = if (firstPaneSource == PaneSource.ASSEMBLY) {
                                assemblyNavigatorCabinetToPages
                            } else if (firstPaneSource == PaneSource.PLANS) {
                                sheetIndex?.documents?.plansElevations?.cabinetToPages.orEmpty()
                            } else {
                                emptyMap()
                            },
                            navigatorPlanViewLabels = if (firstPaneSource == PaneSource.PLANS) {
                                plansNavigatorPlanViewLabels
                            } else {
                                emptyMap()
                            },
                            navigatorWarningMessage = if (firstPaneSource == PaneSource.ASSEMBLY) {
                                assemblyVirtualSanitized.warningMessage
                            } else {
                                null
                            },
                            missingText = firstMissingText,
                            unreadableText = firstUnreadableText,
                            sourceControlsInline = {
                                PaneSourceControlsInline(
                                    selectedSource = firstPaneSource,
                                    selectedOtherFilename = firstPaneOtherFilename,
                                    hasOtherOptions = unmanagedOtherPdfNames.isNotEmpty(),
                                    onSelectSource = { firstPaneSource = it },
                                    onOpenOtherPicker = { otherPickerTarget = PaneSlot.FIRST }
                                )
                            },
                            isFullscreen = true,
                            onToggleFullscreen = { fullscreenPane = FullscreenPane.NONE },
                            showControls = showUi,
                            onSingleTap = { showUi = !showUi; onUiVisibilityChanged(showUi) },
                            customContent = if (firstPaneSource == PaneSource.CHECKLIST) {{
                                ChecklistPane(
                                    modifier = Modifier.fillMaxSize(),
                                    jobFolderName = jobFolderName,
                                    specialtyStateStore = specialtyStateStore,
                                    onJumpToCabinet = { cab -> jumpToCabinet(cab) }
                                )
                            }} else if (firstPaneSource == PaneSource.THREE_D) {{
                                Model3DPane(
                                    modifier = Modifier.fillMaxSize(),
                                    folderName = jobFolderName,
                                    roomName = detectedRoom,
                                    serverPort = serverPort,
                                    serverError = viewerServerError,
                                    isDarkTheme = isDarkTheme,
                                    onOpenIn3DApp = { openIn3DApp(detectedRoom) },
                                    headerSlot = {}
                                )
                            }} else null
                        )
                    }
                    FullscreenPane.SECOND -> {
                        PdfPaneWithFloatingControls(
                            jobRepository = jobRepository,
                            jobFolderName = jobFolderName,
                            isDarkTheme = isDarkTheme,
                            title = secondSourceName,
                            pdfFilename = secondSourceFilename,
                            currentPage = if (secondPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                assemblyPage
                            } else {
                                sourcePage(secondPaneSource, secondPaneOtherPage, secondPaneDeliveryPage)
                            },
                            totalPages = secondPaneTotalPages,
                            onCurrentPageChange = { nextPage ->
                                setSourcePage(
                                    source = secondPaneSource,
                                    nextPage = nextPage,
                                    setOther = { secondPaneOtherPage = it },
                                    setDelivery = { secondPaneDeliveryPage = it }
                                )
                            },
                            onTotalPagesChanged = {
                                secondPaneTotalPages = if (secondPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                    assemblyVirtualTotalPages
                                } else {
                                    it
                                }
                            },
                            tocRequestToken = secondPaneTocRequestToken,
                            onOpenToc = {
                                secondPaneTocRequestToken++
                            },
                            virtualMapping = if (secondPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                assemblyVirtualMapping
                            } else {
                                null
                            },
                            navigatorCabinetToPages = if (secondPaneSource == PaneSource.ASSEMBLY) {
                                assemblyNavigatorCabinetToPages
                            } else if (secondPaneSource == PaneSource.PLANS) {
                                sheetIndex?.documents?.plansElevations?.cabinetToPages.orEmpty()
                            } else {
                                emptyMap()
                            },
                            navigatorPlanViewLabels = if (secondPaneSource == PaneSource.PLANS) {
                                plansNavigatorPlanViewLabels
                            } else {
                                emptyMap()
                            },
                            navigatorWarningMessage = if (secondPaneSource == PaneSource.ASSEMBLY) {
                                assemblyVirtualSanitized.warningMessage
                            } else {
                                null
                            },
                            missingText = secondMissingText,
                            unreadableText = secondUnreadableText,
                            sourceControlsInline = {
                                PaneSourceControlsInline(
                                    selectedSource = secondPaneSource,
                                    selectedOtherFilename = secondPaneOtherFilename,
                                    hasOtherOptions = unmanagedOtherPdfNames.isNotEmpty(),
                                    onSelectSource = { secondPaneSource = it },
                                    onOpenOtherPicker = { otherPickerTarget = PaneSlot.SECOND }
                                )
                            },
                            isFullscreen = true,
                            onToggleFullscreen = { fullscreenPane = FullscreenPane.NONE },
                            showControls = showUi,
                            onSingleTap = { showUi = !showUi; onUiVisibilityChanged(showUi) },
                            customContent = if (secondPaneSource == PaneSource.CHECKLIST) {{
                                ChecklistPane(
                                    modifier = Modifier.fillMaxSize(),
                                    jobFolderName = jobFolderName,
                                    specialtyStateStore = specialtyStateStore,
                                    onJumpToCabinet = { cab -> jumpToCabinet(cab) }
                                )
                            }} else if (secondPaneSource == PaneSource.THREE_D) {{
                                Model3DPane(
                                    modifier = Modifier.fillMaxSize(),
                                    folderName = jobFolderName,
                                    roomName = detectedRoom,
                                    serverPort = serverPort,
                                    serverError = viewerServerError,
                                    isDarkTheme = isDarkTheme,
                                    onOpenIn3DApp = { openIn3DApp(detectedRoom) },
                                    headerSlot = {}
                                )
                            }} else null
                        )
                    }
                    FullscreenPane.NONE -> {
                        AdaptiveSplitLayout(
                            modifier = Modifier.fillMaxSize(),
                            initialFirstWeight = if (isLandscape) 0.5f else 0.47f,
                            firstContent = { paneModifier ->
                                PdfPaneWithFloatingControls(
                                    modifier = paneModifier,
                                    jobRepository = jobRepository,
                                    jobFolderName = jobFolderName,
                                    isDarkTheme = isDarkTheme,
                                    title = firstSourceName,
                                    compactArrows = true,
                                    pdfFilename = firstSourceFilename,
                                    currentPage = if (firstPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                        assemblyPage
                                    } else {
                                        sourcePage(firstPaneSource, firstPaneOtherPage, firstPaneDeliveryPage)
                                    },
                                    totalPages = firstPaneTotalPages,
                                    onCurrentPageChange = { nextPage ->
                                        setSourcePage(
                                            source = firstPaneSource,
                                            nextPage = nextPage,
                                            setOther = { firstPaneOtherPage = it },
                                            setDelivery = { firstPaneDeliveryPage = it }
                                        )
                                    },
                                    onTotalPagesChanged = {
                                        firstPaneTotalPages = if (firstPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                            assemblyVirtualTotalPages
                                        } else {
                                            it
                                        }
                                    },
                                    tocRequestToken = firstPaneTocRequestToken,
                                    onOpenToc = {
                                        firstPaneTocRequestToken++
                                    },
                                    virtualMapping = if (firstPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                        assemblyVirtualMapping
                                    } else {
                                        null
                                    },
                                    navigatorCabinetToPages = if (firstPaneSource == PaneSource.ASSEMBLY) {
                                        assemblyNavigatorCabinetToPages
                                    } else if (firstPaneSource == PaneSource.PLANS) {
                                        sheetIndex?.documents?.plansElevations?.cabinetToPages.orEmpty()
                                    } else {
                                        emptyMap()
                                    },
                                    navigatorPlanViewLabels = if (firstPaneSource == PaneSource.PLANS) {
                                        plansNavigatorPlanViewLabels
                                    } else {
                                        emptyMap()
                                    },
                                    navigatorWarningMessage = if (firstPaneSource == PaneSource.ASSEMBLY) {
                                        assemblyVirtualSanitized.warningMessage
                                    } else {
                                        null
                                    },
                                    missingText = firstMissingText,
                                    unreadableText = firstUnreadableText,
                                    sourceControlsInline = {
                                        PaneSourceControlsInline(
                                            selectedSource = firstPaneSource,
                                            selectedOtherFilename = firstPaneOtherFilename,
                                            hasOtherOptions = unmanagedOtherPdfNames.isNotEmpty(),
                                            onSelectSource = { firstPaneSource = it },
                                            onOpenOtherPicker = { otherPickerTarget = PaneSlot.FIRST }
                                        )
                                    },
                                    onToggleFullscreen = { fullscreenPane = FullscreenPane.FIRST },
                                    showControls = showUi,
                                    onSingleTap = { showUi = !showUi; onUiVisibilityChanged(showUi) },
                                    // In portrait the first pane's bottom edge is the divider, not the nav bar
                                    hasNavBarBelow = isLandscape,
                                    customContent = if (firstPaneSource == PaneSource.CHECKLIST) {{
                                        ChecklistPane(
                                            modifier = Modifier.fillMaxSize(),
                                            jobFolderName = jobFolderName,
                                            specialtyStateStore = specialtyStateStore,
                                            onJumpToCabinet = { cab -> jumpToCabinet(cab) }
                                        )
                                    }} else if (firstPaneSource == PaneSource.THREE_D) {{
                                        Model3DPane(
                                            modifier = Modifier.fillMaxSize(),
                                            folderName = jobFolderName,
                                            roomName = detectedRoom,
                                            serverPort = serverPort,
                                    serverError = viewerServerError,
                                    isDarkTheme = isDarkTheme,
                                            onFullScreen = { fullscreenPane = FullscreenPane.FIRST },
                                            onOpenIn3DApp = { openIn3DApp(detectedRoom) },
                                            headerSlot = {}
                                        )
                                    }} else null
                                )
                            },
                            secondContent = { paneModifier ->
                                PdfPaneWithFloatingControls(
                                    modifier = paneModifier,
                                    jobRepository = jobRepository,
                                    jobFolderName = jobFolderName,
                                    isDarkTheme = isDarkTheme,
                                    title = secondSourceName,
                                    compactArrows = true,
                                    pdfFilename = secondSourceFilename,
                                    currentPage = if (secondPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                        assemblyPage
                                    } else {
                                        sourcePage(secondPaneSource, secondPaneOtherPage, secondPaneDeliveryPage)
                                    },
                                    totalPages = secondPaneTotalPages,
                                    onCurrentPageChange = { nextPage ->
                                        setSourcePage(
                                            source = secondPaneSource,
                                            nextPage = nextPage,
                                            setOther = { secondPaneOtherPage = it },
                                            setDelivery = { secondPaneDeliveryPage = it }
                                        )
                                    },
                                    onTotalPagesChanged = {
                                        secondPaneTotalPages = if (secondPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                            assemblyVirtualTotalPages
                                        } else {
                                            it
                                        }
                                    },
                                    tocRequestToken = secondPaneTocRequestToken,
                                    onOpenToc = {
                                        secondPaneTocRequestToken++
                                    },
                                    virtualMapping = if (secondPaneSource == PaneSource.ASSEMBLY && hasVirtualAssembly) {
                                        assemblyVirtualMapping
                                    } else {
                                        null
                                    },
                                    navigatorCabinetToPages = if (secondPaneSource == PaneSource.ASSEMBLY) {
                                        assemblyNavigatorCabinetToPages
                                    } else if (secondPaneSource == PaneSource.PLANS) {
                                        sheetIndex?.documents?.plansElevations?.cabinetToPages.orEmpty()
                                    } else {
                                        emptyMap()
                                    },
                                    navigatorPlanViewLabels = if (secondPaneSource == PaneSource.PLANS) {
                                        plansNavigatorPlanViewLabels
                                    } else {
                                        emptyMap()
                                    },
                                    navigatorWarningMessage = if (secondPaneSource == PaneSource.ASSEMBLY) {
                                        assemblyVirtualSanitized.warningMessage
                                    } else {
                                        null
                                    },
                                    missingText = secondMissingText,
                                    unreadableText = secondUnreadableText,
                                    sourceControlsInline = {
                                        PaneSourceControlsInline(
                                            selectedSource = secondPaneSource,
                                            selectedOtherFilename = secondPaneOtherFilename,
                                            hasOtherOptions = unmanagedOtherPdfNames.isNotEmpty(),
                                            onSelectSource = { secondPaneSource = it },
                                            onOpenOtherPicker = { otherPickerTarget = PaneSlot.SECOND }
                                        )
                                    },
                                    onToggleFullscreen = { fullscreenPane = FullscreenPane.SECOND },
                                    showControls = showUi,
                                    onSingleTap = { showUi = !showUi; onUiVisibilityChanged(showUi) },
                                    customContent = if (secondPaneSource == PaneSource.CHECKLIST) {{
                                        ChecklistPane(
                                            modifier = Modifier.fillMaxSize(),
                                            jobFolderName = jobFolderName,
                                            specialtyStateStore = specialtyStateStore,
                                            onJumpToCabinet = { cab -> jumpToCabinet(cab) }
                                        )
                                    }} else if (secondPaneSource == PaneSource.THREE_D) {{
                                        Model3DPane(
                                            modifier = Modifier.fillMaxSize(),
                                            folderName = jobFolderName,
                                            roomName = detectedRoom,
                                            serverPort = serverPort,
                                    serverError = viewerServerError,
                                    isDarkTheme = isDarkTheme,
                                            onFullScreen = { fullscreenPane = FullscreenPane.SECOND },
                                            onOpenIn3DApp = { openIn3DApp(detectedRoom) },
                                            headerSlot = {}
                                        )
                                    }} else null
                                )
                            }
                        )
                    }
                }
            }

            // Search bar lives inside the floating nav bar pill (see NavBarDecoration.kt).
            // State stays here; we push it up on every frame via SideEffect.
            val navBarDeco = LocalNavBarDecoration.current
            val currentSearchText = searchText  // read during composition → subscribes to state, triggers recompose on change
            SideEffect {
                navBarDeco.searchDecoration = if (showUi) {
                    NavBarSearchDecoration(
                        searchTextValue = currentSearchText,
                        onSearchTextChange = { searchText = it },
                        onGo = { jumpToCabinet(currentSearchText.text) },
                        isPartsEnabled = lastSearchedCabinet.isNotBlank(),
                        onParts = { showPartsSheet = true },
                        contextLine = contextLine
                    )
                } else {
                    null
                }
            }
            DisposableEffect(Unit) {
                onDispose { navBarDeco.searchDecoration = null }
            }
        }
    }

    if (otherPickerTarget != null && unmanagedOtherPdfNames.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { otherPickerTarget = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Other Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(unmanagedOtherPdfNames) { filename ->
                        Button(
                            onClick = {
                                when (otherPickerTarget) {
                                    PaneSlot.FIRST -> {
                                        firstPaneOtherFilename = filename
                                        firstPaneSource = PaneSource.OTHER
                                    }
                                    PaneSlot.SECOND -> {
                                        secondPaneOtherFilename = filename
                                        secondPaneSource = PaneSource.OTHER
                                    }
                                    null -> Unit
                                }
                                otherPickerTarget = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showPartsSheet && cabinetParts != null) {
        val parts = cabinetParts
        if (parts != null) {
            ModalBottomSheet(
                onDismissRequest = { showPartsSheet = false }
            ) {
                PartsChecklistSheet(parts = parts)
            }
        }
    }
}

@Composable
private fun PdfPaneWithFloatingControls(
    modifier: Modifier = Modifier,
    jobRepository: JobRepository,
    jobFolderName: String,
    isDarkTheme: Boolean,
    title: String,
    pdfFilename: String?,
    currentPage: Int,
    totalPages: Int,
    onCurrentPageChange: (Int) -> Unit,
    onTotalPagesChanged: (Int) -> Unit,
    tocRequestToken: Int = 0,
    onOpenToc: () -> Unit,
    virtualMapping: UnifiedVirtualPageMapping? = null,
    navigatorCabinetToPages: Map<String, List<Int>> = emptyMap(),
    navigatorPlanViewLabels: Map<Int, String> = emptyMap(),
    navigatorWarningMessage: String? = null,
    missingText: String = "$title PDF not found",
    unreadableText: String = "Unable to read $title",
    sourceControlsInline: (@Composable RowScope.() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit,
    showControls: Boolean = true,
    onSingleTap: (() -> Unit)? = null,
    compactArrows: Boolean = false,
    customContent: (@Composable () -> Unit)? = null,
    // true for fullscreen panes and the bottom/right pane in split view (nav bar below them);
    // false for the top pane in portrait split (bottom edge is the divider, not the nav bar)
    hasNavBarBelow: Boolean = true
) {
    // Two truly independent inset layers:
    //
    // Layer 1 — canvasPad (modifier): sizes the white Surface itself.
    //   8dp from every screen edge when UI is visible → consistent canvas frame.
    //   Collapses to 0dp when UI is hidden → true edge-to-edge full screen.
    //
    // Layer 2 — contentPadding (inside the Surface): positions the PDF fit-to-page
    //   between the floating control pill and the nav bar. The Surface stays full-size;
    //   only the PDF content origin shifts. ZoomablePdfImage's graphicsLayer transforms
    //   can pan/zoom back into the bar areas (the Surface is not clipped to the content area).
    val canvasPad by animateDpAsState(
        targetValue = if (showControls) 8.dp else 0.dp,
        animationSpec = tween(220),
        label = "canvasPad"
    )
    val topBarPad by animateDpAsState(
        targetValue = if (showControls) 58.dp else 0.dp,
        animationSpec = tween(220),
        label = "topBarPad"
    )
    val botBarPad by animateDpAsState(
        targetValue = if (showControls && hasNavBarBelow) 112.dp else 0.dp,
        animationSpec = tween(220),
        label = "botBarPad"
    )
    val hazeState = remember { HazeState() }

    // Root Box: blue-grey background fills the full pane edge-to-edge.
    // The canvasPad inset reveals that blue-grey as a consistent 8dp border on all sides.
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (customContent != null) {
            Box(Modifier.fillMaxSize().padding(canvasPad).hazeSource(hazeState)) {
                customContent()
            }
        } else {
            UnifiedReferenceViewer(
                // canvasPad only — this is what sizes the white canvas
                modifier = Modifier
                    .fillMaxSize()
                    .padding(canvasPad)
                    .hazeSource(hazeState),
                displayPage = currentPage,
                onDisplayPageChange = onCurrentPageChange,
                defaultPdfFilename = pdfFilename.orEmpty(),
                pdfFileForFilename = { filename ->
                    jobRepository.getJobRootPdfFile(
                        jobFolderName = jobFolderName,
                        pdfFilename = filename,
                        preferDarkMode = isDarkTheme
                    )
                },
                preferDarkMode = isDarkTheme,
                virtualMapping = virtualMapping,
                navigatorCabinetToPages = navigatorCabinetToPages,
                navigatorPlanViewLabels = navigatorPlanViewLabels,
                navigatorWarningMessage = navigatorWarningMessage,
                missingText = missingText,
                unreadableText = unreadableText,
                onTotalPagesChanged = onTotalPagesChanged,
                showHeaderRow = false,
                showNavigationButtons = false,
                innerPadding = 0.dp,
                tocRequestToken = tocRequestToken,
                onSingleTap = onSingleTap,
                compactArrows = compactArrows,
                // contentPadding only — shifts PDF fit-to-page between bars,
                // independent of the canvas size
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = topBarPad,
                    bottom = botBarPad,
                    start = 0.dp,
                    end = 0.dp
                )
            )
        }

        // Control pill: expands/collapses from centre like a CRT powering on/off
        AnimatedVisibility(
            visible = showControls,
            enter = expandVertically(animationSpec = tween(220), expandFrom = Alignment.CenterVertically),
            exit = shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.CenterVertically) +
                   fadeOut(animationSpec = tween(160))
        ) {
            Surface(
                color = Color.Transparent,
                shadowElevation = 4.dp,
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier.hazeEffect(
                        hazeState,
                        style = HazeDefaults.style(
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                            blurRadius = 25.dp
                        )
                    )
                ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (sourceControlsInline != null) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = sourceControlsInline
                        )
                    }
                    if (customContent == null) {
                        IconButton(
                            onClick = onOpenToc,
                            enabled = totalPages > 0,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.UnfoldMore, contentDescription = "Sheet list", modifier = Modifier.size(20.dp))
                        }
                        Text(
                            "$currentPage/${totalPages.coerceAtLeast(0)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                }
            }
        } // end AnimatedVisibility
    }
}

@Composable
private fun RowScope.PaneSourceControlsInline(
    selectedSource: PaneSource,
    selectedOtherFilename: String?,
    hasOtherOptions: Boolean,
    onSelectSource: (PaneSource) -> Unit,
    onOpenOtherPicker: () -> Unit
) {
    FilterChip(
        selected = selectedSource == PaneSource.PLANS,
        onClick = { onSelectSource(PaneSource.PLANS) },
        label = { Text("Plans") },
        shape = MaterialTheme.shapes.small
    )
    FilterChip(
        selected = selectedSource == PaneSource.ASSEMBLY,
        onClick = { onSelectSource(PaneSource.ASSEMBLY) },
        label = { Text("Assembly") },
        shape = MaterialTheme.shapes.small
    )
    FilterChip(
        selected = selectedSource == PaneSource.DELIVERY,
        onClick = { onSelectSource(PaneSource.DELIVERY) },
        label = { Text("Delivery") },
        shape = MaterialTheme.shapes.small
    )
    FilterChip(
        selected = selectedSource == PaneSource.THREE_D,
        onClick = { onSelectSource(PaneSource.THREE_D) },
        label = { Text("3D") },
        shape = MaterialTheme.shapes.small
    )
    FilterChip(
        selected = selectedSource == PaneSource.CHECKLIST,
        onClick = { onSelectSource(PaneSource.CHECKLIST) },
        label = { Text("Checklist") },
        shape = MaterialTheme.shapes.small
    )
    if (!selectedOtherFilename.isNullOrBlank()) {
        FilterChip(
            selected = selectedSource == PaneSource.OTHER,
            onClick = { onSelectSource(PaneSource.OTHER) },
            label = {
                Text(
                    "Other: $selectedOtherFilename",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            shape = MaterialTheme.shapes.small
        )
    }
    if (hasOtherOptions) {
        Button(onClick = onOpenOtherPicker, shape = MaterialTheme.shapes.small) {
            Text("Other Files")
        }
    }
}

@Composable
private fun PartsChecklistSheet(parts: AssemblyCabinetParts) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "Cabinet ${parts.cabinetNumber} - Parts",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        if (parts.bom.isNotEmpty()) {
            val grouped = parts.bom.groupBy { it.part.sectionType.ifBlank { "Unspecified" } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                grouped.forEach { (section, entries) ->
                    item {
                        Text(
                            section,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(entries) { entry ->
                        BomPartRow(entry)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (parts.cncParts.isNotEmpty()) {
                    item {
                        Text(
                            "CNC",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(parts.cncParts) { cncPart -> CncPartRow(cncPart) }
                }
                if (parts.hardwoodRows.isNotEmpty()) {
                    item {
                        Text(
                            "Hardwoods",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(parts.hardwoodRows) { hwRow -> HardwoodPartRow(hwRow) }
                }
                if (parts.cncParts.isEmpty() && parts.hardwoodRows.isEmpty()) {
                    item {
                        Text(
                            "No indexed parts found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ChecklistPane(
    modifier: Modifier = Modifier,
    jobFolderName: String,
    specialtyStateStore: SpecialtyStateStore,
    onJumpToCabinet: (String) -> Unit
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val resolvedItems = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        specialtyStateStore.getResolvedItems(jobFolderName)
    }
    val completionOverrides = remember(jobFolderName) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val inFlight = remember(jobFolderName) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Surface(
        modifier = modifier,
        tonalElevation = 3.dp
    ) {
        if (resolvedItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (scanState.status == ScanStatus.LOADING) "Loading checklist..." else "No checklist items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        "Checklist ${resolvedItems.count { row -> completionOverrides[row.item.id] ?: row.isComplete }}/${resolvedItems.size}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(resolvedItems, key = { it.item.id }) { resolved ->
                    val item = resolved.item
                    val checked = completionOverrides[item.id] ?: resolved.isComplete
                    val enabled = inFlight[item.id] != true
                    val stationText = item.stations.joinToString(" • ") { s -> s.name.replace('_', ' ') }
                    val cabinetText = item.cabinetNumbers.joinToString(", ").let { if (it.isNotBlank()) "#$it" else "" }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (checked) 0.65f else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = enabled,
                            onCheckedChange = { next ->
                                val previous = completionOverrides[item.id] ?: checked
                                completionOverrides[item.id] = next
                                inFlight[item.id] = true
                                coroutineScope.launch {
                                    try {
                                        specialtyStateStore.setItemCompletion(
                                            jobFolderName = jobFolderName,
                                            itemId = item.id,
                                            completed = next
                                        )
                                        completionOverrides.remove(item.id)
                                    } catch (_: Exception) {
                                        completionOverrides[item.id] = previous
                                    } finally {
                                        inFlight.remove(item.id)
                                    }
                                }
                            }
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = if (cabinetText.isNotBlank()) "$cabinetText - ${item.name}" else item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (stationText.isNotBlank()) {
                                Text(
                                    text = stationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (item.cabinetNumbers.isNotEmpty()) {
                            Button(
                                onClick = { onJumpToCabinet(item.cabinetNumbers.first()) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Text("View", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BomPartRow(entry: AssemblyBomEntry) {
    val (label, bg, fg) = when {
        entry.part.isPurchased -> Triple("Purchased", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        entry.cncParts.isNotEmpty() -> {
            val anyBad = entry.cncParts.any { it.isBadPart || it.sheetStatus == SheetStatus.HAS_BAD_PARTS }
            val allComplete = entry.cncParts.all { it.sheetStatus == SheetStatus.COMPLETE }
            val anySkipped = entry.cncParts.any { it.sheetStatus == SheetStatus.SKIPPED }
            when {
                anyBad -> Triple("CNC - Bad Part", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                allComplete -> Triple("CNC - Complete", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                anySkipped -> Triple("CNC - Skipped", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                else -> Triple("CNC - Not Started", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        entry.hardwoodRows.isNotEmpty() -> {
            val qty = entry.hardwoodRows.sumOf { it.qty.coerceAtLeast(0) }
            val done = entry.hardwoodRows.sumOf { it.doneCount.coerceAtLeast(0) }
            val anyBad = entry.hardwoodRows.any { it.badCount > 0 }
            when {
                anyBad -> Triple("HW - Bad", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                done >= qty && qty > 0 -> Triple("HW - $done/$qty", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                done > 0 -> Triple("HW - $done/$qty", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                else -> Triple("HW - 0/$qty", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> Triple("Not Indexed", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${entry.part.qty} x ${entry.part.description}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${entry.part.width}\" x ${entry.part.length}\" • ${entry.part.material}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusChip(text = label, backgroundColor = bg, contentColor = fg)
        }
    }
}

@Composable
private fun CncPartRow(part: AssemblyCncPart) {
    val (label, bg, fg) = when {
        part.isBadPart || part.sheetStatus == SheetStatus.HAS_BAD_PARTS ->
            Triple("CNC - Bad", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        part.sheetStatus == SheetStatus.COMPLETE ->
            Triple("CNC - Done", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        part.sheetStatus == SheetStatus.SKIPPED ->
            Triple("CNC - Skipped", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else ->
            Triple("CNC - Not Started", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    part.partName.ifBlank { "Part #${part.partNumber}" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${part.width}\" x ${part.length}\" • ${part.materialName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusChip(text = label, backgroundColor = bg, contentColor = fg)
        }
    }
}

@Composable
private fun HardwoodPartRow(row: AssemblyHardwoodRow) {
    val qty = row.qty.coerceAtLeast(0)
    val done = row.doneCount.coerceAtLeast(0)
    val (label, bg, fg) = when {
        row.badCount > 0 ->
            Triple("HW - Bad", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        row.skipped ->
            Triple("HW - Skipped", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        done >= qty && qty > 0 ->
            Triple("HW - $done/$qty", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        done > 0 ->
            Triple("HW - $done/$qty", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else ->
            Triple("HW - 0/$qty", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = listOfNotNull(
                    row.material?.takeIf { it.isNotBlank() },
                    "${row.width} x ${row.length}"
                ).joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StatusChip(text = label, backgroundColor = bg, contentColor = fg)
        }
    }
}

private fun AssemblyPaneView.toPaneSource(): PaneSource = when (this) {
    AssemblyPaneView.ASSEMBLY -> PaneSource.ASSEMBLY
    AssemblyPaneView.PLANS -> PaneSource.PLANS
    AssemblyPaneView.DELIVERY -> PaneSource.DELIVERY
    AssemblyPaneView.THREE_D -> PaneSource.THREE_D
    AssemblyPaneView.CHECKLIST -> PaneSource.CHECKLIST
}
