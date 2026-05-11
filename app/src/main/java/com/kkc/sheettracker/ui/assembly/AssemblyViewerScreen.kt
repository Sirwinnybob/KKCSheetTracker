package com.kkc.sheettracker.ui.assembly

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.AdaptiveSplitLayout
import com.kkc.sheettracker.ui.components.ReferencePdfPane
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.viewer3d.Model3DPane
import com.kkc.sheettracker.viewer3d.ViewerServer
import java.io.File
import kotlinx.coroutines.launch

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
    THREE_D
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
    jobFolderName: String,
    basePath: String = "",
    startPageAssembly: Int,
    startPagePlans: Int,
    initialSource: String? = null,
    initialCabinet: String? = null,
    initialRoom: String? = null,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val sheetIndex = remember(jobFolderName) { assemblyStateStore.getCabinetSheetIndex(jobFolderName) }
    fun parseInitialSource(raw: String?): PaneSource? = when (raw?.trim()?.lowercase()) {
        "3d", "three_d", "threed" -> PaneSource.THREE_D
        "assembly" -> PaneSource.ASSEMBLY
        "plans", "plans_elevations", "elevations" -> PaneSource.PLANS
        else -> null
    }
    val initialPaneSource = parseInitialSource(initialSource)

    val assemblyFilename = remember(sheetIndex, jobFolderName) {
        sheetIndex?.documents?.assembly?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY)
            ?: ""
    }
    val plansFilename = remember(sheetIndex, jobFolderName) {
        sheetIndex?.documents?.plansElevations?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            ?: ""
    }
    val pdfCatalog = remember(jobFolderName) {
        jobRepository.getJobPdfCatalog(jobFolderName)
    }
    val deliveryFilename = remember(pdfCatalog.deliverySheet) {
        pdfCatalog.deliverySheet?.pdfFilename.orEmpty()
    }
    val unmanagedOtherPdfNames = remember(pdfCatalog.otherDocs) {
        pdfCatalog.otherDocs.map { it.pdfFilename }
    }

    val assemblyPdfFile = remember(jobFolderName, assemblyFilename, isDarkTheme) {
        if (assemblyFilename.isBlank()) null
        else jobRepository.getJobRootPdfFile(jobFolderName, assemblyFilename, preferDarkMode = isDarkTheme)
    }
    val plansPdfFile = remember(jobFolderName, plansFilename, isDarkTheme) {
        if (plansFilename.isBlank()) null
        else jobRepository.getJobRootPdfFile(jobFolderName, plansFilename, preferDarkMode = isDarkTheme)
    }

    var assemblyPage by rememberSaveable(assemblyPdfFile?.absolutePath, startPageAssembly) {
        mutableIntStateOf(startPageAssembly.coerceAtLeast(1))
    }
    var plansPage by rememberSaveable(plansPdfFile?.absolutePath, startPagePlans) {
        mutableIntStateOf(startPagePlans.coerceAtLeast(1))
    }
    var searchText by rememberSaveable { mutableStateOf("") }
    var lastSearchedCabinet by rememberSaveable { mutableStateOf("") }
    var contextLine by remember { mutableStateOf("") }
    var showPartsSheet by remember { mutableStateOf(false) }
    var fullscreenPane by rememberSaveable(initialSource) {
        mutableStateOf(if (initialPaneSource == PaneSource.THREE_D) FullscreenPane.FIRST else FullscreenPane.NONE)
    }
    var firstPaneSource by rememberSaveable(initialSource) {
        mutableStateOf(initialPaneSource ?: PaneSource.PLANS)
    }
    var secondPaneSource by rememberSaveable { mutableStateOf(PaneSource.ASSEMBLY) }
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

    val context = LocalContext.current
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

    val cabinetParts = remember(jobFolderName, lastSearchedCabinet) {
        lastSearchedCabinet.takeIf { it.isNotBlank() }?.let {
            assemblyStateStore.deriveCabinetParts(jobFolderName, it)
        }
    }

    fun extractRoomFolder(roomText: String?): String? =
        roomText?.let {
            val raw = Regex("""\(([^)]+)\)""").find(it)?.groupValues?.get(1)?.uppercase()
                ?: it.uppercase().takeIf { it.isNotBlank() }
            raw?.replace(Regex("""[/\\:*?"<>|]"""), " ")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.takeIf { s -> s.isNotBlank() }
        }

    fun roomForAssemblyPage(page: Int): String? =
        extractRoomFolder(sheetIndex?.documents?.assembly?.pageDetails?.get(page.toString())?.room)

    fun firstAlphabeticalRoomFromIndex(): Pair<String, Int>? {
        return sheetIndex?.documents?.assembly?.pageDetails
            ?.mapNotNull { (pageKey, detail) ->
                val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                val room = extractRoomFolder(detail.room) ?: return@mapNotNull null
                room to page
            }
            ?.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            ?.firstOrNull()
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
        if (shouldAutoJumpCabinet && !initialCabinet.isNullOrBlank() && lastSearchedCabinet.isBlank()) {
            searchText = initialCabinet
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
    }

    fun sourceFilename(source: PaneSource, otherFilename: String?): String? = when (source) {
        PaneSource.PLANS -> plansFilename.takeIf { it.isNotBlank() }
        PaneSource.ASSEMBLY -> assemblyFilename.takeIf { it.isNotBlank() }
        PaneSource.DELIVERY -> deliveryFilename.takeIf { it.isNotBlank() }
        PaneSource.OTHER -> otherFilename?.takeIf { it.isNotBlank() }
        PaneSource.THREE_D -> null
    }

    fun sourcePage(source: PaneSource, otherPage: Int, deliveryPage: Int): Int = when (source) {
        PaneSource.PLANS -> plansPage
        PaneSource.ASSEMBLY -> assemblyPage
        PaneSource.DELIVERY -> deliveryPage
        PaneSource.OTHER -> otherPage
        PaneSource.THREE_D -> 1
    }

    fun setSourcePage(source: PaneSource, nextPage: Int, setOther: (Int) -> Unit, setDelivery: (Int) -> Unit) {
        when (source) {
            PaneSource.PLANS -> plansPage = nextPage
            PaneSource.ASSEMBLY -> assemblyPage = nextPage
            PaneSource.DELIVERY -> setDelivery(nextPage)
            PaneSource.OTHER -> setOther(nextPage)
            PaneSource.THREE_D -> Unit
        }
    }

    val firstSourceName = sourceLabel(firstPaneSource)
    val secondSourceName = sourceLabel(secondPaneSource)
    val firstSourceFilename = sourceFilename(firstPaneSource, firstPaneOtherFilename)
    val secondSourceFilename = sourceFilename(secondPaneSource, secondPaneOtherFilename)
    val firstSourcePdfFile = remember(jobFolderName, firstSourceFilename, isDarkTheme) {
        if (firstSourceFilename.isNullOrBlank()) null
        else jobRepository.getJobRootPdfFile(jobFolderName, firstSourceFilename, preferDarkMode = isDarkTheme)
    }
    val secondSourcePdfFile = remember(jobFolderName, secondSourceFilename, isDarkTheme) {
        if (secondSourceFilename.isNullOrBlank()) null
        else jobRepository.getJobRootPdfFile(jobFolderName, secondSourceFilename, preferDarkMode = isDarkTheme)
    }
    val firstMissingText = remember(firstPaneSource, firstPaneOtherFilename, firstSourceFilename) {
        when (firstPaneSource) {
            PaneSource.OTHER -> when {
                firstPaneOtherFilename.isNullOrBlank() -> "Select an Other PDF"
                firstSourceFilename.isNullOrBlank() -> "Selected Other file is unavailable"
                else -> "Selected Other file unavailable: $firstPaneOtherFilename"
            }
            PaneSource.THREE_D -> "Select a part/room target to load its 3D model"
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

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (fullscreenPane) {
                    FullscreenPane.FIRST -> {
                        PdfPaneWithFloatingControls(
                            title = firstSourceName,
                            pdfFile = firstSourcePdfFile,
                            currentPage = sourcePage(firstPaneSource, firstPaneOtherPage, firstPaneDeliveryPage),
                            totalPages = firstPaneTotalPages,
                            onCurrentPageChange = { nextPage ->
                                setSourcePage(
                                    source = firstPaneSource,
                                    nextPage = nextPage,
                                    setOther = { firstPaneOtherPage = it },
                                    setDelivery = { firstPaneDeliveryPage = it }
                                )
                            },
                            onTotalPagesChanged = { firstPaneTotalPages = it },
                            tocRequestToken = firstPaneTocRequestToken,
                            onOpenToc = { firstPaneTocRequestToken++ },
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
                            customContent = if (firstPaneSource == PaneSource.THREE_D) {{
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
                            title = secondSourceName,
                            pdfFile = secondSourcePdfFile,
                            currentPage = sourcePage(secondPaneSource, secondPaneOtherPage, secondPaneDeliveryPage),
                            totalPages = secondPaneTotalPages,
                            onCurrentPageChange = { nextPage ->
                                setSourcePage(
                                    source = secondPaneSource,
                                    nextPage = nextPage,
                                    setOther = { secondPaneOtherPage = it },
                                    setDelivery = { secondPaneDeliveryPage = it }
                                )
                            },
                            onTotalPagesChanged = { secondPaneTotalPages = it },
                            tocRequestToken = secondPaneTocRequestToken,
                            onOpenToc = { secondPaneTocRequestToken++ },
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
                            customContent = if (secondPaneSource == PaneSource.THREE_D) {{
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
                            initialFirstWeight = 0.5f,
                            firstContent = { paneModifier ->
                                PdfPaneWithFloatingControls(
                                    modifier = paneModifier,
                                    title = firstSourceName,
                                    pdfFile = firstSourcePdfFile,
                                    currentPage = sourcePage(firstPaneSource, firstPaneOtherPage, firstPaneDeliveryPage),
                                    totalPages = firstPaneTotalPages,
                                    onCurrentPageChange = { nextPage ->
                                        setSourcePage(
                                            source = firstPaneSource,
                                            nextPage = nextPage,
                                            setOther = { firstPaneOtherPage = it },
                                            setDelivery = { firstPaneDeliveryPage = it }
                                        )
                                    },
                                    onTotalPagesChanged = { firstPaneTotalPages = it },
                                    tocRequestToken = firstPaneTocRequestToken,
                                    onOpenToc = { firstPaneTocRequestToken++ },
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
                                    customContent = if (firstPaneSource == PaneSource.THREE_D) {{
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
                                    title = secondSourceName,
                                    pdfFile = secondSourcePdfFile,
                                    currentPage = sourcePage(secondPaneSource, secondPaneOtherPage, secondPaneDeliveryPage),
                                    totalPages = secondPaneTotalPages,
                                    onCurrentPageChange = { nextPage ->
                                        setSourcePage(
                                            source = secondPaneSource,
                                            nextPage = nextPage,
                                            setOther = { secondPaneOtherPage = it },
                                            setDelivery = { secondPaneDeliveryPage = it }
                                        )
                                    },
                                    onTotalPagesChanged = { secondPaneTotalPages = it },
                                    tocRequestToken = secondPaneTocRequestToken,
                                    onOpenToc = { secondPaneTocRequestToken++ },
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
                                    customContent = if (secondPaneSource == PaneSource.THREE_D) {{
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

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Cabinet #") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small
                    )
                    Button(onClick = { jumpToCabinet(searchText) }) {
                        Text("Go")
                    }
                    Button(
                        onClick = { showPartsSheet = true },
                        enabled = lastSearchedCabinet.isNotBlank()
                    ) {
                        Text("Parts")
                    }
                }
                if (contextLine.isNotBlank()) {
                    Text(
                        contextLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
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
        ModalBottomSheet(
            onDismissRequest = { showPartsSheet = false }
        ) {
            PartsChecklistSheet(parts = cabinetParts)
        }
    }
}

@Composable
private fun PdfPaneWithFloatingControls(
    modifier: Modifier = Modifier,
    title: String,
    pdfFile: java.io.File?,
    currentPage: Int,
    totalPages: Int,
    onCurrentPageChange: (Int) -> Unit,
    onTotalPagesChanged: (Int) -> Unit,
    tocRequestToken: Int = 0,
    onOpenToc: () -> Unit,
    missingText: String = "$title PDF not found",
    unreadableText: String = "Unable to read $title",
    sourceControlsInline: (@Composable RowScope.() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit,
    customContent: (@Composable () -> Unit)? = null
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var viewportZoom by remember(pdfFile?.absolutePath) { mutableFloatStateOf(1f) }
    val bottomLift = if (customContent == null && isPortrait && viewportZoom <= 1.02f) 78.dp else 0.dp

    Box(modifier = modifier.fillMaxSize()) {
        if (customContent != null) {
            customContent()
        } else {
            ReferencePdfPane(
                modifier = Modifier.fillMaxSize(),
                pdfFile = pdfFile,
                currentPage = currentPage,
                onCurrentPageChange = onCurrentPageChange,
                missingText = missingText,
                unreadableText = unreadableText,
                onTotalPagesChanged = onTotalPagesChanged,
                onViewportStateChange = { state -> viewportZoom = state.zoom },
                showHeaderRow = false,
                showNavigationButtons = false,
                innerPadding = bottomLift,
                tocRequestToken = tocRequestToken
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
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
                        onClick = { onCurrentPageChange((currentPage - 1).coerceAtLeast(1)) },
                        enabled = totalPages > 0 && currentPage > 1,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", modifier = Modifier.size(20.dp))
                    }
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
                    IconButton(
                        onClick = { onCurrentPageChange((currentPage + 1).coerceAtMost(totalPages.coerceAtLeast(1))) },
                        enabled = totalPages > 0 && currentPage < totalPages,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(20.dp))
                    }
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
        label = { Text("Plans") }
    )
    FilterChip(
        selected = selectedSource == PaneSource.ASSEMBLY,
        onClick = { onSelectSource(PaneSource.ASSEMBLY) },
        label = { Text("Assembly") }
    )
    FilterChip(
        selected = selectedSource == PaneSource.DELIVERY,
        onClick = { onSelectSource(PaneSource.DELIVERY) },
        label = { Text("Delivery") }
    )
    FilterChip(
        selected = selectedSource == PaneSource.THREE_D,
        onClick = { onSelectSource(PaneSource.THREE_D) },
        label = { Text("3D") }
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
            }
        )
    }
    if (hasOtherOptions) {
        Button(onClick = onOpenOtherPicker) {
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
            Text(
                "No parsed BOM found. Showing indexed CNC/Hardwoods matches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text("CNC Parts: ${parts.cncParts.size}", style = MaterialTheme.typography.bodyMedium)
            Text("Hardwood Rows: ${parts.hardwoodRows.size}", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
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

    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
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
