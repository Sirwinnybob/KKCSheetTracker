package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.data.IdlePhase
import com.kkc.sheettracker.ui.components.ClockInButton
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment as UiAlignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import com.kkc.sheettracker.BuildConfig
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.PdfMarkupStore
import com.kkc.sheettracker.data.PreparedPageKey
import com.kkc.sheettracker.data.PreparedStateInvalidationReason
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SheetStatusKey
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor
import com.kkc.sheettracker.ui.components.ImmersiveSystemBars
import com.kkc.sheettracker.ui.components.LocalIdlePhase
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarCncDecoration
import com.kkc.sheettracker.ui.components.PdfViewportState
import com.kkc.sheettracker.ui.components.ResizeHandle
import com.kkc.sheettracker.ui.components.SheetStatusBadge
import com.kkc.sheettracker.ui.components.SortColumn
import com.kkc.sheettracker.ui.components.SortDirection
import com.kkc.sheettracker.ui.components.SortHeader
import com.kkc.sheettracker.ui.components.animateEntrance
import com.kkc.sheettracker.ui.components.VerticalSplitLayout
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.markup.DrawingTool
import com.kkc.sheettracker.ui.markup.PdfMarkupOverlay
import com.kkc.sheettracker.ui.markup.PdfMarkupToolbar
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import com.kkc.sheettracker.ui.markup.rememberPdfMarkupToolState
import com.kkc.sheettracker.ui.theme.DimensionTextStyle
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt
import java.util.ArrayDeque

private val ROOM_PAREN_REGEX = Regex("""\(([^)]+)\)""")
private val ROOM_ILLEGAL_CHARS_REGEX = Regex("""[/\\:*?"<>|]""")
private val ROOM_WHITESPACE_REGEX = Regex("""\s+""")
private const val OCR_TAG = "KKC_OCR"
private const val VIEWER_REF_TAG = "KKC_VIEWER_REF"
private const val VIEWER_PARITY_TAG = "KKC_APP_STATE_PARITY_VIEWER"
private const val VIEWER_PREPARED_TAG = "KKC_PREPARED_STATE"
private const val RENDER_CACHE_MAX_PAGES = 6
private const val RENDER_PREWARM_RADIUS = 2
private val SHEET_BITMAP_INVERSION_COLOR_MATRIX = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
)

private data class RenderedSheetPage(
    val pageBitmap: Bitmap?,
    val diagramBitmap: Bitmap?,
    val renderScale: Float,
    var wasDisplayed: Boolean = false
)

private data class TocSheetInfo(
    val status: SheetStatus,
    val committedBadCount: Int,
    val draftBadCount: Int
)

private fun resolvePageMetadata(material: Material?, page: Int): PageMetadata? {
    val pages = material?.metadata?.pages.orEmpty()
    if (pages.isEmpty()) return null
    val byExactPageNumber = pages.firstOrNull { it.pageNumber == page }
    if (byExactPageNumber != null) return byExactPageNumber

    val byPageIndex = pages.getOrNull((page - 1).coerceAtLeast(0))
    if (byPageIndex != null) return byPageIndex

    val nearestWithParts = pages
        .filter { it.parts.isNotEmpty() }
        .minByOrNull { kotlin.math.abs(it.pageNumber - page) }
    return nearestWithParts ?: pages.firstOrNull()
}

private fun Material.visibleSheetPages(): List<Int> {
    val metadataPages = metadata?.pages.orEmpty()
    val visibleFromMetadata = metadataPages
        .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
        .mapNotNull { md ->
            val p = md.pageNumber
            p.takeIf { it in 1..pageCount }
        }
        .distinct()
        .sorted()
    return if (visibleFromMetadata.isNotEmpty()) visibleFromMetadata else (1..pageCount).toList()
}

private fun Material.resolveHeadPage(page: Int): Int {
    val meta = metadata?.pages?.firstOrNull { it.pageNumber == page } ?: return page
    if (!(meta.hiddenInApp || meta.trackingExcluded || meta.isPartListContinuation)) return page
    val head = meta.continuationHeadPage ?: return page
    return if (head in 1..pageCount) head else page
}

private data class TableLayoutPrefs(
    val numberDp: Float = 30f,
    val widthDp: Float = 55f,
    val lengthDp: Float = 60f,
    val nameWeight: Float = 0.45f,
    val cabDp: Float = 35f,
    val roomDp: Float = 145f,
    val sortColumn: SortColumn = SortColumn.NUMBER,
    val sortDirection: SortDirection = SortDirection.ASC
)

internal data class SheetViewerMarkupStoreConfig(
    val basePath: String,
    val tabletId: String
)

internal fun shouldShowPenMarkupOverlay(
    showFullPdfPage: Boolean,
    penModeEnabled: Boolean
): Boolean = penModeEnabled

internal fun shouldInvertCncSheetBitmap(
    idlePhase: IdlePhase,
    isDarkTheme: Boolean,
    useStandardSheets: Boolean
): Boolean = idlePhase != IdlePhase.ACTIVE || (isDarkTheme && !useStandardSheets)

/** Compose may retain a displayed bitmap in a recorded display list after state changes. */
internal fun shouldRecycleRenderedPageBitmap(bitmap: Bitmap?, wasDisplayed: Boolean): Boolean {
    return bitmap != null && !wasDisplayed && !bitmap.isRecycled
}

internal fun resolveSheetDisplayBitmap(
    showFullPdfPage: Boolean,
    pageBitmap: Bitmap?,
    diagramBitmap: Bitmap?
): Bitmap? = if (showFullPdfPage) pageBitmap else diagramBitmap

internal fun resolveSheetViewerMarkupStoreConfig(
    basePath: String?,
    tabletId: String?
): SheetViewerMarkupStoreConfig? {
    val safeBasePath = basePath?.trim().orEmpty()
    val safeTabletId = tabletId?.trim().orEmpty()
    if (safeBasePath.isBlank() || safeTabletId.isBlank()) return null
    return SheetViewerMarkupStoreConfig(
        basePath = safeBasePath,
        tabletId = safeTabletId
    )
}

internal fun cncSheetViewerUiVisible(): Boolean = true

internal fun cncSheetViewerTitle(jobNumber: String?, materialName: String): String {
    val number = jobNumber?.trim().orEmpty()
    return if (number.isBlank()) materialName else "$number - $materialName"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetViewerScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    jobFolderName: String,
    pdfFilename: String,
    startPage: Int,
    isDarkTheme: Boolean,
    useStandardSheets: Boolean,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeDTarget: (cabinet: String?, assemblyPage: Int?, plansPage: Int?, room: String?) -> Unit,
    onBack: () -> Unit,
    onMaterialUnavailable: () -> Unit = onBack,
    onUiVisibilityChanged: (Boolean) -> Unit = {},
    clockInState: ClockInState? = null
) {
    val idlePhase by LocalIdlePhase.current.collectAsState()
    val invertSheetBitmap = shouldInvertCncSheetBitmap(
        idlePhase = idlePhase,
        isDarkTheme = isDarkTheme,
        useStandardSheets = useStandardSheets
    )
    val scanState by scanCoordinator.state.collectAsState()
    val unifiedEngine = remember(scanState.snapshot.basePath) { UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), BuildConfig.DEBUG) }
    val progressVersion by progressStore.progressVersion.collectAsState()
    val appSheetStatusSnapshots by appStateStore.sheetStatusSnapshots.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appProgressVersion by appStateStore.lastProgressVersion.collectAsState()
    val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }
    val useAppStateStatus = appFlags.viewerStatusEnabled
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val sharedPrefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val trackerPrefs = remember { context.getSharedPreferences("kkc_tracker", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val markupStoreConfig = remember {
        resolveSheetViewerMarkupStoreConfig(
            basePath = trackerPrefs.getString("base_path", null),
            tabletId = trackerPrefs.getString("tablet_id", null)
        )
    }
    val pdfMarkupStore = remember {
        markupStoreConfig?.let { PdfMarkupStore(File(it.basePath), it.tabletId) }
    }

    fun loadTablePrefs(): TableLayoutPrefs {
        val prefix = "table_prefs_v1_"
        val col = sharedPrefs.getString("${prefix}sort_col", SortColumn.NUMBER.name) ?: SortColumn.NUMBER.name
        val dir = sharedPrefs.getString("${prefix}sort_dir", SortDirection.ASC.name) ?: SortDirection.ASC.name
        return TableLayoutPrefs(
            numberDp = sharedPrefs.getFloat("${prefix}num", 30f),
            widthDp = sharedPrefs.getFloat("${prefix}width", 55f),
            lengthDp = sharedPrefs.getFloat("${prefix}length", 60f),
            nameWeight = sharedPrefs.getFloat("${prefix}name_weight", 0.45f),
            cabDp = sharedPrefs.getFloat("${prefix}cab", 35f),
            roomDp = sharedPrefs.getFloat("${prefix}room", 145f),
            sortColumn = runCatching { SortColumn.valueOf(col) }.getOrDefault(SortColumn.NUMBER),
            sortDirection = runCatching { SortDirection.valueOf(dir) }.getOrDefault(SortDirection.ASC)
        )
    }

    val initialPrefs = remember { loadTablePrefs() }
    var currentPage by remember { mutableIntStateOf(startPage) }
    var lastPersistedViewPage by remember(jobFolderName, pdfFilename) { mutableIntStateOf(-1) }
    var lastPersistedViewAtMs by remember(jobFolderName, pdfFilename) { mutableStateOf(0L) }
    var totalPages by remember { mutableIntStateOf(0) }
    var visiblePages by remember { mutableStateOf<List<Int>>(emptyList()) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var diagramBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var jobMaterials by remember { mutableStateOf<List<Material>>(emptyList()) }
    var currentMaterial by remember { mutableStateOf<Material?>(null) }
    val currentPageMetadata = remember(currentMaterial, currentPage) {
        resolvePageMetadata(currentMaterial, currentPage)
    }
    val parts = remember(currentPageMetadata) {
        currentPageMetadata?.parts ?: emptyList()
    }
    val sheetFilesLabel = remember(currentPageMetadata) {
        inferSheetFiles(currentPageMetadata).joinToString("  |  ")
    }
    val sheetSizeLabel = remember(currentPageMetadata) {
        formatSheetDimensions(currentPageMetadata?.sheetDimensions)
    }
    var sheetStatus by remember { mutableStateOf(SheetStatus.NOT_STARTED) }
    var badParts by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var draftBadParts by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedPartNumber by remember { mutableStateOf<Int?>(null) }
    var selectedCabinetNumber by remember { mutableStateOf<Int?>(null) }
    var showReferenceDocDialog by remember { mutableStateOf(false) }
    val referenceModal = com.kkc.sheettracker.ui.components.rememberReferenceModalOverlayState()
    val mainViewRef = rememberMainViewReferenceState()
    // Only pay for the reference-doc lookup (JobRepository.getCabinetSheetIndex plus potential
    // findReferencePdfFilename I/O) when a reference pane is actually shown. The Sheet case
    // (mode == null, the common case) skips rememberReferenceViewerData entirely — mirrors the
    // fix already applied to the popup viewer's ReferenceModalHost (see ReferenceModalOverlay.kt,
    // commit d094237).
    val mainViewReferenceData = mainViewRef.snapshot.mode?.let { mode ->
        rememberReferenceViewerData(
            jobRepository = jobRepository,
            jobFolderName = jobFolderName,
            docType = mode,
            refreshGeneration = scanState.snapshot.generation,
            isDarkTheme = isDarkTheme
        )
    }
    var showFullPdfPage by remember { mutableStateOf(false) }
    var markupEnabled by remember(jobFolderName, pdfFilename) { mutableStateOf(false) }
    var markupStrokesVisible by remember(jobFolderName, pdfFilename) { mutableStateOf(true) }
    val markupToolState = rememberPdfMarkupToolState()
    val localMarkupStrokes = remember(jobFolderName, pdfFilename) { mutableStateListOf<PdfInkStroke>() }
    val localMarkupDeletedIds = remember(jobFolderName, pdfFilename) { mutableStateListOf<String>() }
    val markupChangeGeneration = rememberPdfMarkupChangeGeneration(pdfMarkupStore, jobFolderName)
    var resetZoomTrigger by remember { mutableIntStateOf(0) }
    var showSheetToc by remember { mutableStateOf(false) }
    var showCncSearch by remember { mutableStateOf(false) }
    var showViewerMenu by remember { mutableStateOf(false) }
    var selectedPartType by rememberSaveable { mutableStateOf<String?>(null) }
    var sortColumn by rememberSaveable { mutableStateOf(initialPrefs.sortColumn) }
    var sortDirection by rememberSaveable { mutableStateOf(initialPrefs.sortDirection) }
    var numberColDp by rememberSaveable { mutableFloatStateOf(initialPrefs.numberDp) }
    var widthColDp by rememberSaveable { mutableFloatStateOf(initialPrefs.widthDp) }
    var lengthColDp by rememberSaveable { mutableFloatStateOf(initialPrefs.lengthDp) }
    var nameWeight by rememberSaveable { mutableFloatStateOf(initialPrefs.nameWeight) }
    var cabColDp by rememberSaveable { mutableFloatStateOf(initialPrefs.cabDp) }
    var roomColDp by rememberSaveable { mutableFloatStateOf(initialPrefs.roomDp) }
    var diagramBboxes by remember { mutableStateOf<Map<Int, List<Rect>>>(emptyMap()) }
    var renderEffectCount by remember { mutableIntStateOf(0) }
    var statusEffectCount by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val cabinetSheetIndex by produceState<CabinetSheetIndex?>(null, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    }
    // Boolean? (not Boolean): null means "not yet resolved for this job", which lets the
    // mode-coercion effect below tell a fresh produceState reset (job just changed) apart from a
    // resolved "this job genuinely has no reference document" result.
    val hasAssemblyReferenceState by produceState<Boolean?>(null, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY) }
    }
    val hasPlansReferenceState by produceState<Boolean?>(null, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS) }
    }
    val hasAssemblyReference = hasAssemblyReferenceState == true
    val hasPlansReference = hasPlansReferenceState == true
    val hasThreeDAssets by produceState(false, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.hasThreeDAssets(jobFolderName) }
    }
    val defaultModalDoc: ReferenceDocType? = when {
        hasPlansReference -> ReferenceDocType.PLANS_ELEVATIONS
        hasAssemblyReference -> ReferenceDocType.ASSEMBLY
        else -> null
    }
    // mainViewRef's mode is a single global preference (MainViewReferenceState.kt), not scoped to
    // the current job. Without this, leaving the toggle on "Plans & Elev."/"Assembly" while
    // viewing a job, then opening a different job that lacks that reference document, would land
    // on UnifiedReferenceViewer's "Reference PDF not found" state instead of the Sheet.
    // Gate on the nullable *State values (not hasPlansReference/hasAssemblyReference themselves)
    // so this only evaluates once BOTH lookups have resolved for the current job — otherwise the
    // single frame where produceState has reset to its initial `null` right after a job switch
    // would read as "unavailable" and cause a false-positive reset even when the new job DOES
    // have the reference document.
    LaunchedEffect(jobFolderName, hasPlansReferenceState, hasAssemblyReferenceState) {
        if (hasPlansReferenceState == null || hasAssemblyReferenceState == null) return@LaunchedEffect
        val mode = mainViewRef.snapshot.mode
        val unavailable = (mode == ReferenceDocType.PLANS_ELEVATIONS && !hasPlansReference) ||
            (mode == ReferenceDocType.ASSEMBLY && !hasAssemblyReference)
        if (unavailable) mainViewRef.setMode(null)
    }

    // The main-view segmented row below sizes its shapes off `segmentCount` (see
    // SegmentedButtonDefaults.itemShape(index = 2, ...) for the Assembly segment, which is the
    // last item when count == 3 but a middle item once count == 4). Driving that directly off
    // hasAssemblyReference/hasPlansReference would make Assembly's OWN corner shape flip
    // (end-cap -> middle -> end-cap) on every job load, because those flags read false during
    // the produceState transient right after a job switch (see comment above at lines 383-385)
    // even for a job that genuinely has reference documents. Hold the previously-resolved value
    // stable through that transient and only update once BOTH lookups have actually resolved for
    // the current job, mirroring the gate used for the mode-coercion effect above.
    var resolvedShowPopupSegment by remember { mutableStateOf(false) }
    LaunchedEffect(jobFolderName, hasPlansReferenceState, hasAssemblyReferenceState) {
        if (hasPlansReferenceState == null || hasAssemblyReferenceState == null) return@LaunchedEffect
        resolvedShowPopupSegment = hasPlansReferenceState == true || hasAssemblyReferenceState == true
    }

    LaunchedEffect(pdfMarkupStore, jobFolderName, pdfFilename, currentPage, markupChangeGeneration) {
        if (pdfMarkupStore == null || currentPage <= 0) {
            localMarkupStrokes.clear()
            localMarkupDeletedIds.clear()
            return@LaunchedEffect
        }
        val (mergedStrokes, deletedIds) = withContext(Dispatchers.IO) {
            val strokes = pdfMarkupStore.getMergedActiveStrokes(jobFolderName, pdfFilename, currentPage)
            val deleted = pdfMarkupStore.loadTabletPageMarkup(jobFolderName, pdfFilename, currentPage)
                ?.deletedStrokeIds
                .orEmpty()
            strokes to deleted
        }
        localMarkupStrokes.clear()
        localMarkupStrokes.addAll(mergedStrokes)
        localMarkupDeletedIds.clear()
        localMarkupDeletedIds.addAll(deletedIds)
        Log.d(
            "PdfMarkupDebug",
            "SheetViewer reload job=$jobFolderName pdf=$pdfFilename page=$currentPage strokes=${localMarkupStrokes.size} deleted=${localMarkupDeletedIds.size}"
        )
    }
    // Pen/markup drawing only applies to the Sheet (CNC PDF) view — the ref-doc topContent branch
    // (Plans & Elev./Assembly via UnifiedReferenceViewer) doesn't render any markup overlay, so
    // leaving this true while mainViewRef.snapshot.mode != null would float the markup toolbar
    // uselessly over a reference doc and silently carry "draw mode" back into a later Sheet session.
    val penMarkupOverlayActive = markupEnabled && mainViewRef.snapshot.mode == null
    val hasMarkupHistory = remember(localMarkupStrokes.size, localMarkupDeletedIds.size) {
        localMarkupStrokes.any { it.id !in localMarkupDeletedIds }
    }

    fun persistCurrentPageMarkup() {
        val store = pdfMarkupStore ?: return
        val page = currentPage
        if (page <= 0) return
        val strokesToSave = localMarkupStrokes.filter { it.id !in localMarkupDeletedIds }
        val deletedToSave = localMarkupDeletedIds.toList()
        Log.d(
            "PdfMarkupDebug",
            "SheetViewer persist job=$jobFolderName pdf=$pdfFilename page=$page strokes=${strokesToSave.size} deleted=${deletedToSave.size}"
        )
        scope.launch(Dispatchers.IO) {
            store.savePageMarkup(
                jobFolderName = jobFolderName,
                pdfFilename = pdfFilename,
                page = page,
                strokes = strokesToSave,
                deletedStrokeIds = deletedToSave
            )
        }
    }

    fun normalizeRoomFolder(roomText: String?): String? {
        val raw = roomText?.let {
            ROOM_PAREN_REGEX.find(it)?.groupValues?.get(1)?.uppercase()
                ?: it.uppercase().takeIf { s -> s.isNotBlank() }
        } ?: return null
        return raw.replace(ROOM_ILLEGAL_CHARS_REGEX, " ")
            .replace(ROOM_WHITESPACE_REGEX, " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun firstAlphabeticalRoomFromIndex(): Pair<String, Int>? {
        return cabinetSheetIndex?.documents?.assembly?.pageDetails
            ?.mapNotNull { (pageKey, detail) ->
                val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                val room = normalizeRoomFolder(detail.room) ?: return@mapNotNull null
                room to page
            }
            ?.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            ?.firstOrNull()
    }

    fun roomInCurrentSheetView(): String? {
        val roomCounts = parts.asSequence()
            .mapNotNull { part -> normalizeRoomFolder(part.room)?.takeIf { room -> room.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        return roomCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key
    }

    val pdfFile = remember { jobRepository.getPdfFile(jobFolderName, pdfFilename) }
    val fileFingerprint = currentMaterial?.fileFingerprint.orEmpty()
    val pendingBadPartCount by produceState(0, progressVersion, pdfFilename, fileFingerprint) {
        value = withContext(Dispatchers.IO) {
            progressStore.getPendingBadPartsForMaterial(jobFolderName, pdfFilename, fileFingerprint)
        }
    }
    val sheetFilesCache = remember(jobFolderName, pdfFilename, fileFingerprint) { mutableStateMapOf<Int, List<String>>() }
    val tocThumbCache = remember(jobFolderName, pdfFilename, fileFingerprint) { mutableStateMapOf<Int, Bitmap?>() }
    var tocSheetInfoByPage by remember(jobFolderName, pdfFilename, fileFingerprint) {
        mutableStateOf<Map<Int, TocSheetInfo>>(emptyMap())
    }
    val renderCache = remember(jobFolderName, pdfFilename, fileFingerprint) { mutableMapOf<Int, RenderedSheetPage>() }
    val renderCacheOrder = remember(jobFolderName, pdfFilename, fileFingerprint) { ArrayDeque<Int>() }
    var previousMaterialIdentity by remember { mutableStateOf<String?>(null) }
    var hasBoundInitialMaterial by remember(jobFolderName, pdfFilename) { mutableStateOf(false) }
    var didRedirectForUnavailableMaterial by remember(jobFolderName, pdfFilename) { mutableStateOf(false) }

    fun touchRenderCache(page: Int) {
        renderCacheOrder.remove(page)
        renderCacheOrder.addLast(page)
    }

    fun cacheRenderedPage(page: Int, rendered: RenderedSheetPage) {
        val existing = renderCache[page]
        if (existing != null && existing.renderScale >= rendered.renderScale) {
            if (shouldRecycleRenderedPageBitmap(rendered.pageBitmap, rendered.wasDisplayed)) {
                rendered.pageBitmap?.recycle()
            }
            touchRenderCache(page)
            return
        }
        renderCache[page] = rendered
        if (shouldRecycleRenderedPageBitmap(existing?.pageBitmap, existing?.wasDisplayed == true)) {
            existing?.pageBitmap?.recycle()
        }
        touchRenderCache(page)
        while (renderCacheOrder.size > RENDER_CACHE_MAX_PAGES) {
            val stalePage = renderCacheOrder.removeFirst()
            if (stalePage != page) {
                val evicted = renderCache.remove(stalePage)
                // Never recycle diagramBitmap: it is owned/evicted by ProgressStore's own
                // prepared-page cache. Only the page render's own pageBitmap belongs to us.
                if (shouldRecycleRenderedPageBitmap(evicted?.pageBitmap, evicted?.wasDisplayed == true)) {
                    evicted?.pageBitmap?.recycle()
                }
            }
        }
    }

    fun clearRenderCache(reason: PreparedStateInvalidationReason) {
        renderCache.values.forEach { rendered ->
            if (shouldRecycleRenderedPageBitmap(rendered.pageBitmap, rendered.wasDisplayed)) {
                rendered.pageBitmap?.recycle()
            }
        }
        renderCache.clear()
        renderCacheOrder.clear()
        Log.d(VIEWER_PREPARED_TAG, "render_recompute_reason=$reason")
    }

    fun preparedPageKey(material: Material, pageNumber: Int): PreparedPageKey {
        return PreparedPageKey(
            jobFolderName = jobFolderName,
            pdfFilename = material.pdfFilename,
            page = pageNumber,
            fileFingerprint = material.fileFingerprint
        )
    }

    suspend fun renderPageFromPdf(
        targetMaterial: Material,
        targetPdfFile: java.io.File,
        pageNumber: Int,
        source: String,
        quality: SheetRenderQuality
    ): RenderedSheetPage? {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return null
        var renderedBitmap: Bitmap? = null
        return try {
            var outDiagram: Bitmap? = null
            val pageIndex = pageNumber - 1
            ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    Log.d(OCR_TAG, "render_fn: pageIndex=$pageIndex pageCount=${renderer.pageCount} quality=$quality file=${targetPdfFile.path} exists=${targetPdfFile.exists()}")
                    if (pageIndex !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val width = (page.width * quality.scale).toInt().coerceAtLeast(1)
                        val height = (page.height * quality.scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        try {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            renderedBitmap = bmp
                        } catch (error: Throwable) {
                            bmp.recycle()
                            throw error
                        }
                    }
                }
            }
            val pageMeta = resolvePageMetadata(targetMaterial, pageNumber)
            when (quality.diagramSource) {
                SheetDiagramSource.SIDECAR_THUMBNAIL -> {
                    val thumbnail = loadCncSidecarBitmap(targetPdfFile, pageMeta?.thumbnailPath)
                    if (thumbnail != null) {
                        outDiagram = resizeThumbnail(thumbnail)
                        if (outDiagram !== thumbnail) thumbnail.recycle()
                    }
                }
                SheetDiagramSource.FULL_EMBEDDED_IMAGE -> {
                    val key = preparedPageKey(targetMaterial, pageNumber)
                    outDiagram = progressStore.getOrPrepareDiagramBitmap(
                        key = key,
                        source = source
                    ) {
                        extractLargestEmbeddedImage(targetPdfFile, pageIndex)
                            ?: loadCncSidecarBitmap(targetPdfFile, pageMeta?.thumbnailPath)
                    }
                }
            }
            RenderedSheetPage(
                pageBitmap = renderedBitmap,
                diagramBitmap = outDiagram,
                renderScale = quality.scale
            )
        } catch (e: Exception) {
            renderedBitmap?.takeUnless { it.isRecycled }?.recycle()
            if (e is CancellationException) throw e
            Log.e(OCR_TAG, "Page $pageNumber render error source=$source", e)
            null
        }
    }

    fun sheetSnapshotForPage(page: Int) = appSheetStatusSnapshots[
        SheetStatusKey(jobFolderName, pdfFilename, page, fileFingerprint)
    ]

    LaunchedEffect(jobFolderName, pdfFilename, startPage) {
        Log.d(
            VIEWER_PREPARED_TAG,
            "viewer_instance_created job=$jobFolderName pdf=$pdfFilename startPage=$startPage"
        )
    }

    suspend fun persistViewTouch(force: Boolean = false) {
        val material = currentMaterial ?: return
        if (material.fileFingerprint.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && currentPage == lastPersistedViewPage && now - lastPersistedViewAtMs < 1200L) return
        val page = currentPage
        withContext(Dispatchers.IO) {
            progressStore.markSheetViewed(jobFolderName, pdfFilename, page, material.fileFingerprint)
        }
        lastPersistedViewPage = page
        lastPersistedViewAtMs = now
    }

    LaunchedEffect(
        numberColDp, widthColDp, lengthColDp, nameWeight, cabColDp, roomColDp,
        sortColumn, sortDirection
    ) {
        val prefix = "table_prefs_v1_"
        sharedPrefs.edit()
            .putFloat("${prefix}num", numberColDp)
            .putFloat("${prefix}width", widthColDp)
            .putFloat("${prefix}length", lengthColDp)
            .putFloat("${prefix}name_weight", nameWeight)
            .putFloat("${prefix}cab", cabColDp)
            .putFloat("${prefix}room", roomColDp)
            .putString("${prefix}sort_col", sortColumn.name)
            .putString("${prefix}sort_dir", sortDirection.name)
            .apply()
    }

    var currentCncJob by remember { mutableStateOf<com.kkc.sheettracker.data.models.Job?>(null) }

    val clockInFallback = remember(jobFolderName) {
        unifiedEngine.getCachedJobInfos().find { it.folderName == jobFolderName }
    }

    LaunchedEffect(jobFolderName, pdfFilename, scanState.snapshot.generation) {
        val job = withContext(Dispatchers.IO) {
            unifiedEngine.getCncSnapshot(jobFolderName)?.job
        }
        currentCncJob = job
        jobMaterials = job?.materials ?: emptyList()
        if (job != null) {
            withContext(Dispatchers.IO) {
                progressStore.pruneLocalStateForJob(job.folderName, job.materials)
            }
        }
        val nextMaterial = jobMaterials.firstOrNull { it.pdfFilename == pdfFilename }
        val nextIdentity = nextMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
        val oldIdentity = previousMaterialIdentity
        val isReplaced = hasBoundInitialMaterial && oldIdentity != null && nextIdentity != null && oldIdentity != nextIdentity
        val isMissing = hasBoundInitialMaterial && job != null && nextMaterial == null
        if ((isReplaced || isMissing) && !didRedirectForUnavailableMaterial) {
            didRedirectForUnavailableMaterial = true
            onMaterialUnavailable()
            return@LaunchedEffect
        }

        currentMaterial = nextMaterial
        visiblePages = nextMaterial?.visibleSheetPages().orEmpty()
        if (nextMaterial != null) {
            hasBoundInitialMaterial = true
            didRedirectForUnavailableMaterial = false
            val localTouchPage = progressStore
                .getLocalMaterialLastTouches(jobFolderName)[pdfFilename]
                ?.page
            if (visiblePages.isEmpty()) {
                val resolved = (localTouchPage ?: startPage).coerceIn(1, nextMaterial.pageCount.coerceAtLeast(1))
                currentPage = resolved
            } else {
                val requested = nextMaterial.resolveHeadPage(localTouchPage ?: startPage)
                val identityChanged = oldIdentity != nextIdentity
                currentPage = when {
                    !identityChanged && currentPage in visiblePages -> currentPage
                    requested in visiblePages -> requested
                    else -> visiblePages.first()
                }
            }
        }

        if (oldIdentity != nextIdentity) {
            val reason = if (oldIdentity != null && nextIdentity != null) {
                PreparedStateInvalidationReason.FingerprintChanged
            } else {
                PreparedStateInvalidationReason.IdentityChanged
            }
            clearRenderCache(reason)
            progressStore.invalidatePreparedPagesForDocument(
                jobFolderName = jobFolderName,
                pdfFilename = pdfFilename,
                reason = reason
            )
        }
        previousMaterialIdentity = nextIdentity
    }

    LaunchedEffect(currentMaterial, visiblePages, currentPage) {
        val material = currentMaterial ?: return@LaunchedEffect
        if (visiblePages.isEmpty()) return@LaunchedEffect
        if (currentPage in visiblePages) return@LaunchedEffect
        val headPage = material.resolveHeadPage(currentPage)
        currentPage = if (headPage in visiblePages) headPage else visiblePages.first()
    }

    LaunchedEffect(currentPage, fileFingerprint) {
        if (fileFingerprint.isBlank()) return@LaunchedEffect
        delay(250)
        persistViewTouch(force = false)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch { persistViewTouch(force = true) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentPage, fileFingerprint) {
        val material = currentMaterial ?: run {
            Log.w(OCR_TAG, "render_guard: currentMaterial=null page=$currentPage fp=$fileFingerprint")
            return@LaunchedEffect
        }
        if (fileFingerprint.isBlank()) {
            Log.w(OCR_TAG, "render_guard: fileFingerprint blank page=$currentPage material=${material.pdfFilename}")
            return@LaunchedEffect
        }
        val pages = visiblePages.ifEmpty { material.visibleSheetPages() }
        if (pages.isEmpty()) {
            Log.w(OCR_TAG, "render_guard: pages empty page=$currentPage material=${material.pdfFilename}")
            return@LaunchedEffect
        }
        if (currentPage !in pages) {
            Log.w(OCR_TAG, "render_guard: currentPage=$currentPage not in pages=$pages material=${material.pdfFilename}")
            return@LaunchedEffect
        }
        totalPages = pages.size
        renderEffectCount++
        Log.d(
            VIEWER_PARITY_TAG,
            "render_effect_trigger count=$renderEffectCount page=$currentPage generation=${scanState.snapshot.generation}"
        )
        diagramBboxes = emptyMap()
        val cached = renderCache[currentPage]
        if (cached != null) {
            cached.wasDisplayed = true
            touchRenderCache(currentPage)
            pageBitmap = cached.pageBitmap
            diagramBitmap = cached.diagramBitmap
            Log.i(
                OCR_TAG,
                "Page $currentPage render cache hit: pageBitmap=${pageBitmap?.width}x${pageBitmap?.height}, diagram=${diagramBitmap?.width}x${diagramBitmap?.height}"
            )
        }
        if (cached == null || !isRenderQualitySufficient(cached.renderScale, SheetRenderQuality.CURRENT)) {
            Log.d(
                VIEWER_PREPARED_TAG,
                "render_recompute_reason=${if (cached == null) "cache_miss" else "quality_promotion"} page=$currentPage job=$jobFolderName pdf=$pdfFilename"
            )
            if (cached == null) delay(150L)
            val rendered = withContext(Dispatchers.IO) {
                renderPageFromPdf(
                    targetMaterial = material,
                    targetPdfFile = pdfFile,
                    pageNumber = currentPage,
                    source = "render_current",
                    quality = SheetRenderQuality.CURRENT
                )
            }
            if (rendered != null) {
                rendered.wasDisplayed = true
                pageBitmap = rendered.pageBitmap
                diagramBitmap = rendered.diagramBitmap
                cacheRenderedPage(currentPage, rendered)
                Log.i(
                    OCR_TAG,
                    "Page $currentPage render fresh: pageBitmap=${pageBitmap?.width}x${pageBitmap?.height}, diagram=${diagramBitmap?.width}x${diagramBitmap?.height}"
                )
            } else {
                pageBitmap = null
                diagramBitmap = null
            }
        }

        val meta = resolvePageMetadata(material, currentPage)
        if (meta != null && meta.parts.isEmpty()) {
            Log.w(OCR_TAG, "Page $currentPage metadata resolved but parts list is empty (sheetId=${meta.sheetId})")
        }
        val resolvedFiles = inferSheetFiles(meta)
        sheetFilesCache[currentPage] = resolvedFiles
        selectedPartType = null

        diagramBboxes = meta.toSidecarOcrMap()
    }

    LaunchedEffect(currentPage, totalPages, fileFingerprint) {
        val material = currentMaterial ?: return@LaunchedEffect
        if (fileFingerprint.isBlank()) return@LaunchedEffect
        val pages = visiblePages.ifEmpty { material.visibleSheetPages() }
        if (pages.isEmpty()) return@LaunchedEffect
        val currentIndex = pages.indexOf(currentPage)
        if (currentIndex < 0) return@LaunchedEffect
        delay(250L)
        withContext(Dispatchers.IO) {
            val start = (currentIndex - RENDER_PREWARM_RADIUS).coerceAtLeast(0)
            val end = (currentIndex + RENDER_PREWARM_RADIUS).coerceAtMost(pages.lastIndex)
            for (idx in start..end) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
                val p = pages[idx]
                if (p == currentPage) continue
                val shouldRender = withContext(Dispatchers.Main) {
                    val cachedAdjacent = renderCache[p]
                    cachedAdjacent == null ||
                        !isRenderQualitySufficient(cachedAdjacent.renderScale, SheetRenderQuality.ADJACENT)
                }
                if (!shouldRender) continue
                val rendered = renderPageFromPdf(
                    targetMaterial = material,
                    targetPdfFile = pdfFile,
                    pageNumber = p,
                    source = "render_prewarm",
                    quality = SheetRenderQuality.ADJACENT
                ) ?: continue
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    rendered.pageBitmap?.recycle()
                    break
                }
                var cacheOwnsBitmap = false
                try {
                    withContext(Dispatchers.Main) {
                        cacheRenderedPage(p, rendered)
                        cacheOwnsBitmap = true
                    }
                } finally {
                    if (!cacheOwnsBitmap) {
                        rendered.pageBitmap?.takeUnless { it.isRecycled }?.recycle()
                    }
                }
            }
        }
    }

    LaunchedEffect(currentPage, fileFingerprint, progressVersion, appProgressVersion, useAppStateStatus) {
        if (currentMaterial == null || fileFingerprint.isBlank()) return@LaunchedEffect
        statusEffectCount++
        Log.d(
            VIEWER_PARITY_TAG,
            "status_effect_trigger count=$statusEffectCount page=$currentPage appProgress=$appProgressVersion legacyProgress=$progressVersion"
        )

        val appSnapshot = sheetSnapshotForPage(currentPage)
        val legacyStatus = progressStore.getSheetStatus(jobFolderName, pdfFilename, currentPage, fileFingerprint)
        // RE_NESTED is always a local tablet write — AppState snapshots can lag a derivation
        // cycle and return a stale SKIPPED status. Always trust the ProgressStore for RE_NESTED.
        val resolvedStatus = if (useAppStateStatus && legacyStatus != SheetStatus.RE_NESTED) {
            appSnapshot?.status ?: legacyStatus
        } else {
            legacyStatus
        }

        if (appFlags.shadowEnabled && appSnapshot != null && appSnapshot.status != legacyStatus) {
            Log.w(
                VIEWER_PARITY_TAG,
                "status_mismatch page=$currentPage app=${appSnapshot.status} legacy=$legacyStatus appGen=${appUiState.scanGeneration} legacyGen=${scanState.snapshot.generation} appProgress=$appProgressVersion legacyProgress=$progressVersion"
            )
        }

        sheetStatus = resolvedStatus
        badParts = progressStore.getBadParts(jobFolderName, pdfFilename, currentPage, fileFingerprint, includeDraft = true)
        draftBadParts = progressStore.getDraftBadParts(jobFolderName, pdfFilename, currentPage, fileFingerprint)
    }

    val materialName = remember(pdfFilename) {
        pdfFilename.removeSuffix(".pdf").let { name ->
            val dashIdx = name.indexOf(" - ")
            if (dashIdx >= 0) name.substring(dashIdx + 3) else name
        }
    }
    val currentJobNumber = currentCncJob?.jobNumber ?: ""
    val viewerTitle = cncSheetViewerTitle(currentJobNumber, materialName)
    val topBarColor = when (sheetStatus) {
        SheetStatus.COMPLETE -> KKCThemeColors.statusColors.complete
        SheetStatus.SKIPPED -> KKCThemeColors.statusColors.skip
        SheetStatus.HAS_BAD_PARTS -> KKCThemeColors.statusColors.bad
        SheetStatus.RE_NESTED -> KKCThemeColors.statusColors.complete.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }
    val topBarTextColor = when (sheetStatus) {
        SheetStatus.SKIPPED, SheetStatus.NOT_STARTED, SheetStatus.IN_PROGRESS, SheetStatus.RE_NESTED -> MaterialTheme.colorScheme.onSurface
        SheetStatus.COMPLETE, SheetStatus.HAS_BAD_PARTS -> Color.White
    }
    val currentPageRemake = remember(currentMaterial, currentPage) {
        resolvePageMetadata(currentMaterial, currentPage)?.remake
    }
    val currentPageRemakeParts = remember(currentPageRemake) {
        currentPageRemake?.remadeParts
            ?.mapNotNull { remade -> remade.partNumber.takeIf { it > 0 } }
            ?.toSet()
            .orEmpty()
    }
    val effectiveVisiblePages = if (visiblePages.isNotEmpty()) visiblePages else currentMaterial?.visibleSheetPages().orEmpty()
    val currentVisibleIndex = effectiveVisiblePages.indexOf(currentPage).let { if (it >= 0) it else 0 }
    val visibleTotalPages = if (effectiveVisiblePages.isNotEmpty()) effectiveVisiblePages.size else totalPages
    val displayPageNumber = if (effectiveVisiblePages.isNotEmpty()) currentVisibleIndex + 1 else currentPage
    val tocMetadataPages = currentMaterial?.metadata?.pages.orEmpty()

    LaunchedEffect(
        showSheetToc,
        effectiveVisiblePages,
        jobFolderName,
        pdfFilename,
        fileFingerprint,
        progressVersion,
        appProgressVersion,
        useAppStateStatus
    ) {
        if (!showSheetToc || fileFingerprint.isBlank() || effectiveVisiblePages.isEmpty()) {
            tocSheetInfoByPage = emptyMap()
            return@LaunchedEffect
        }

        val pages = effectiveVisiblePages.toList()
        val sheetInfo = withContext(Dispatchers.IO) {
            pages.associateWith { page ->
                val appSnapshot = sheetSnapshotForPage(page)
                val legacyStatus = progressStore.getSheetStatus(jobFolderName, pdfFilename, page, fileFingerprint)
                val status = if (useAppStateStatus) appSnapshot?.status ?: legacyStatus else legacyStatus
                val committedBadCount = if (useAppStateStatus) {
                    appSnapshot?.committedBadCount ?: progressStore.getBadParts(
                        jobFolderName,
                        pdfFilename,
                        page,
                        fileFingerprint,
                        includeDraft = false
                    ).size
                } else {
                    progressStore.getBadParts(
                        jobFolderName,
                        pdfFilename,
                        page,
                        fileFingerprint,
                        includeDraft = false
                    ).size
                }
                val draftBadCount = if (useAppStateStatus) {
                    if (appSnapshot?.hasDraftBadParts == true) {
                        progressStore.getDraftBadParts(
                            jobFolderName,
                            pdfFilename,
                            page,
                            fileFingerprint
                        ).size
                    } else {
                        0
                    }
                } else {
                    progressStore.getDraftBadParts(
                        jobFolderName,
                        pdfFilename,
                        page,
                        fileFingerprint
                    ).size
                }
                TocSheetInfo(
                    status = status,
                    committedBadCount = committedBadCount,
                    draftBadCount = draftBadCount
                )
            }
        }
        tocSheetInfoByPage = sheetInfo
    }

    // True fullscreen: hide system bars for the lifetime of this screen.
    ImmersiveSystemBars()
    // CNC viewer controls stay visible so cache refresh/loading states cannot strand operators.
    val showUi = cncSheetViewerUiVisible()
    LaunchedEffect(Unit) { onUiVisibilityChanged(true) }
    DisposableEffect(Unit) { onDispose { onUiVisibilityChanged(true) } }
    val topBarAlpha by animateFloatAsState(if (showUi) 1f else 0f, tween(220), label = "topBarAlpha")
    val navBarDeco = LocalNavBarDecoration.current
    DisposableEffect(navBarDeco) {
        onDispose {
            navBarDeco.cncDecoration = null
            navBarDeco.extendedControls = null
        }
    }
    SideEffect {
        navBarDeco.cncDecoration = if (showUi) {
            NavBarCncDecoration(
                currentPage = displayPageNumber,
                totalPages = visibleTotalPages,
                sheetStatus = sheetStatus,
                onPrevPage = {
                    if (effectiveVisiblePages.isNotEmpty() && currentVisibleIndex > 0) {
                        currentPage = effectiveVisiblePages[currentVisibleIndex - 1]
                        selectedPartNumber = null
                        selectedCabinetNumber = null
                    }
                },
                onNextPage = {
                    if (effectiveVisiblePages.isNotEmpty() && currentVisibleIndex < effectiveVisiblePages.lastIndex) {
                        currentPage = effectiveVisiblePages[currentVisibleIndex + 1]
                        selectedPartNumber = null
                        selectedCabinetNumber = null
                    }
                },
                onOpenToc = { showSheetToc = true },
                onToggleSkip = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val page = currentPage
                    val fp = fileFingerprint
                    val identityBefore = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val skipped = progressStore.isSheetSkipped(jobFolderName, pdfFilename, page, fp)
                            if (skipped) progressStore.unmarkSheetSkipped(jobFolderName, pdfFilename, page, fp)
                            else progressStore.markSheetSkipped(jobFolderName, pdfFilename, page, fp)
                        }
                        if (BuildConfig.DEBUG) {
                            val identityAfter = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                            check(identityBefore == identityAfter) { "CACHE_IDENTITY_CHANGED during skip toggle" }
                        }
                    }
                },
                onToggleComplete = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val page = currentPage
                    val fp = fileFingerprint
                    // Derive the display number and remake parts from the live `page`, not
                    // the composition snapshots (displayPageNumber / currentPageRemakeParts).
                    // The decoration reaches the nav bar via SideEffect, so if `currentPage`
                    // changed between the last push and this tap, those snapshots would lag —
                    // marking one sheet complete while resolving remake parts / reporting the
                    // page number of another. Recomputing from `page` keeps all three in sync.
                    val displayNo = if (effectiveVisiblePages.isNotEmpty())
                        effectiveVisiblePages.indexOf(page).let { if (it >= 0) it + 1 else page }
                    else page
                    val remakeParts = resolvePageMetadata(currentMaterial, page)?.remake?.remadeParts
                        ?.mapNotNull { remade -> remade.partNumber.takeIf { it > 0 } }
                        ?.toSet()
                        .orEmpty()
                    val identityBefore = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                    scope.launch {
                        val wasComplete = withContext(Dispatchers.IO) {
                            progressStore.isSheetComplete(jobFolderName, pdfFilename, page, fp)
                        }
                        if (wasComplete) {
                            withContext(Dispatchers.IO) {
                                progressStore.unmarkSheetComplete(jobFolderName, pdfFilename, page, fp)
                            }
                            snackbarHostState.showSnackbar("Sheet $displayNo marked incomplete")
                        } else {
                            val (wasSkipped, resolvedRemakeCount) = withContext(Dispatchers.IO) {
                                val skipped = progressStore.isSheetSkipped(jobFolderName, pdfFilename, page, fp)
                                progressStore.markSheetComplete(jobFolderName, pdfFilename, page, fp)
                                val resolved = progressStore.resolveSpecificBadParts(
                                    jobFolderName = jobFolderName,
                                    pdfFilename = pdfFilename,
                                    page = page,
                                    fileFingerprint = fp,
                                    partNumbers = remakeParts
                                )
                                skipped to resolved
                            }
                            val baseMessage =
                                if (wasSkipped) "Sheet $displayNo marked complete (skip removed)"
                                else "Sheet $displayNo marked complete"
                            snackbarHostState.showSnackbar(
                                if (resolvedRemakeCount > 0) {
                                    "$baseMessage • auto-resolved $resolvedRemakeCount remake bad part(s)"
                                } else {
                                    baseMessage
                                }
                            )
                            if (effectiveVisiblePages.isNotEmpty() && currentVisibleIndex < effectiveVisiblePages.lastIndex) {
                                currentPage = effectiveVisiblePages[currentVisibleIndex + 1]
                                selectedPartNumber = null
                                selectedCabinetNumber = null
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            val identityAfter = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                            check(identityBefore == identityAfter) { "CACHE_IDENTITY_CHANGED during complete toggle" }
                        }
                    }
                },
                onOpenSearch = { showCncSearch = true },
                onToggleRenested = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val page = currentPage
                    val fp = fileFingerprint
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val renested = progressStore.isSheetRenested(jobFolderName, pdfFilename, page, fp)
                            if (renested) progressStore.unmarkSheetRenested(jobFolderName, pdfFilename, page, fp)
                            else progressStore.markSheetRenested(jobFolderName, pdfFilename, page, fp)
                        }
                    }
                }
            )
        } else {
            null
        }
        navBarDeco.extendedControls = if (showUi && penMarkupOverlayActive) {
            {
                PdfMarkupToolbar(
                    state = markupToolState,
                    hasUndo = hasMarkupHistory,
                    onUndo = {
                        val store = pdfMarkupStore
                        val page = currentPage
                        val deletedSnapshot = localMarkupDeletedIds.toSet()
                        scope.launch {
                            val latestVisible = withContext(Dispatchers.IO) {
                                store
                                    ?.loadTabletPageMarkup(jobFolderName, pdfFilename, page)
                                    ?.strokes
                                    ?.lastOrNull { it.id !in deletedSnapshot }
                            }
                            if (latestVisible != null) {
                                localMarkupDeletedIds.add(latestVisible.id)
                                persistCurrentPageMarkup()
                            }
                        }
                    },
                    strokesVisible = markupStrokesVisible,
                    onToggleVisibility = { markupStrokesVisible = !markupStrokesVisible },
                    onHide = { markupEnabled = false }
                )
            }
        } else {
            null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            // graphicsLayer alpha — no layout shift, no bitmap re-render during animation.
            TopAppBar(
                modifier = Modifier.graphicsLayer { alpha = topBarAlpha },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            viewerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        SheetStatusBadge(
                            status = sheetStatus,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarColor,
                    titleContentColor = topBarTextColor,
                    actionIconContentColor = topBarTextColor,
                    navigationIconContentColor = topBarTextColor
                ),
                windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
                actions = {
                    if (pendingBadPartCount > 0) {
                        TextButton(
                            onClick = {
                                val fp = fileFingerprint
                                scope.launch(Dispatchers.IO) {
                                    progressStore.submitPendingBadParts(jobFolderName, pdfFilename, fp)
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = KKCThemeColors.statusColors.bad
                            )
                        ) {
                            Text(
                                text = "Report $pendingBadPartCount Bad Part${if (pendingBadPartCount == 1) "" else "s"}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    val clockInJob = currentCncJob ?: clockInFallback?.let { 
                        com.kkc.sheettracker.data.models.Job(folderName = it.folderName, jobNumber = it.jobNumber, jobName = it.jobName) 
                    }
                    if (clockInJob != null) {
                        if (clockInState != null) {
                            ClockInButton(
                                clockInState = clockInState,
                                isClockedInHere = isClockedInHere,
                                onClockInClick = { onClockIn(clockInJob.jobNumber, clockInJob.jobName) }
                            )
                        } else {
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
                    }
                    if (mainViewRef.snapshot.mode == null) {
                        IconButton(
                            onClick = { markupEnabled = !markupEnabled }
                        ) {
                            Icon(
                                Icons.Default.Create,
                                contentDescription = if (markupEnabled) "Disable pen mode" else "Enable pen mode",
                                tint = if (markupEnabled) MaterialTheme.colorScheme.primary else topBarTextColor
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showViewerMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Viewer actions")
                        }
                        DropdownMenu(
                            expanded = showViewerMenu,
                            onDismissRequest = { showViewerMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Reset Zoom") },
                                onClick = {
                                    resetZoomTrigger++
                                    showViewerMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showFullPdfPage) "Show Diagram" else "Show Full PDF") },
                                onClick = {
                                    showFullPdfPage = !showFullPdfPage
                                    showViewerMenu = false
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (sheetFilesLabel.isNotBlank() || currentPageRemake != null) {
                val chips = remember(currentPageMetadata) {
                    inferSheetFiles(currentPageMetadata)
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val leftChips = remember(chips, sheetSizeLabel) {
                            chips + listOfNotNull(sheetSizeLabel)
                        }
                        if (leftChips.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(9.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                    shadowElevation = 2.dp,
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxHeight(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        leftChips.forEachIndexed { i, label ->
                                            if (i > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(1.dp)
                                                        .fillMaxHeight()
                                                        .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.24f))
                                                )
                                            }
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                                    .padding(horizontal = 16.dp)
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        val segmentCount = if (resolvedShowPopupSegment) 4 else 3
                        val rightRowShape = RoundedCornerShape(9.dp)
                        Surface(
                            shape = rightRowShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .width(if (resolvedShowPopupSegment) 440.dp else 330.dp)
                                .height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Button 0: Sheet
                                val isSheetSelected = mainViewRef.snapshot.mode == null
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(if (isSheetSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .clickable { mainViewRef.setMode(null) }
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        text = "Sheet",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSheetSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }

                                // Divider 1
                                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))

                                // Button 1: Plans & Elev.
                                val isPlansSelected = mainViewRef.snapshot.mode == ReferenceDocType.PLANS_ELEVATIONS
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(if (isPlansSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .clickable(enabled = hasPlansReference) { mainViewRef.setMode(ReferenceDocType.PLANS_ELEVATIONS) }
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        text = "Plans & Elev.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isPlansSelected) MaterialTheme.colorScheme.onSecondaryContainer else if (hasPlansReference) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        maxLines = 1
                                    )
                                }

                                // Divider 2
                                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))

                                // Button 2: Assembly
                                val isAssemblySelected = mainViewRef.snapshot.mode == ReferenceDocType.ASSEMBLY
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(if (isAssemblySelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .clickable(enabled = hasAssemblyReference) { mainViewRef.setMode(ReferenceDocType.ASSEMBLY) }
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        text = "Assembly",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isAssemblySelected) MaterialTheme.colorScheme.onSecondaryContainer else if (hasAssemblyReference) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        maxLines = 1
                                    )
                                }

                                if (resolvedShowPopupSegment) {
                                    // Divider 3
                                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))

                                    // Button 3: Popup
                                    val isPopupSelected = referenceModal.snapshot.isOpen
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (isPopupSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                            .clickable { referenceModal.toggleOpen(hasPlansReference, hasAssemblyReference, defaultModalDoc) }
                                            .padding(horizontal = 12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Popup",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isPopupSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (isPopupSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val remadePartNames = currentPageRemake?.remadeParts
                        ?.map { part ->
                            val numberText = part.partNumber.takeIf { it > 0 }?.let { "#$it " }.orEmpty()
                            "$numberText${part.partName}".trim()
                        }
                        ?.distinct()
                        .orEmpty()
                    if (remadePartNames.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Remade parts: ${remadePartNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            val activeCabinet = selectedCabinetNumber
                ?: selectedPartNumber?.let { selected ->
                    parts.firstOrNull { it.number == selected }?.cabNumber?.takeIf { it > 0 }
                }
            val activePart = selectedPartNumber?.let { selected ->
                parts.firstOrNull { it.number == selected }
            }
            val activeAssemblyPage = activeCabinet?.let { cab ->
                cabinetSheetIndex?.documents?.assembly?.cabinetToPages?.get(cab.toString())?.firstOrNull()
            }
            val activePlansPage = activeCabinet?.let { cab ->
                cabinetSheetIndex?.documents?.plansElevations?.cabinetToPages?.get(cab.toString())?.firstOrNull()
            }
            val activeRoom = normalizeRoomFolder(activePart?.room)
                ?: roomInCurrentSheetView()
                ?: activeAssemblyPage?.let { page ->
                    normalizeRoomFolder(cabinetSheetIndex?.documents?.assembly?.pageDetails?.get(page.toString())?.room)
                }
                ?: firstAlphabeticalRoomFromIndex()?.first
            LaunchedEffect(
                selectedPartNumber,
                selectedCabinetNumber,
                activeCabinet,
                activeAssemblyPage,
                activePlansPage,
                hasAssemblyReference,
                hasPlansReference,
                currentPage,
                fileFingerprint
            ) {
                val selectedPartCab = selectedPartNumber?.let { selected ->
                    parts.firstOrNull { it.number == selected }?.cabNumber
                }
                Log.d(
                    VIEWER_REF_TAG,
                    "ref_state page=$currentPage selectedPart=$selectedPartNumber selectedCabinet=$selectedCabinetNumber " +
                        "selectedPartCab=$selectedPartCab activeCabinet=$activeCabinet hasAssembly=$hasAssemblyReference " +
                        "assemblyPage=$activeAssemblyPage hasPlans=$hasPlansReference plansPage=$activePlansPage " +
                        "plansChipVisible=${hasPlansReference && activePlansPage != null} fp=$fileFingerprint"
                )
            }



            val bitmap = resolveSheetDisplayBitmap(showFullPdfPage, pageBitmap, diagramBitmap)
            val visiblePdfMarkupStrokes = if (markupStrokesVisible) {
                localMarkupStrokes.filter { it.id !in localMarkupDeletedIds }
            } else {
                emptyList()
            }

            var lastValidAspectRatio by remember { mutableFloatStateOf(2f) }
            var hasSetInitialAspectRatio by remember { mutableStateOf(false) }
            LaunchedEffect(bitmap) {
                val bmp = bitmap
                if (bmp != null && bmp.height > 0) {
                    lastValidAspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
                    hasSetInitialAspectRatio = true
                }
            }
            val bitmapAspectRatio = if (hasSetInitialAspectRatio) lastValidAspectRatio else null

            VerticalSplitLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                aspectRatio = bitmapAspectRatio,
                topContent = { topModifier ->
                    val mainViewRefData = mainViewReferenceData
                    if (mainViewRefData != null) {
                        UnifiedReferenceViewer(
                            modifier = topModifier.fillMaxWidth(),
                            displayPage = mainViewRef.snapshot.pageForMode(),
                            onDisplayPageChange = { mainViewRef.setPage(it) },
                            defaultPdfFilename = mainViewRefData.defaultPdfFilename,
                            pdfFileForFilename = { filename ->
                                jobRepository.getJobRootPdfFile(
                                    jobFolderName = jobFolderName,
                                    pdfFilename = filename,
                                    preferDarkMode = isDarkTheme
                                )
                            },
                            fileIdentitySeed = scanState.snapshot.generation,
                            preferDarkMode = isDarkTheme,
                            virtualMapping = mainViewRefData.virtualMapping,
                            navigatorCabinetToPages = mainViewRefData.navigatorCabinetToPages,
                            navigatorPlanViewLabels = mainViewRefData.navigatorPlanViewLabels,
                            navigatorWarningMessage = mainViewRefData.warningMessage,
                            missingText = "Reference PDF not found.",
                            unreadableText = "Unable to read PDF pages.",
                            showHeaderRow = false,
                            showNavigationButtons = true,
                            compactArrows = true
                        )
                    } else {
                        Crossfade(targetState = bitmap, animationSpec = tween(150), label = "viewerBitmap") { activeBitmap ->
                        if (activeBitmap != null) {
                            if (showFullPdfPage || markupEnabled) {
                                MarkupPdfPageView(
                                    bitmap = activeBitmap,
                                    invertSheetBitmap = invertSheetBitmap,
                                    resetZoomTrigger = resetZoomTrigger,
                                    inputEnabled = markupEnabled,
                                    modifier = topModifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    markupStrokes = visiblePdfMarkupStrokes,
                                    markupToolState = markupToolState,
                                    onMarkupStrokeAdded = { stroke ->
                                        localMarkupStrokes.add(stroke)
                                        persistCurrentPageMarkup()
                                    },
                                    onMarkupStrokeErased = { strokeId ->
                                        if (strokeId !in localMarkupDeletedIds) {
                                            localMarkupDeletedIds.add(strokeId)
                                        }
                                        persistCurrentPageMarkup()
                                    },
                                    onTapEmpty = null
                                )
                            } else {
                                DiagramView(
                                    bitmap = activeBitmap,
                                    invertSheetBitmap = invertSheetBitmap,
                                    parts = parts,
                                    selectedPartNumber = selectedPartNumber,
                                    diagramBboxes = diagramBboxes,
                                    resetZoomTrigger = resetZoomTrigger,
                                    markupStrokes = visiblePdfMarkupStrokes,
                                    modifier = topModifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    onTapPart = { partNumber ->
                                        val isDeselecting = selectedPartNumber == partNumber
                                        selectedPartNumber = if (isDeselecting) null else partNumber
                                        selectedCabinetNumber = if (isDeselecting) {
                                            null
                                        } else {
                                            parts.firstOrNull { it.number == partNumber }?.cabNumber?.takeIf { it > 0 }
                                        }
                                        val tappedCabinet = parts.firstOrNull { it.number == partNumber }?.cabNumber
                                        Log.d(
                                            VIEWER_REF_TAG,
                                            "tap_part source=diagram part=$partNumber tappedCabinet=$tappedCabinet " +
                                                "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                                        )
                                    },
                                    onLongPressPart = { partNumber ->
                                        val cabNumber = parts.firstOrNull { it.number == partNumber }?.cabNumber
                                        selectedPartNumber = partNumber
                                        selectedCabinetNumber = cabNumber?.takeIf { it > 0 }
                                        Log.d(
                                            VIEWER_REF_TAG,
                                            "tap_part source=diagram_long_press part=$partNumber tappedCabinet=$cabNumber " +
                                                "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                                        )
                                        showReferenceDocDialog = true
                                    },
                                    onTapEmpty = null
                                )
                            }
                        } else {
                            SheetLoadingPlaceholder(modifier = topModifier.fillMaxWidth())
                        }
                    }
                    }
                },
                bottomContent = { bottomModifier ->
                    if (parts.isEmpty()) {
                        Box(
                            modifier = bottomModifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No metadata available.\nRun the PDF splitter to generate part data.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                val summary = remember(parts) {
                    parts.groupBy { it.name }.mapValues { it.value.size }.toList().sortedByDescending { it.second }
                }
                val sortedFilteredParts = remember(parts, selectedPartType, sortColumn, sortDirection) {
                    var list = parts
                    if (!selectedPartType.isNullOrBlank()) {
                        list = list.filter { it.name == selectedPartType }
                    }
                    val comparator = when (sortColumn) {
                        SortColumn.NUMBER -> compareBy<Part> { it.number }
                        SortColumn.WIDTH -> compareBy { it.width }
                        SortColumn.LENGTH -> compareBy { it.length }
                        SortColumn.NAME -> compareBy { it.name.lowercase() }
                        SortColumn.CAB -> compareBy { it.cabNumber }
                        SortColumn.ROOM -> compareBy { it.room.lowercase() }
                    }
                    when (sortDirection) {
                        SortDirection.ASC -> list.sortedWith(comparator)
                        SortDirection.DESC -> list.sortedWith(comparator.reversed())
                        SortDirection.NONE -> list
                    }
                }

                PartsTable(
                    parts = sortedFilteredParts,
                    badParts = badParts,
                    draftBadParts = draftBadParts,
                    summary = summary,
                    isDarkTheme = isDarkTheme,
                    selectedPartType = selectedPartType,
                    onSelectPartType = { selectedPartType = if (selectedPartType == it) null else it },
                    selectedPartNumber = selectedPartNumber,
                    sortColumn = sortColumn,
                    sortDirection = sortDirection,
                    onSortChange = { col ->
                        if (sortColumn != col) {
                            sortColumn = col
                            sortDirection = SortDirection.ASC
                        } else {
                            sortDirection = when (sortDirection) {
                                SortDirection.NONE -> SortDirection.ASC
                                SortDirection.ASC -> SortDirection.DESC
                                SortDirection.DESC -> SortDirection.NONE
                            }
                        }
                    },
                    numberColDp = numberColDp,
                    widthColDp = widthColDp,
                    lengthColDp = lengthColDp,
                    nameWeight = nameWeight,
                    cabColDp = cabColDp,
                    roomColDp = roomColDp,
                    onResizeNumber = { delta -> numberColDp = (numberColDp + delta / 5f).coerceIn(24f, 70f) },
                    onResizeWidth = { delta -> widthColDp = (widthColDp + delta / 5f).coerceIn(40f, 110f) },
                    onResizeLength = { delta -> lengthColDp = (lengthColDp + delta / 5f).coerceIn(44f, 120f) },
                    onResizeNameWeight = { delta -> nameWeight = (nameWeight + delta / 900f).coerceIn(0.20f, 0.75f) },
                    onResizeCab = { delta -> cabColDp = (cabColDp + delta / 5f).coerceIn(30f, 90f) },
                    onResizeRoom = { delta -> roomColDp = (roomColDp + delta / 5f).coerceIn(100f, 300f) },
                    modifier = bottomModifier.fillMaxWidth(),
                    onPartClick = { part ->
                        val isDeselecting = selectedPartNumber == part.number
                        selectedPartNumber = if (isDeselecting) null else part.number
                        selectedCabinetNumber = if (isDeselecting) null else part.cabNumber.takeIf { it > 0 }
                        Log.d(
                            VIEWER_REF_TAG,
                            "tap_part source=table_click part=${part.number} partCabinet=${part.cabNumber} " +
                                "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                        )
                    },
                    onPartLongPress = { part ->
                        selectedPartNumber = part.number
                        selectedCabinetNumber = part.cabNumber.takeIf { it > 0 }
                        Log.d(
                            VIEWER_REF_TAG,
                            "tap_part source=table_long_press part=${part.number} partCabinet=${part.cabNumber} " +
                                "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                        )
                        showReferenceDocDialog = true
                    },
                    onToggleBadPart = { part ->
                        val page = currentPage
                        val fp = fileFingerprint
                        val identityBefore = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                progressStore.toggleBadPart(jobFolderName, pdfFilename, page, fp, part.number)
                            }
                            if (BuildConfig.DEBUG) {
                                val identityAfter = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                                check(identityBefore == identityAfter) { "CACHE_IDENTITY_CHANGED during bad-part toggle" }
                            }
                        }
                    }
                )
                    }
                }
            )
        }
        // Part-tap jump: mirrors ReferenceModalHost's handledCabinet pattern (ReferenceModalOverlay.kt).
        // `handledMainCabinet` is seeded with the cabinet selected at composition start and only
        // updates on a genuinely FRESH cabinet selection — keyed on selectedCabinetNumber alone
        // (not mode), so toggling Sheet/Plans/Assembly never re-fires the jump and each mode keeps
        // its own last-viewed page across toggles.
        var handledMainCabinet by remember { mutableStateOf(selectedCabinetNumber) }
        LaunchedEffect(selectedCabinetNumber) {
            if (selectedCabinetNumber == handledMainCabinet) return@LaunchedEffect
            handledMainCabinet = selectedCabinetNumber
            val cabinet = selectedCabinetNumber ?: return@LaunchedEffect
            val mode = mainViewRef.snapshot.mode ?: return@LaunchedEffect
            val refData = mainViewReferenceData ?: return@LaunchedEffect
            val target = com.kkc.sheettracker.ui.components.resolveJumpPage(
                refData.navigatorCabinetToPages,
                cabinet
            )
            if (target != null) {
                mainViewRef.setPage(target)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "No reference sheet for this cabinet in ${if (mode == ReferenceDocType.ASSEMBLY) "Assembly" else "Plans"}."
                    )
                }
            }
        }
        com.kkc.sheettracker.ui.components.ReferenceModalHost(
            state = referenceModal,
            jobRepository = jobRepository,
            jobFolderName = jobFolderName,
            refreshGeneration = scanState.snapshot.generation,
            isDarkTheme = isDarkTheme,
            hasPlans = hasPlansReference,
            hasAssembly = hasAssemblyReference,
            selectedCabinet = selectedCabinetNumber,
            sheetPdfFilename = pdfFilename,
            sheetPdfFile = pdfFile,
            currentSheetPage = currentPage,
            hazeState = null,
            modifier = Modifier.fillMaxSize()
        )
        }
    }

    if (showReferenceDocDialog) {
        val selectedPart = selectedPartNumber?.let { selected ->
            parts.firstOrNull { it.number == selected }
        }
        val cabinetNumber = selectedCabinetNumber
        val assemblyPage = cabinetNumber?.let { cab ->
            cabinetSheetIndex?.documents?.assembly?.cabinetToPages?.get(cab.toString())?.firstOrNull()
        }
        val plansPage = cabinetNumber?.let { cab ->
            cabinetSheetIndex?.documents?.plansElevations?.cabinetToPages?.get(cab.toString())?.firstOrNull()
        }
        val roomFolder = if (hasThreeDAssets) {
            normalizeRoomFolder(selectedPart?.room)
            ?: roomInCurrentSheetView()
            ?: assemblyPage?.let { page ->
                normalizeRoomFolder(cabinetSheetIndex?.documents?.assembly?.pageDetails?.get(page.toString())?.room)
            }
            ?: firstAlphabeticalRoomFromIndex()?.first
        } else {
            null
        }
        val partGraphicsArchive = currentMaterial?.metadata?.partGraphicsArchive
        val selectedGraphicPath = selectedPart?.graphicPath
        val partGraphicBitmap by produceState<Bitmap?>(
            initialValue = null,
            key1 = selectedGraphicPath,
            key2 = partGraphicsArchive,
            key3 = pdfFile
        ) {
            value = withContext(Dispatchers.IO) {
                loadPartGraphicBitmap(pdfFile, partGraphicsArchive, selectedGraphicPath)
            }
        }
        val bandingCode = selectedPart?.banding?.trim()?.takeIf { it.isNotEmpty() }
        val hasPartDetail = partGraphicBitmap != null || bandingCode != null
        AlertDialog(
            onDismissRequest = { showReferenceDocDialog = false },
            title = { Text("Open Reference Sheet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cabinet #${cabinetNumber ?: "?"}")
                    if (!selectedPart?.room.isNullOrBlank()) {
                        Text(
                            "Room: ${selectedPart.room}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (cabinetNumber == null) {
                        Text(
                            "No cabinet number was found for this part. 3D can still open when room mapping exists.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hasPartDetail) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val loadedPartGraphic = partGraphicBitmap
                            if (loadedPartGraphic != null) {
                                Image(
                                    bitmap = loadedPartGraphic.asImageBitmap(),
                                    contentDescription = "Part graphic",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                )
                            }
                            if (bandingCode != null) {
                                Text(
                                    "Banding: $bandingCode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    if (hasAssemblyReference && assemblyPage != null) {
                        Button(
                            onClick = {
                                showReferenceDocDialog = false
                                onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, assemblyPage)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Assembly Sheets (Page $assemblyPage)")
                        }
                    }
                    if (hasPlansReference && plansPage != null) {
                        Button(
                            onClick = {
                                showReferenceDocDialog = false
                                onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, plansPage)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Plans & Elevations (Page $plansPage)")
                        }
                    }
                    if (hasThreeDAssets && roomFolder != null) {
                        Button(
                            onClick = {
                                showReferenceDocDialog = false
                                onOpenThreeDTarget(
                                    cabinetNumber?.toString(),
                                    assemblyPage,
                                    plansPage,
                                    roomFolder
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View 3D Room")
                        }
                    }
                    if (isDarkTheme) {
                        Text(
                            "Dark mode is enabled; DARK MODE PDF is used when present.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showReferenceDocDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showSheetToc) {
        SheetNavigatorSheet(
            visiblePages = effectiveVisiblePages,
            currentPage = currentPage,
            metadataPages = tocMetadataPages,
            sheetInfoByPage = tocSheetInfoByPage,
            sheetFilesCache = sheetFilesCache,
            tocThumbCache = tocThumbCache,
            pdfFile = pdfFile,
            fileFingerprint = fileFingerprint,
            onSelectPage = { page ->
                currentPage = page
                selectedPartNumber = null
                selectedCabinetNumber = null
                showSheetToc = false
            },
            onDismiss = { showSheetToc = false }
        )
    }

    if (showCncSearch) {
        CncSearchModal(
            metadataPages = tocMetadataPages,
            visiblePages = effectiveVisiblePages,
            onSelectPage = { page ->
                currentPage = page
                selectedPartNumber = null
                selectedCabinetNumber = null
                showCncSearch = false
            },
            onDismiss = { showCncSearch = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetNavigatorSheet(
    visiblePages: List<Int>,
    currentPage: Int,
    metadataPages: List<PageMetadata>,
    sheetInfoByPage: Map<Int, TocSheetInfo>,
    sheetFilesCache: MutableMap<Int, List<String>>,
    tocThumbCache: MutableMap<Int, Bitmap?>,
    pdfFile: java.io.File,
    fileFingerprint: String,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pageMetaByNumber = remember(metadataPages) { metadataPages.associateBy { it.pageNumber } }

    LaunchedEffect(visiblePages, currentPage, fileFingerprint, metadataPages) {
        if (fileFingerprint.isBlank() || visiblePages.isEmpty()) return@LaunchedEffect
        val loadOrder = buildTocLoadOrder(visiblePages, currentPage)
        for (page in loadOrder) {
            if (!isActive) break
            val pageMeta = resolveTocPageMetadata(pageMetaByNumber, metadataPages, page)

            if (!sheetFilesCache.containsKey(page)) {
                sheetFilesCache[page] = inferSheetFiles(pageMeta)
            }

            if (!tocThumbCache.containsKey(page)) {
                val thumb = withContext(Dispatchers.IO) {
                    loadSheetThumbnailForToc(pdfFile, page - 1, pageMeta?.thumbnailPath)
                }
                tocThumbCache[page] = thumb
            }

            yield()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveDialogDecor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
        ) {
            Text(
                "Sheet Navigator",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "Tap a sheet to jump. Thumbnails are loaded from sidecar when available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = 8.dp,
                    end = 12.dp,
                    bottom = 112.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(visiblePages, key = { _, page -> page }) { idx, page ->
                    val displayPage = idx + 1
                    val pageMeta = resolveTocPageMetadata(pageMetaByNumber, metadataPages, page)
                    val sheetInfo = sheetInfoByPage[page]
                        ?: TocSheetInfo(
                            status = SheetStatus.NOT_STARTED,
                            committedBadCount = 0,
                            draftBadCount = 0
                        )
                    val fileLabel = sheetFilesCache[page]?.takeIf { it.isNotEmpty() }?.joinToString(" | ")
                        ?: inferSheetFiles(pageMeta).joinToString(" | ").ifBlank { "No sheet file id" }
                    val thumb = tocThumbCache[page]
                    val selected = page == currentPage

                    Surface(
                        tonalElevation = if (selected) 3.dp else 1.dp,
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPage(page) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 148.dp, height = 100.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "Sheet $page thumbnail",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp),
                                        contentScale = ContentScale.Fit,
                                        filterQuality = FilterQuality.None
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = "Image icon",

                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sheet $displayPage",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                                )
                                Text(
                                    fileLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${pageMeta?.parts?.size ?: 0} parts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SheetStatusBadge(sheetInfo.status)

                                    if (sheetInfo.committedBadCount > 0) {
                                        Surface(
                                            color = KKCThemeColors.statusColors.bad.copy(alpha = 0.12f),
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                "Bad ${sheetInfo.committedBadCount}",
                                                color = KKCThemeColors.statusColors.bad,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    } else if (sheetInfo.draftBadCount > 0) {
                                        Surface(
                                            color = KKCThemeColors.statusColors.skip.copy(alpha = 0.14f),
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                "Draft Bad ${sheetInfo.draftBadCount}",
                                                color = KKCThemeColors.statusColors.skip,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CncSearchModal(
    metadataPages: List<PageMetadata>,
    visiblePages: List<Int>,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val results: List<Pair<Int, List<Part>>> = remember(query, metadataPages, visiblePages) {
        if (query.isBlank()) emptyList()
        else {
            val q = query.trim().lowercase()
            visiblePages.mapNotNull { page ->
                val pageMeta = metadataPages.find { it.pageNumber == page }
                val matchingParts = pageMeta?.parts?.filter { part ->
                    part.cabNumber.toString().contains(q) ||
                    part.name.lowercase().contains(q) ||
                    part.room.lowercase().contains(q)
                }.orEmpty()
                if (matchingParts.isNotEmpty()) Pair(page, matchingParts) else null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveDialogDecor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
        ) {
            Text(
                "Search Parts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cabinet #, name, or room") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            when {
                query.isBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Type a cabinet number, name, or room to find sheets",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No matching parts found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = 112.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results) { (page, matchingParts) ->
                            val displayIndex = visiblePages.indexOf(page) + 1
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                tonalElevation = 1.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPage(page) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        "Sheet $displayIndex",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    matchingParts.take(3).forEach { part ->
                                        val roomSuffix = if (part.room.isNotBlank()) " · ${part.room}" else ""
                                        Text(
                                            "Cab ${part.cabNumber} · ${part.name}$roomSuffix",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (matchingParts.size > 3) {
                                        Text(
                                            "+${matchingParts.size - 3} more",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

private fun resolveTocPageMetadata(
    pageMetaByNumber: Map<Int, PageMetadata>,
    metadataPages: List<PageMetadata>,
    page: Int
): PageMetadata? {
    return pageMetaByNumber[page]
        ?: pageMetaByNumber[page - 1]
        ?: metadataPages.getOrNull((page - 1).coerceAtLeast(0))
}

private fun buildTocLoadOrder(
    pages: List<Int>,
    currentPage: Int
): List<Int> {
    if (pages.isEmpty()) return emptyList()
    val center = pages.indexOf(currentPage).let { if (it >= 0) it else 0 }
    val ordered = ArrayList<Int>(pages.size)
    ordered += pages[center]
    var offset = 1
    while (ordered.size < pages.size) {
        val left = center - offset
        if (left >= 0) ordered += pages[left]
        val right = center + offset
        if (right < pages.size) ordered += pages[right]
        offset++
    }
    return ordered
}

internal fun resolveCncSidecarFile(
    pdfFile: File,
    relativeOrAbsolute: String?
): File? {
    val trimmed = relativeOrAbsolute?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val candidate = File(trimmed)
    return if (candidate.isAbsolute) candidate else File(pdfFile.parentFile, trimmed)
}

private fun loadSheetThumbnailForToc(
    pdfFile: File,
    pageIndex: Int,
    thumbnailPath: String?
): Bitmap? {
    // 1) Prefer sidecar thumbnail if splitter generated one.
    val sidecarBitmap = loadCncSidecarBitmap(pdfFile, thumbnailPath)
    if (sidecarBitmap != null) {
        return resizeThumbnail(sidecarBitmap)
    }

    // 2) Fallback: generate thumbnail on tablet from embedded sheet image.
    val generated = extractLargestEmbeddedImage(pdfFile, pageIndex) ?: return null
    return resizeThumbnail(generated)
}

private fun loadCncSidecarBitmap(
    pdfFile: File,
    imagePath: String?
): Bitmap? {
    val imageFile = resolveCncSidecarFile(pdfFile, imagePath) ?: return null
    return try {
        if (imageFile.exists() && imageFile.isFile) {
            BitmapFactory.decodeFile(imageFile.absolutePath)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Load a part graphic. New splitter output bundles a material's part images
 * into one ZIP (`archiveRelPath`); the part's [graphicPath] basename is the
 * entry name inside it. When no archive is present (legacy jobs), fall back to
 * decoding the loose file the old way. Returns null (graceful, no crash) on any
 * missing file/entry or decode error.
 */
private fun loadPartGraphicBitmap(
    pdfFile: File,
    archiveRelPath: String?,
    graphicPath: String?
): Bitmap? {
    if (graphicPath.isNullOrBlank()) return null
    if (!archiveRelPath.isNullOrBlank()) {
        val archiveFile = resolveCncSidecarFile(pdfFile, archiveRelPath)
        if (archiveFile != null && archiveFile.exists() && archiveFile.isFile) {
            val entryName = File(graphicPath).name
            try {
                java.util.zip.ZipFile(archiveFile).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return null
                    zip.getInputStream(entry).use { input ->
                        return BitmapFactory.decodeStream(input)
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
        // Archive declared but missing on disk: fall through to loose-file
        // lookup so a partially-synced job still shows anything it has.
    }
    return loadCncSidecarBitmap(pdfFile, graphicPath)
}

private fun resizeThumbnail(src: Bitmap, maxW: Int = 420, maxH: Int = 280): Bitmap {
    if (src.width <= 1 || src.height <= 1) return src
    if (src.width <= maxW && src.height <= maxH) return src
    val scale = min(maxW.toFloat() / src.width.toFloat(), maxH.toFloat() / src.height.toFloat())
    val tw = (src.width * scale).toInt().coerceAtLeast(1)
    val th = (src.height * scale).toInt().coerceAtLeast(1)
    // Keep line art crisp for cabinet-part drawings (bilinear smoothing blurs thin strokes).
    return Bitmap.createScaledBitmap(src, tw, th, false)
}

internal fun extractLargestEmbeddedImage(pdfFile: java.io.File, pageIndex: Int): Bitmap? {
    var doc: PDDocument? = null
    return try {
        doc = PDDocument.load(pdfFile)
        if (pageIndex !in 0 until doc.numberOfPages) return null
        val page = doc.getPage(pageIndex)
        var bestBitmap: Bitmap? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestArea = 0L
        var bestNonWhite = 0.0
        var bestVariance = 0.0

        var fallbackBitmap: Bitmap? = null
        var fallbackArea = 0L

        fun walk(resources: PDResources?) {
            if (resources == null) return
            for (name: COSName in resources.xObjectNames) {
                val xo: PDXObject = resources.getXObject(name) ?: continue
                when (xo) {
                    is PDImageXObject -> {
                        val area = xo.width.toLong() * xo.height.toLong()
                        if (xo.width <= 1 || xo.height <= 1 || area <= 1L) continue
                        val bmp = try {
                            xo.image
                        } catch (e: Exception) {
                            Log.w(
                                OCR_TAG,
                                "Skipping undecodable embedded image pageIndex=$pageIndex size=${xo.width}x${xo.height}",
                                e
                            )
                            null
                        } ?: continue
                        if (area > fallbackArea) {
                            fallbackArea = area
                            fallbackBitmap = bmp
                        }

                        val (nonWhiteRatio, variance) = measureImageSignal(bmp)
                        val qualityOk = nonWhiteRatio >= 0.002 && variance >= 30.0
                        val score = nonWhiteRatio * 1000.0 + variance / 1000.0 + area / 1_000_000.0

                        if (qualityOk && score > bestScore) {
                            bestScore = score
                            bestArea = area
                            bestNonWhite = nonWhiteRatio
                            bestVariance = variance
                            bestBitmap = bmp
                        }
                    }
                    is PDFormXObject -> walk(xo.resources)
                }
            }
        }

        walk(page.resources)
        val raw = bestBitmap ?: fallbackBitmap ?: return null
        Log.i(
            OCR_TAG,
            "Embedded image selected: raw=${raw.width}x${raw.height}, area=${if (bestArea > 0) bestArea else fallbackArea}, nonWhite=${"%.5f".format(bestNonWhite)}, variance=${"%.2f".format(bestVariance)}"
        )
        raw
    } catch (e: Exception) {
        Log.e(OCR_TAG, "Embedded image extraction failed for pageIndex=$pageIndex", e)
        null
    } finally {
        try { doc?.close() } catch (_: Exception) {}
    }
}

private fun measureImageSignal(bitmap: Bitmap): Pair<Double, Double> {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 1 || h <= 1) return 0.0 to 0.0

    val stepX = (w / 320).coerceAtLeast(1)
    val stepY = (h / 320).coerceAtLeast(1)
    val row = IntArray(w)
    var count = 0L
    var nonWhite = 0L
    var sum = 0.0
    var sumSq = 0.0

    var y = 0
    while (y < h) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) {
            val c = row[x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 30 + g * 59 + b * 11) / 100.0
            if (lum < 250.0) nonWhite++
            sum += lum
            sumSq += lum * lum
            count++
            x += stepX
        }
        y += stepY
    }
    if (count <= 0L) return 0.0 to 0.0

    val mean = sum / count
    val variance = (sumSq / count) - (mean * mean)
    val nonWhiteRatio = nonWhite.toDouble() / count.toDouble()
    return nonWhiteRatio to variance
}

private fun com.kkc.sheettracker.data.models.PageMetadata?.toSidecarOcrMap(): Map<Int, List<Rect>> {
    val ocrBoxes = this?.ocrBoxes.orEmpty()
    if (ocrBoxes.isEmpty()) return emptyMap()
    return ocrBoxes.mapNotNull { (numText, boxes) ->
        val num = numText.toIntOrNull() ?: return@mapNotNull null
        val rects = boxes.map { Rect(it.left, it.top, it.right, it.bottom) }
        num to rects.take(2)
    }.toMap()
}

internal data class AnchoredZoomPan(val zoom: Float, val panX: Float, val panY: Float)

/**
 * Computes the next zoom/pan so the content point under [centroid] stays under the
 * fingers as the user pinches, instead of zooming from the view center.
 */
internal fun computeAnchoredZoomPan(
    zoom: Float,
    panX: Float,
    panY: Float,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    viewSize: IntSize,
    minZoom: Float,
    maxZoom: Float
): AnchoredZoomPan {
    val nextZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
    // Use the post-clamp ratio so the anchor stays correct even when the gesture
    // is clamped at min/max zoom (raw zoomChange would overshoot the correction).
    val appliedZoomChange = nextZoom / zoom
    val anchorX = centroid.x - viewSize.width / 2f
    val anchorY = centroid.y - viewSize.height / 2f
    val nextPanX = panX * appliedZoomChange + panChange.x + anchorX * (1f - appliedZoomChange)
    val nextPanY = panY * appliedZoomChange + panChange.y + anchorY * (1f - appliedZoomChange)
    return AnchoredZoomPan(nextZoom, nextPanX, nextPanY)
}

@Composable
private fun MarkupPdfPageView(
    bitmap: Bitmap,
    invertSheetBitmap: Boolean,
    resetZoomTrigger: Int,
    markupStrokes: List<PdfInkStroke>,
    inputEnabled: Boolean,
    markupToolState: PdfMarkupToolState,
    modifier: Modifier = Modifier,
    onMarkupStrokeAdded: (PdfInkStroke) -> Unit,
    onMarkupStrokeErased: (String) -> Unit,
    onTapEmpty: (() -> Unit)? = null
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    fun clampPan(targetZoom: Float, x: Float, y: Float): Pair<Float, Float> {
        if (viewSize == IntSize.Zero || targetZoom <= 1f) return 0f to 0f
        val maxPanX = (viewSize.width * (targetZoom - 1f)) / 2f
        val maxPanY = (viewSize.height * (targetZoom - 1f)) / 2f
        return x.coerceIn(-maxPanX, maxPanX) to y.coerceIn(-maxPanY, maxPanY)
    }

    LaunchedEffect(resetZoomTrigger) {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    LaunchedEffect(viewSize, zoom) {
        val (clampedX, clampedY) = clampPan(zoom, panX, panY)
        panX = clampedX
        panY = clampedY
    }

    val viewportState = remember(zoom, panX, panY, viewSize) {
        PdfViewportState(
            zoom = zoom,
            panX = panX,
            panY = panY,
            viewSize = viewSize
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTapEmpty?.invoke() })
            }
            .pointerInput(inputEnabled, markupToolState.allowFingerDrawing, viewSize) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val isStylusGesture =
                        firstDown.type == PointerType.Stylus || firstDown.type == PointerType.Eraser
                    val shouldHandleGesture = if (!inputEnabled) {
                        true
                    } else {
                        !isStylusGesture && !markupToolState.allowFingerDrawing
                    }
                    if (!shouldHandleGesture) {
                        do {
                            val blockedEvent = awaitPointerEvent()
                        } while (blockedEvent.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val anchored = computeAnchoredZoomPan(
                            zoom, panX, panY, zoomChange, panChange, centroid, viewSize, 1f, 5f
                        )
                        val (clampedX, clampedY) = clampPan(anchored.zoom, anchored.panX, anchored.panY)
                        zoom = anchored.zoom
                        panX = clampedX
                        panY = clampedY
                        event.changes.forEach { change ->
                            if (change.pressed) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Sheet PDF page",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                },
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            colorFilter = if (invertSheetBitmap) ColorFilter.colorMatrix(SHEET_BITMAP_INVERSION_COLOR_MATRIX) else null,
            filterQuality = FilterQuality.None
        )

        PdfMarkupOverlay(
            modifier = Modifier.fillMaxSize(),
            viewportState = viewportState,
            pageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat(),
            activeStrokes = markupStrokes,
            inputEnabled = inputEnabled,
            activeTool = markupToolState.activeTool,
            activeColor = markupToolState.activeColor,
            activeThickness = markupToolState.activeThickness,
            allowFingerDrawing = markupToolState.allowFingerDrawing,
            onStylusButtonEraserChanged = {
                markupToolState.isStylusButtonEraserActive = it
            },
            onStrokeAdded = onMarkupStrokeAdded,
            onStrokeErased = onMarkupStrokeErased
        )
    }
}

@Composable
internal fun DiagramView(
    bitmap: Bitmap,
    invertSheetBitmap: Boolean = false,
    parts: List<Part>,
    selectedPartNumber: Int?,
    diagramBboxes: Map<Int, List<Rect>>,
    resetZoomTrigger: Int,
    onTapPart: (Int) -> Unit,
    onLongPressPart: (Int) -> Unit,
    modifier: Modifier = Modifier,
    markupStrokes: List<PdfInkStroke> = emptyList(),
    onTapEmpty: (() -> Unit)? = null
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val viewportState = remember(zoom, panX, panY, viewSize) {
        PdfViewportState(zoom = zoom, panX = panX, panY = panY, viewSize = viewSize)
    }

    fun clampPan(targetZoom: Float, x: Float, y: Float): Pair<Float, Float> {
        if (viewSize == IntSize.Zero || targetZoom <= 1f) return 0f to 0f
        val maxPanX = (viewSize.width * (targetZoom - 1f)) / 2f
        val maxPanY = (viewSize.height * (targetZoom - 1f)) / 2f
        return x.coerceIn(-maxPanX, maxPanX) to y.coerceIn(-maxPanY, maxPanY)
    }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val anchored = computeAnchoredZoomPan(
            zoom, panX, panY, zoomChange, panChange, centroid, viewSize, 1f, 5f
        )
        val (clampedX, clampedY) = clampPan(anchored.zoom, anchored.panX, anchored.panY)
        zoom = anchored.zoom
        panX = clampedX
        panY = clampedY
    }

    LaunchedEffect(resetZoomTrigger) {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    LaunchedEffect(viewSize, zoom) {
        val (clampedX, clampedY) = clampPan(zoom, panX, panY)
        panX = clampedX
        panY = clampedY
    }

    // Fit + Center helpers: scale = min(viewW/bitmapW, viewH/bitmapH), centered both axes.
    // Must match Image(contentScale = Fit, alignment = Center) below.
    fun bitmapToView(bx: Float, by: Float): Pair<Float, Float> {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        val scale = minOf(vw / bitmap.width, vh / bitmap.height)
        val offsetX = (vw - bitmap.width * scale) / 2f
        val offsetY = (vh - bitmap.height * scale) / 2f
        return (bx * scale + offsetX) to (by * scale + offsetY)
    }

    fun viewToBitmap(vx: Float, vy: Float): Pair<Float, Float> {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        val scale = minOf(vw / bitmap.width, vh / bitmap.height)
        val offsetX = (vw - bitmap.width * scale) / 2f
        val offsetY = (vh - bitmap.height * scale) / 2f
        return ((vx - offsetX) / scale) to ((vy - offsetY) / scale)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(diagramBboxes, viewSize, zoom, panX, panY) {
                fun hitPart(tapX: Float, tapY: Float): Int? {
                    if (viewSize == IntSize.Zero) return null
                    val ux = (tapX - panX) / zoom
                    val uy = (tapY - panY) / zoom
                    val (bx, by) = viewToBitmap(ux, uy)
                    var bestPart: Int? = null
                    var bestDist = Float.MAX_VALUE
                    for ((num, rects) in diagramBboxes) {
                        for (rect in rects) {
                            val cx = (rect.left + rect.right) / 2f
                            val cy = (rect.top + rect.bottom) / 2f
                            val dist = sqrt((bx - cx) * (bx - cx) + (by - cy) * (by - cy))
                            if (dist < bestDist && dist < (90f / zoom)) {
                                bestDist = dist
                                bestPart = num
                            }
                        }
                    }
                    return bestPart
                }
                detectTapGestures(
                    onTap = { tap ->
                        val hit = hitPart(tap.x, tap.y)
                        if (hit != null) {
                            onTapPart(hit)
                        } else {
                            onTapEmpty?.invoke()
                        }
                    },
                    onLongPress = { tap ->
                        val hit = hitPart(tap.x, tap.y)
                        if (hit != null) onLongPressPart(hit)
                    }
                )
            }
            .transformable(state = transformState)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Sheet diagram",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = UiAlignment.Center,
                colorFilter = if (invertSheetBitmap) ColorFilter.colorMatrix(SHEET_BITMAP_INVERSION_COLOR_MATRIX) else null,
                filterQuality = FilterQuality.None
            )

            // Highlight overlay for selected part; transformed together with image.
            val statusColors = KKCThemeColors.statusColors
            val selBboxes = selectedPartNumber?.let { diagramBboxes[it] }
            if (!selBboxes.isNullOrEmpty() && viewSize != IntSize.Zero) {
                val pad = 12f
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (selBbox in selBboxes) {
                        val (vLeft, vTop) = bitmapToView(selBbox.left.toFloat(), selBbox.top.toFloat())
                        val (vRight, vBottom) = bitmapToView(selBbox.right.toFloat(), selBbox.bottom.toFloat())
                        drawRect(
                            color = statusColors.skip.copy(alpha = 0.40f),
                            topLeft = Offset(vLeft - pad - 3f, vTop - pad - 3f),
                            size = Size(vRight - vLeft + (pad + 3f) * 2, vBottom - vTop + (pad + 3f) * 2)
                        )
                        drawRect(
                            color = statusColors.skipBorder.copy(alpha = 0.33f),
                            topLeft = Offset(vLeft - pad, vTop - pad),
                            size = Size(vRight - vLeft + pad * 2, vBottom - vTop + pad * 2)
                        )
                        drawRect(
                            color = statusColors.bad,
                            topLeft = Offset(vLeft - pad, vTop - pad),
                            size = Size(vRight - vLeft + pad * 2, vBottom - vTop + pad * 2),
                            style = Stroke(width = 6f)
                        )
                    }
                }
            }
        }
        if (markupStrokes.isNotEmpty()) {
            PdfMarkupOverlay(
                modifier = Modifier.fillMaxSize(),
                viewportState = viewportState,
                pageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat(),
                activeStrokes = markupStrokes,
                inputEnabled = false,
                activeTool = DrawingTool.PEN,
                activeColor = Color.Red,
                activeThickness = 4f,
                allowFingerDrawing = false,
                onStylusButtonEraserChanged = {},
                onStrokeAdded = {},
                onStrokeErased = {}
            )
        }
    }
}


@Composable
private fun SheetLoadingPlaceholder(modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "sheetLoading")
    val pulseAlpha by shimmer.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sheetLoadingAlpha"
    )
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.86f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = pulseAlpha),
                        shape = MaterialTheme.shapes.small
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .height(14.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = pulseAlpha * 0.9f),
                        shape = MaterialTheme.shapes.small
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = pulseAlpha * 0.45f),
                        shape = MaterialTheme.shapes.medium
                    )
            )
        }
    }
}


@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PartsTable(
    parts: List<Part>,
    badParts: Set<Int>,
    draftBadParts: Set<Int>,
    summary: List<Pair<String, Int>>,
    isDarkTheme: Boolean,
    selectedPartType: String?,
    onSelectPartType: (String) -> Unit,
    selectedPartNumber: Int?,
    sortColumn: SortColumn,
    sortDirection: SortDirection,
    onSortChange: (SortColumn) -> Unit,
    numberColDp: Float,
    widthColDp: Float,
    lengthColDp: Float,
    nameWeight: Float,
    cabColDp: Float,
    roomColDp: Float,
    onResizeNumber: (Float) -> Unit,
    onResizeWidth: (Float) -> Unit,
    onResizeLength: (Float) -> Unit,
    onResizeNameWeight: (Float) -> Unit,
    onResizeCab: (Float) -> Unit,
    onResizeRoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onPartClick: (Part) -> Unit,
    onPartLongPress: (Part) -> Unit,
    onToggleBadPart: (Part) -> Unit
) {
    val actionColWidth = 40.dp
    var initialLoadComplete by remember(parts) { mutableStateOf(false) }
    LaunchedEffect(parts) {
        delay(300)
        initialLoadComplete = true
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (summary.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        summary.forEachIndexed { i, (name, count) ->
                            if (i > 0) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                )
                            }
                            val isSelected = selectedPartType == name
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { onSelectPartType(name) }
                                    .padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = "$name ($count)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fixed width for the marker column — not resizable.
        val rotColWidth = 20.dp

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Marker column header — muted, not sortable.
                Text(
                    "*",
                    modifier = Modifier.width(rotColWidth),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                SortHeader("#", Modifier.width(numberColDp.dp), sortColumn == SortColumn.NUMBER, sortDirection) { onSortChange(SortColumn.NUMBER) }
                ResizeHandle(onDrag = onResizeNumber)
                SortHeader("Width", Modifier.width(widthColDp.dp), sortColumn == SortColumn.WIDTH, sortDirection) { onSortChange(SortColumn.WIDTH) }
                ResizeHandle(onDrag = onResizeWidth)
                SortHeader("Length", Modifier.width(lengthColDp.dp), sortColumn == SortColumn.LENGTH, sortDirection) { onSortChange(SortColumn.LENGTH) }
                ResizeHandle(onDrag = onResizeLength)
                SortHeader("Name", Modifier.weight(nameWeight), sortColumn == SortColumn.NAME, sortDirection) { onSortChange(SortColumn.NAME) }
                ResizeHandle(onDrag = onResizeNameWeight)
                SortHeader("Cab", Modifier.width(cabColDp.dp), sortColumn == SortColumn.CAB, sortDirection) { onSortChange(SortColumn.CAB) }
                ResizeHandle(onDrag = onResizeCab)
                SortHeader("Room", Modifier.width(roomColDp.dp), sortColumn == SortColumn.ROOM, sortDirection) { onSortChange(SortColumn.ROOM) }
                ResizeHandle(onDrag = onResizeRoom)
                Spacer(Modifier.width(actionColWidth))
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
            itemsIndexed(items = parts, key = { _, part -> part.number }) { rowIndex, part ->
                val isBad = part.number in badParts
                val isDraft = part.number in draftBadParts
                val isSelected = part.number == selectedPartNumber
                val zebra = if (rowIndex % 2 == 0) {
                    if (isDarkTheme) Color(0xFF2E4057) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                val baseColor = when {
                    isBad -> KKCThemeColors.statusColors.bad.copy(alpha = 0.12f)
                    isDraft -> KKCThemeColors.statusColors.skip.copy(alpha = 0.12f)
                    isSelected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.24f)
                    else -> zebra
                }
                val opaqueBackgroundColor = if (baseColor.alpha < 1f) {
                    baseColor.compositeOver(MaterialTheme.colorScheme.surface)
                } else {
                    baseColor
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = opaqueBackgroundColor,
                    border = BorderStroke(
                        1.dp,
                        when {
                            isBad -> KKCThemeColors.statusColors.bad.copy(alpha = 0.4f)
                            isDraft -> KKCThemeColors.statusColors.skip.copy(alpha = 0.4f)
                            isSelected -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
                    ),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .animateEntrance(rowIndex, initialLoadComplete)
                ) {
                    Row(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onPartClick(part) },
                                onLongClick = { onPartLongPress(part) }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rotation and banding markers share the same fixed-width column.
                        // Spacers after each data cell match the 16dp ResizeHandle gaps in the header,
                        // keeping all columns aligned.
                        PartMarkers(
                            rotated = part.rotated,
                            banding = part.banding,
                            modifier = Modifier.width(rotColWidth)
                        )
                        Text("${part.number}", Modifier.width(numberColDp.dp), style = DimensionTextStyle)
                        Spacer(Modifier.width(16.dp))
                        Text("${part.width}", Modifier.width(widthColDp.dp), style = DimensionTextStyle)
                        Spacer(Modifier.width(16.dp))
                        Text("${part.length}", Modifier.width(lengthColDp.dp), style = DimensionTextStyle)
                        Spacer(Modifier.width(16.dp))
                        Text(part.name, Modifier.weight(nameWeight), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(16.dp))
                        Text("${part.cabNumber}", Modifier.width(cabColDp.dp), fontSize = 13.sp)
                        Spacer(Modifier.width(16.dp))
                        Text(part.room, Modifier.width(roomColDp.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(16.dp))

                        IconButton(
                            onClick = { onToggleBadPart(part) },
                            modifier = Modifier
                                .width(actionColWidth)
                                .height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = if (isBad) "Unflag part" else "Flag bad part",
                                tint = when {
                                    isBad -> KKCThemeColors.statusColors.bad
                                    isDraft -> KKCThemeColors.statusColors.skip
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartMarkers(
    rotated: Boolean,
    banding: String?,
    modifier: Modifier = Modifier
) {
    val markers = partMarkers(rotated, banding)
    if (markers.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }

    Box(
        modifier = modifier.height(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (PartMarker.Banding in markers) {
            EdgeBandingIcon(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        if (PartMarker.Rotation in markers) {
            Text(
                "*",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-5).dp),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 13.sp,
                color = Color(0xFFE65100)
            )
        }
    }
}

@Composable
private fun EdgeBandingIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val scale = min(size.width, size.height) / 100f
            nativeCanvas.save()
            nativeCanvas.scale(scale, scale)
            nativeCanvas.translate(30f, 40f)

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val bandPath = PathParser.createPathFromPathData(
                "M -4 0 A 4 4 0 0 1 4 0 A 8 8 0 0 1 -12 0 " +
                    "A 12 12 0 0 1 12 0 A 16 16 0 0 1 -20 0 " +
                    "A 20 20 0 0 1 20 0 C 20 20 28 27 40 27 L 55 27"
            )
            nativeCanvas.drawPath(bandPath, strokePaint)
            nativeCanvas.restore()

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                style = Paint.Style.FILL
            }
            nativeCanvas.drawRect(
                5f * scale,
                70f * scale,
                88f * scale,
                85f * scale,
                fillPaint
            )
        }
    }
}

internal enum class PartMarker {
    Rotation,
    Banding
}

internal fun partMarkers(rotated: Boolean, banding: String?): List<PartMarker> = buildList {
    if (rotated) add(PartMarker.Rotation)
    if (!banding.isNullOrBlank()) add(PartMarker.Banding)
}


private fun inferSheetFiles(pageMeta: com.kkc.sheettracker.data.models.PageMetadata?): List<String> {
    val fromSidecar = pageMeta?.sheetFiles?.filter { it.isNotBlank() }?.distinct().orEmpty()
    if (fromSidecar.isNotEmpty()) return fromSidecar

    val single = pageMeta?.sheetId?.trim().orEmpty()
    if (single.isBlank()) return emptyList()

    // Safe fallback: if sidecar has only one side id, infer the common Z/A partner.
    // Example: R280602Z -> [R280602Z, R280602A]
    val suffix = single.lastOrNull()
    return when (suffix) {
        'Z' -> listOf(single, single.dropLast(1) + "A")
        'A' -> listOf(single.dropLast(1) + "Z", single)
        else -> listOf(single)
    }
}

private fun formatSheetDimensions(dimensions: List<Double>?): String? {
    if (dimensions == null || dimensions.size < 2) return null
    fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    return "${fmt(dimensions[0])} × ${fmt(dimensions[1])}"
}

private fun getSegmentShape(index: Int, count: Int, cornerRadius: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.Shape {
    return when {
        count <= 1 -> androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
        index == 0 -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius)
        index == count - 1 -> androidx.compose.foundation.shape.RoundedCornerShape(topEnd = cornerRadius, bottomEnd = cornerRadius)
        else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
    }
}
