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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.components.ClockInButton
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarCncDecoration
import com.kkc.sheettracker.ui.components.PdfViewportState
import com.kkc.sheettracker.ui.components.ResizeHandle
import com.kkc.sheettracker.ui.components.SheetStatusBadge
import com.kkc.sheettracker.ui.components.SortColumn
import com.kkc.sheettracker.ui.components.SortDirection
import com.kkc.sheettracker.ui.components.SortHeader
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt
import java.util.ArrayDeque

private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
private val ROOM_PAREN_REGEX = Regex("""\(([^)]+)\)""")
private val ROOM_ILLEGAL_CHARS_REGEX = Regex("""[/\\:*?"<>|]""")
private val ROOM_WHITESPACE_REGEX = Regex("""\s+""")
private const val OCR_TAG = "KKC_OCR"
private const val VIEWER_REF_TAG = "KKC_VIEWER_REF"
private const val VIEWER_PARITY_TAG = "KKC_APP_STATE_PARITY_VIEWER"
private const val VIEWER_PREPARED_TAG = "KKC_PREPARED_STATE"
private const val RENDER_CACHE_MAX_PAGES = 6
private const val RENDER_PREWARM_RADIUS = 2

private data class RenderedSheetPage(
    val pageBitmap: Bitmap?,
    val diagramBitmap: Bitmap?
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

/**
 * Whether a page bitmap evicted from the render LRU cache is safe to recycle. It must exist,
 * not already be recycled, and must not be the bitmap currently bound to the on-screen page
 * (which the UI is still drawing from) -- recycling that would crash the next draw call.
 */
internal fun shouldRecycleEvictedPageBitmap(evicted: Bitmap?, currentlyDisplayed: Bitmap?): Boolean {
    return evicted != null && evicted !== currentlyDisplayed && !evicted.isRecycled
}

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
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeDTarget: (cabinet: String?, assemblyPage: Int?, plansPage: Int?, room: String?) -> Unit,
    onBack: () -> Unit,
    onMaterialUnavailable: () -> Unit = onBack,
    onUiVisibilityChanged: (Boolean) -> Unit = {},
    clockInState: ClockInState? = null
) {
    val scanState by scanCoordinator.state.collectAsState()
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
    var showFullPdfPage by remember { mutableStateOf(false) }
    var markupEnabled by remember(jobFolderName, pdfFilename) { mutableStateOf(false) }
    var markupStrokesVisible by remember(jobFolderName, pdfFilename) { mutableStateOf(true) }
    val markupToolState = rememberPdfMarkupToolState()
    val localMarkupStrokes = remember(jobFolderName, pdfFilename) { mutableStateListOf<PdfInkStroke>() }
    val localMarkupDeletedIds = remember(jobFolderName, pdfFilename) { mutableStateListOf<String>() }
    var markupContentVersion by remember(jobFolderName, pdfFilename) { mutableStateOf(0L) }
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
    var prewarmJob by remember { mutableStateOf<Job?>(null) }
    var diagramBboxes by remember { mutableStateOf<Map<Int, List<Rect>>>(emptyMap()) }
    var renderEffectCount by remember { mutableIntStateOf(0) }
    var statusEffectCount by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val cabinetSheetIndex by produceState<CabinetSheetIndex?>(null, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    }
    val hasAssemblyReference by produceState(false, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY) }
    }
    val hasPlansReference by produceState(false, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS) }
    }
    val hasThreeDAssets by produceState(false, jobFolderName) {
        value = withContext(Dispatchers.IO) { jobRepository.hasThreeDAssets(jobFolderName) }
    }
    val defaultModalDoc: ReferenceDocType? = when {
        hasPlansReference -> ReferenceDocType.PLANS_ELEVATIONS
        hasAssemblyReference -> ReferenceDocType.ASSEMBLY
        else -> null
    }
    val modalReferenceData = com.kkc.sheettracker.ui.viewer.rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = referenceModal.snapshot.docType,
        refreshGeneration = scanState.snapshot.generation,
        isDarkTheme = isDarkTheme
    )
    LaunchedEffect(selectedCabinetNumber, referenceModal.snapshot.isOpen, referenceModal.snapshot.docType) {
        val cabinet = selectedCabinetNumber
        if (!referenceModal.snapshot.isOpen || cabinet == null) return@LaunchedEffect
        val target = com.kkc.sheettracker.ui.components.resolveJumpPage(
            modalReferenceData.navigatorCabinetToPages, cabinet
        )
        if (target != null) referenceModal.setPage(target) else referenceModal.showNoRefNote()
    }

    LaunchedEffect(pdfMarkupStore, jobFolderName) {
        if (pdfMarkupStore == null) {
            markupContentVersion = 0L
            return@LaunchedEffect
        }
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            markupContentVersion = withContext(Dispatchers.IO) {
                pdfMarkupStore.trackerContentVersion(jobFolderName)
            }
            delay(1000)
        }
    }

    LaunchedEffect(pdfMarkupStore, jobFolderName, pdfFilename, currentPage, markupContentVersion) {
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
    val penMarkupOverlayActive = markupEnabled
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
        renderCache[page] = rendered
        touchRenderCache(page)
        while (renderCacheOrder.size > RENDER_CACHE_MAX_PAGES) {
            val stalePage = renderCacheOrder.removeFirst()
            if (stalePage != page) {
                val evicted = renderCache.remove(stalePage)
                // Never recycle diagramBitmap: it is owned/evicted by ProgressStore's own
                // prepared-page cache. Only the page render's own pageBitmap belongs to us.
                val evictedPageBitmap = evicted?.pageBitmap
                if (shouldRecycleEvictedPageBitmap(evictedPageBitmap, pageBitmap)) {
                    evictedPageBitmap?.recycle()
                }
            }
        }
    }

    fun clearRenderCache(reason: PreparedStateInvalidationReason) {
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
        source: String
    ): RenderedSheetPage? {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return null
        return try {
            var outPage: Bitmap? = null
            var outDiagram: Bitmap? = null
            val fd = ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val pageIndex = pageNumber - 1
            Log.d(OCR_TAG, "render_fn: pageIndex=$pageIndex pageCount=${renderer.pageCount} file=${targetPdfFile.path} exists=${targetPdfFile.exists()}")
            if (pageIndex in 0 until renderer.pageCount) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    renderer.close()
                    fd.close()
                    return null
                }
                val page = renderer.openPage(pageIndex)
                val scale = 2
                val bmp = Bitmap.createBitmap(
                    page.width * scale,
                    page.height * scale,
                    Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(android.graphics.Color.WHITE)
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    page.close()
                    bmp.recycle()
                    renderer.close()
                    fd.close()
                    return null
                }
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                outPage = bmp
                val key = preparedPageKey(targetMaterial, pageNumber)
                outDiagram = progressStore.getOrPrepareDiagramBitmap(
                    key = key,
                    source = source
                ) {
                    val pageMeta = resolvePageMetadata(targetMaterial, pageNumber)
                    extractLargestEmbeddedImage(targetPdfFile, pageIndex)
                        ?: loadCncSidecarBitmap(targetPdfFile, pageMeta?.thumbnailPath)
                }
            }
            renderer.close()
            fd.close()
            RenderedSheetPage(pageBitmap = outPage, diagramBitmap = outDiagram)
        } catch (e: Exception) {
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

    LaunchedEffect(jobFolderName, pdfFilename, scanState.snapshot.generation) {
        val job = scanState.snapshot.jobs.find { it.folderName == jobFolderName }
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

    suspend fun runOcrForPage(page: Int, expectedSet: Set<Int>): Map<Int, List<Rect>> {
        val material = currentMaterial ?: return emptyMap()
        val cached = progressStore.getOcrCache(jobFolderName, pdfFilename, page, fileFingerprint)
        if (cached != null) return cached
        val pageMeta = resolvePageMetadata(material, page)
        val sidecar = pageMeta.toSidecarOcrMap()
        if (sidecar.isNotEmpty()) {
            progressStore.saveOcrCache(jobFolderName, pdfFilename, page, fileFingerprint, sidecar)
            return sidecar
        }
        val targetExpected = when {
            expectedSet.isNotEmpty() -> expectedSet
            else -> (1..500).toSet()
        }
        val missing = targetExpected - sidecar.keys
        val preparedKey = preparedPageKey(material, page)
        val embedded = progressStore.getOrPrepareDiagramBitmap(
            key = preparedKey,
            source = "ocr_current"
        ) {
            withContext(Dispatchers.IO) { extractLargestEmbeddedImage(pdfFile, page - 1) }
        }
        val display = embedded ?: return sidecar
        val found = withContext(Dispatchers.Default) {
            runMlKitOcrOnDiagramImage(display, if (missing.isEmpty()) targetExpected else missing)
        }
        val merged = mergeOcrMaps(sidecar, found)
        progressStore.saveOcrCache(jobFolderName, pdfFilename, page, fileFingerprint, merged)
        return merged
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
            touchRenderCache(currentPage)
            pageBitmap = cached.pageBitmap
            diagramBitmap = cached.diagramBitmap
            Log.i(
                OCR_TAG,
                "Page $currentPage render cache hit: pageBitmap=${pageBitmap?.width}x${pageBitmap?.height}, diagram=${diagramBitmap?.width}x${diagramBitmap?.height}"
            )
        } else {
            Log.d(
                VIEWER_PREPARED_TAG,
                "render_recompute_reason=cache_miss page=$currentPage job=$jobFolderName pdf=$pdfFilename"
            )
            delay(150L)
            val rendered = withContext(Dispatchers.IO) {
                renderPageFromPdf(
                    targetMaterial = material,
                    targetPdfFile = pdfFile,
                    pageNumber = currentPage,
                    source = "render_current"
                )
            }
            if (rendered != null) {
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

        if (diagramBitmap != null) {
            val expectedFromParts = parts.map { it.number }.toSet()
            try {
                if (!progressStore.hasOcrCache(jobFolderName, pdfFilename, currentPage, fileFingerprint)) {
                    delay(300L)
                }
                diagramBboxes = runOcrForPage(currentPage, expectedFromParts)
                Log.i(
                    OCR_TAG,
                    "Page $currentPage OCR ready: matched=${diagramBboxes.size}, fromCache=${progressStore.hasOcrCache(jobFolderName, pdfFilename, currentPage, fileFingerprint)}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(OCR_TAG, "Page $currentPage OCR error", e)
            }
        }
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
                val shouldRender = withContext(Dispatchers.Main) { !renderCache.containsKey(p) }
                if (!shouldRender) continue
                val rendered = renderPageFromPdf(
                    targetMaterial = material,
                    targetPdfFile = pdfFile,
                    pageNumber = p,
                    source = "render_prewarm"
                ) ?: continue
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    rendered.pageBitmap?.recycle()
                    break
                }
                withContext(Dispatchers.Main) {
                    cacheRenderedPage(p, rendered)
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

    LaunchedEffect(jobFolderName, pdfFilename, fileFingerprint, currentPage) {
        if (currentMaterial == null || fileFingerprint.isBlank()) return@LaunchedEffect
        prewarmJob?.cancel()
        prewarmJob = scope.launch(Dispatchers.Default) {
            delay(400L)
            val current = currentMaterial ?: return@launch
            val queue = mutableListOf<Pair<Material, Int>>()
            for (p in current.visibleSheetPages()) {
                if (p != currentPage) queue += current to p
            }
            jobMaterials.filter { it.pdfFilename != current.pdfFilename }.forEach { m ->
                for (p in m.visibleSheetPages()) queue += m to p
            }

            for ((material, page) in queue) {
                if (!isActive) break
                if (material.fileFingerprint.isBlank()) continue
                if (progressStore.hasOcrCache(jobFolderName, material.pdfFilename, page, material.fileFingerprint)) {
                    continue
                }
                try {
                    val pageMeta = resolvePageMetadata(material, page)
                    val sidecar = pageMeta.toSidecarOcrMap()
                    if (sidecar.isNotEmpty()) {
                        progressStore.saveOcrCache(jobFolderName, material.pdfFilename, page, material.fileFingerprint, sidecar)
                        continue
                    }
                    val expectedFromParts = pageMeta?.parts?.map { it.number }?.toSet().orEmpty()
                    val targetExpected = when {
                        expectedFromParts.isNotEmpty() -> expectedFromParts
                        else -> (1..500).toSet()
                    }
                    val targetPdf = jobRepository.getPdfFile(jobFolderName, material.pdfFilename)
                    val key = preparedPageKey(material, page)
                    val embedded = progressStore.getOrPrepareDiagramBitmap(
                        key = key,
                        source = "ocr_prewarm"
                    ) {
                        withContext(Dispatchers.IO) { extractLargestEmbeddedImage(targetPdf, page - 1) }
                    } ?: continue
                    val missing = targetExpected - sidecar.keys
                    val found = if (missing.isEmpty()) emptyMap() else runMlKitOcrOnDiagramImage(embedded, missing)
                    val merged = mergeOcrMaps(sidecar, found)
                    progressStore.saveOcrCache(jobFolderName, material.pdfFilename, page, material.fileFingerprint, merged)
                    delay(120L)
                } catch (_: Exception) {
                }
            }
        }
    }

    val materialName = remember(pdfFilename) {
        pdfFilename.removeSuffix(".pdf").let { name ->
            val dashIdx = name.indexOf(" - ")
            if (dashIdx >= 0) name.substring(dashIdx + 3) else name
        }
    }
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
                            materialName,
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
                    val clockInJob = scanState.snapshot.jobs.find { it.folderName == jobFolderName }
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
                    IconButton(
                        onClick = { markupEnabled = !markupEnabled }
                    ) {
                        Icon(
                            Icons.Default.Create,
                            contentDescription = if (markupEnabled) "Disable pen mode" else "Enable pen mode",
                            tint = if (markupEnabled) MaterialTheme.colorScheme.primary else topBarTextColor
                        )
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
                    if (chips.isNotEmpty() || sheetSizeLabel != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            chips.forEach { label ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(label) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                            }
                            if (sheetSizeLabel != null) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text(sheetSizeLabel) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pdfFilename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (hasAssemblyReference || hasPlansReference) {
                            AssistChip(
                                onClick = { referenceModal.toggleOpen(defaultModalDoc) },
                                label = { Text("Popup Viewer") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                                    )
                                }
                            )
                        }
                        val remakeLabel = currentPageRemake?.label?.takeIf { it.isNotBlank() }
                        if (remakeLabel != null) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text(remakeLabel) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Remake"
                                    )
                                }
                            )
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



            val bitmap = if (showFullPdfPage) pageBitmap else (diagramBitmap ?: pageBitmap)
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
                    Crossfade(targetState = bitmap, animationSpec = tween(150), label = "viewerBitmap") { activeBitmap ->
                        if (activeBitmap != null) {
                            if (showFullPdfPage || markupEnabled) {
                                MarkupPdfPageView(
                                    bitmap = activeBitmap,
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
        com.kkc.sheettracker.ui.components.ReferenceModalHost(
            state = referenceModal,
            jobRepository = jobRepository,
            jobFolderName = jobFolderName,
            refreshGeneration = scanState.snapshot.generation,
            isDarkTheme = isDarkTheme,
            hasPlans = hasPlansReference,
            hasAssembly = hasAssemblyReference,
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

private suspend fun runMlKitOcrOnDiagramImage(
    diagramBitmap: Bitmap,
    expectedNums: Set<Int>
): Map<Int, List<Rect>> {
    Log.i(OCR_TAG, "OCR preprocess: input=${diagramBitmap.width}x${diagramBitmap.height}")
    val merged = mutableMapOf<Int, MutableList<OcrHit>>()

    val variantProducers = listOf<suspend () -> Bitmap>(
        { diagramBitmap },
        { withContext(Dispatchers.Default) { toGrayscaleContrast(diagramBitmap, contrast = 1.20f) } },
        { withContext(Dispatchers.Default) { preprocessForOcr(diagramBitmap, thresholdBias = 0, erode = false) } },
        { withContext(Dispatchers.Default) { preprocessForOcr(diagramBitmap, thresholdBias = 10, erode = false) } },
        { withContext(Dispatchers.Default) { preprocessForOcr(diagramBitmap, thresholdBias = 0, erode = true) } },
        { withContext(Dispatchers.Default) { preprocessForOcr(diagramBitmap, thresholdBias = 10, erode = true) } }
    )

    variantProducers.forEachIndexed { idx, producer ->
        kotlinx.coroutines.yield()
        val bmp = producer()
        try {
            val r = runMlKitRawMultiPass(bmp, expectedNums)
            Log.i(OCR_TAG, "OCR variant[$idx] matchedNums=${r.size}")
            for ((n, hits) in r) {
                merged.getOrPut(n) { mutableListOf() }.addAll(hits)
            }
        } finally {
            if (idx > 0 && !bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }
    val deduped = merged.mapValues { (_, hits) ->
        hits.sortedByDescending { it.conf }.fold(mutableListOf<OcrHit>()) { acc, h ->
            val dup = acc.any { a ->
                kotlin.math.abs(a.box.left - h.box.left) < 10 &&
                    kotlin.math.abs(a.box.top - h.box.top) < 10 &&
                    kotlin.math.abs(a.box.right - h.box.right) < 10 &&
                    kotlin.math.abs(a.box.bottom - h.box.bottom) < 10
            }
            if (!dup) acc.add(h)
            acc
        }.take(2)
    }
    Log.i(OCR_TAG, "OCR merged result count=${deduped.size}")
    val base = deduped.mapValues { it.value.map { hit -> hit.box } }.toMutableMap()

    // If this is a double-sided layout (two sheet frames), use OCR from BOTH sides only.
    // No synthetic mirroring here: we trust actual OCR detections on each side.
    val frames = detectSheetFramesFromBoxes(base, diagramBitmap.width, diagramBitmap.height)
    if (frames != null) {
        val (leftFrame, rightFrame) = frames
        val rebuilt = mutableMapOf<Int, List<Rect>>()
        for ((n, hits) in deduped) {
            val boxes = hits.map { it.box }
            val leftHits = boxes.filter {
                val cx = (it.left + it.right) / 2f
                leftFrame.containsX(cx)
            }
            val rightHits = boxes.filter {
                val cx = (it.left + it.right) / 2f
                rightFrame.containsX(cx)
            }
            val chosen = mutableListOf<Rect>()

            // Keep one OCR hit per side (if available), prefer lower-left placement
            // because labels are typically near bottom-left of the part.
            if (leftHits.isNotEmpty()) {
                chosen += leftHits.minByOrNull { it.left - (it.top * 0.15f) }!!
            }
            if (rightHits.isNotEmpty()) {
                chosen += rightHits.minByOrNull { it.left - (it.top * 0.15f) }!!
            }

            // Single-sided fallback for this number.
            if (chosen.isEmpty() && boxes.isNotEmpty()) chosen += boxes.first()
            if (chosen.isNotEmpty()) rebuilt[n] = chosen
        }
        val twoSide = rebuilt.values.count { it.size >= 2 }
        Log.i(OCR_TAG, "OCR double-sided (true OCR both sides): nums=${rebuilt.size}, twoSide=$twoSide")
        return rebuilt
    }

    // Double-sided fallback:
    // If sheet is mirrored left/right and a number only OCRs on one side,
    // infer the counterpart box using median horizontal offset from paired detections.
    val inferred = inferMirroredBoxes(base, diagramBitmap.width, diagramBitmap.height)
    for ((n, boxes) in inferred) {
        val list = base.getOrPut(n) { emptyList() }.toMutableList()
        for (b in boxes) {
            val dup = list.any {
                kotlin.math.abs(it.left - b.left) < 10 &&
                    kotlin.math.abs(it.top - b.top) < 10 &&
                    kotlin.math.abs(it.right - b.right) < 10 &&
                    kotlin.math.abs(it.bottom - b.bottom) < 10
            }
            if (!dup && list.size < 2) list.add(b)
        }
        base[n] = list
    }
    val twoSide = base.values.count { it.size >= 2 }
    Log.i(OCR_TAG, "OCR post-infer: nums=${base.size}, twoSide=$twoSide")
    return base
}

private data class SheetFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    fun containsX(x: Float): Boolean = x in left..right
}

private data class FlipModel(val flipX: Boolean, val flipY: Boolean)

private fun detectSheetFramesFromBoxes(
    detected: Map<Int, List<Rect>>,
    imageWidth: Int,
    imageHeight: Int
): Pair<SheetFrame, SheetFrame>? {
    val leftPts = mutableListOf<Pair<Float, Float>>()
    val rightPts = mutableListOf<Pair<Float, Float>>()
    val mid = imageWidth / 2f
    for ((_, boxes) in detected) {
        for (b in boxes) {
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            if (cx < mid) leftPts.add(cx to cy) else rightPts.add(cx to cy)
        }
    }
    if (leftPts.size < 8 || rightPts.size < 8) return null

    fun quantile(values: List<Float>, q: Float): Float {
        val s = values.sorted()
        if (s.isEmpty()) return 0f
        val idx = ((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)
        return s[idx]
    }

    val lxs = leftPts.map { it.first }
    val lys = leftPts.map { it.second }
    val rxs = rightPts.map { it.first }
    val rys = rightPts.map { it.second }

    val left = SheetFrame(
        left = (quantile(lxs, 0.02f) - 30f).coerceAtLeast(0f),
        top = (quantile(lys, 0.02f) - 20f).coerceAtLeast(0f),
        right = (quantile(lxs, 0.98f) + 30f).coerceAtMost(imageWidth.toFloat()),
        bottom = (quantile(lys, 0.98f) + 20f).coerceAtMost(imageHeight.toFloat())
    )
    val right = SheetFrame(
        left = (quantile(rxs, 0.02f) - 30f).coerceAtLeast(0f),
        top = (quantile(rys, 0.02f) - 20f).coerceAtLeast(0f),
        right = (quantile(rxs, 0.98f) + 30f).coerceAtMost(imageWidth.toFloat()),
        bottom = (quantile(rys, 0.98f) + 20f).coerceAtMost(imageHeight.toFloat())
    )
    if (left.right >= right.left) return null
    return left to right
}

private fun learnFlipModel(
    detected: Map<Int, List<Rect>>,
    left: SheetFrame,
    right: SheetFrame
): FlipModel {
    data class Sample(val lx: Float, val ly: Float, val rx: Float, val ry: Float)
    val samples = mutableListOf<Sample>()
    for ((_, boxes) in detected) {
        if (boxes.size < 2) continue
        val l = boxes.minByOrNull { (it.left + it.right) / 2f } ?: continue
        val r = boxes.maxByOrNull { (it.left + it.right) / 2f } ?: continue
        val lcx = (l.left + l.right) / 2f
        val lcy = (l.top + l.bottom) / 2f
        val rcx = (r.left + r.right) / 2f
        val rcy = (r.top + r.bottom) / 2f
        if (!left.containsX(lcx) || !right.containsX(rcx)) continue
        val lnx = ((lcx - left.left) / left.width).coerceIn(0f, 1f)
        val lny = ((lcy - left.top) / left.height).coerceIn(0f, 1f)
        val rnx = ((rcx - right.left) / right.width).coerceIn(0f, 1f)
        val rny = ((rcy - right.top) / right.height).coerceIn(0f, 1f)
        samples.add(Sample(lnx, lny, rnx, rny))
    }
    if (samples.size < 3) return FlipModel(flipX = false, flipY = true)

    val candidates = listOf(
        FlipModel(false, false),
        FlipModel(true, false),
        FlipModel(false, true),
        FlipModel(true, true)
    )
    val best = candidates.minByOrNull { m ->
        samples.sumOf { s ->
            val px = if (m.flipX) 1f - s.lx else s.lx
            val py = if (m.flipY) 1f - s.ly else s.ly
            val dx = px - s.rx
            val dy = py - s.ry
            (dx * dx + dy * dy).toDouble()
        }
    } ?: FlipModel(false, true)
    Log.i(OCR_TAG, "Mirror learned model: samples=${samples.size}, flipX=${best.flipX}, flipY=${best.flipY}")
    return best
}

private fun inferMirroredBoxes(
    detected: Map<Int, List<Rect>>,
    imageWidth: Int,
    imageHeight: Int
): Map<Int, List<Rect>> {
    fun deriveFramesFromDetections(): Pair<SheetFrame, SheetFrame>? =
        detectSheetFramesFromBoxes(detected, imageWidth, imageHeight)

    fun mapFlipVertical(
        srcX: Float,
        srcY: Float,
        src: SheetFrame,
        dst: SheetFrame
    ): Pair<Float, Float> {
        val nx = ((srcX - src.left) / src.width).coerceIn(0f, 1f)
        val ny = ((srcY - src.top) / src.height).coerceIn(0f, 1f)
        val tx = dst.left + nx * dst.width
        val ty = dst.top + (1f - ny) * dst.height
        return tx to ty
    }

    // Preferred strategy:
    // derive each sheet frame and mirror within that frame (flip like turning the sheet over).
    val derivedFrames = deriveFramesFromDetections()
    if (derivedFrames != null) {
        val (leftFrame, rightFrame) = derivedFrames
        Log.i(
            OCR_TAG,
            "Mirror infer frames: L=(${leftFrame.left.toInt()},${leftFrame.top.toInt()},${leftFrame.right.toInt()},${leftFrame.bottom.toInt()}) " +
                "R=(${rightFrame.left.toInt()},${rightFrame.top.toInt()},${rightFrame.right.toInt()},${rightFrame.bottom.toInt()})"
        )

        val out = mutableMapOf<Int, List<Rect>>()
        for ((n, boxes) in detected) {
            if (boxes.size >= 2) continue
            val b = boxes.firstOrNull() ?: continue
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            val w = b.width().toFloat()
            val h = b.height().toFloat()

            val (targetCx, targetCy) = when {
                leftFrame.containsX(cx) -> mapFlipVertical(cx, cy, leftFrame, rightFrame)
                rightFrame.containsX(cx) -> mapFlipVertical(cx, cy, rightFrame, leftFrame)
                else -> continue
            }

            val inferred = Rect(
                (targetCx - w / 2f).toInt().coerceIn(0, imageWidth - 1),
                (targetCy - h / 2f).toInt().coerceIn(0, imageHeight - 1),
                (targetCx + w / 2f).toInt().coerceIn(0, imageWidth - 1),
                (targetCy + h / 2f).toInt().coerceIn(0, imageHeight - 1)
            )
            out[n] = listOf(inferred)
        }
        return out
    }

    // Fallback strategy:
    // infer by median offsets from already paired detections.
    val midX = imageWidth / 2f
    val deltasX = mutableListOf<Float>()
    val deltaY = mutableListOf<Float>()   // same-orientation model: yR - yL
    val sumY = mutableListOf<Float>()     // flipped-vertical model: yR + yL
    data class PairSample(val yL: Float, val yR: Float)
    val samples = mutableListOf<PairSample>()

    for ((_, boxes) in detected) {
        if (boxes.size < 2) continue
        val left = boxes.minByOrNull { (it.left + it.right) / 2f } ?: continue
        val right = boxes.maxByOrNull { (it.left + it.right) / 2f } ?: continue
        val lx = (left.left + left.right) / 2f
        val rx = (right.left + right.right) / 2f
        if (!(lx < midX && rx > midX)) continue
        val ly = (left.top + left.bottom) / 2f
        val ry = (right.top + right.bottom) / 2f
        deltasX.add(rx - lx)
        deltaY.add(ry - ly)
        sumY.add(ry + ly)
        samples.add(PairSample(ly, ry))
    }

    if (samples.size < 3) return emptyMap()

    val medianDeltaX = deltasX.sorted()[deltasX.size / 2]
    val medianDy = deltaY.sorted()[deltaY.size / 2]
    val medianSy = sumY.sorted()[sumY.size / 2]

    // Decide Y mapping model from observed pairs.
    val errSame = samples.map { kotlin.math.abs((it.yL + medianDy) - it.yR) }.average()
    val errFlip = samples.map { kotlin.math.abs((medianSy - it.yL) - it.yR) }.average()
    val useFlipY = errFlip < errSame
    Log.i(OCR_TAG, "Mirror infer model: samples=${samples.size}, dx=$medianDeltaX, dy=$medianDy, sy=$medianSy, useFlipY=$useFlipY")

    val out = mutableMapOf<Int, List<Rect>>()
    for ((n, boxes) in detected) {
        if (boxes.size >= 2) continue
        val b = boxes.firstOrNull() ?: continue
        val cx = (b.left + b.right) / 2f
        val cy = (b.top + b.bottom) / 2f
        val w = b.width()
        val h = b.height()

        val targetCx = if (cx < midX) cx + medianDeltaX else cx - medianDeltaX
        if (targetCx !in 0f..imageWidth.toFloat()) continue
        val targetCy = if (useFlipY) {
            medianSy - cy
        } else {
            if (cx < midX) cy + medianDy else cy - medianDy
        }.coerceIn(0f, imageHeight.toFloat())

        val inferred = Rect(
            (targetCx - w / 2f).toInt().coerceAtLeast(0),
            (targetCy - h / 2f).toInt().coerceAtLeast(0),
            (targetCx + w / 2f).toInt().coerceAtMost(imageWidth - 1),
            (targetCy + h / 2f).toInt().coerceAtMost(imageHeight - 1)
        )
        out[n] = listOf(inferred)
    }
    return out
}

private data class OcrHit(val box: Rect, val conf: Float)

private suspend fun runMlKitRawMultiPass(
    bitmap: Bitmap,
    expectedNums: Set<Int>
): Map<Int, List<OcrHit>> {
    val all = mutableMapOf<Int, MutableList<OcrHit>>()

    fun addPass(result: Map<Int, List<OcrHit>>, offX: Int = 0, offY: Int = 0) {
        for ((n, hits) in result) {
            val list = all.getOrPut(n) { mutableListOf() }
            for (h in hits) {
                val b = h.box
                val shifted = Rect(
                    b.left + offX,
                    b.top + offY,
                    b.right + offX,
                    b.bottom + offY
                )
                list.add(OcrHit(shifted, h.conf))
            }
        }
    }

    // Pass 1: whole image
    addPass(runMlKitRawSingle(bitmap, expectedNums))

    // Pass 2: tiled OCR (improves tiny/packed labels)
    // 2x2 with overlap catches labels near tile boundaries.
    val w = bitmap.width
    val h = bitmap.height
    val overlapX = (w * 0.08f).toInt()
    val overlapY = (h * 0.08f).toInt()
    val halfW = w / 2
    val halfH = h / 2

    val tiles = listOf(
        Rect(0, 0, (halfW + overlapX).coerceAtMost(w), (halfH + overlapY).coerceAtMost(h)),
        Rect((halfW - overlapX).coerceAtLeast(0), 0, w, (halfH + overlapY).coerceAtMost(h)),
        Rect(0, (halfH - overlapY).coerceAtLeast(0), (halfW + overlapX).coerceAtMost(w), h),
        Rect((halfW - overlapX).coerceAtLeast(0), (halfH - overlapY).coerceAtLeast(0), w, h)
    )

    tiles.forEachIndexed { i, t ->
        kotlinx.coroutines.yield()
        val tw = (t.right - t.left).coerceAtLeast(1)
        val th = (t.bottom - t.top).coerceAtLeast(1)
        val tileBmp = Bitmap.createBitmap(bitmap, t.left, t.top, tw, th)
        val tileRes = runMlKitRawSingle(tileBmp, expectedNums)
        addPass(tileRes, t.left, t.top)
        Log.i(OCR_TAG, "OCR tile[$i] rect=(${t.left},${t.top},${t.right},${t.bottom}) matched=${tileRes.size}")
    }

    // De-dup + keep strongest 2 hits per number (double-sided support)
    val deduped = all.mapValues { (_, hits) ->
        hits.sortedByDescending { it.conf }.fold(mutableListOf<OcrHit>()) { acc, h ->
            val dup = acc.any { a ->
                kotlin.math.abs(a.box.left - h.box.left) < 10 &&
                    kotlin.math.abs(a.box.top - h.box.top) < 10 &&
                    kotlin.math.abs(a.box.right - h.box.right) < 10 &&
                    kotlin.math.abs(a.box.bottom - h.box.bottom) < 10
            }
            if (!dup) acc.add(h)
            acc
        }.take(2)
    }
    val acceptedBoxes = deduped.values.sumOf { it.size }
    Log.i(OCR_TAG, "OCR multipass merged: nums=${deduped.size}, boxes=$acceptedBoxes")
    return deduped
}

private suspend fun runMlKitRawSingle(
    bitmap: Bitmap,
    expectedNums: Set<Int>
): Map<Int, List<OcrHit>> =
    suspendCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val result = mutableMapOf<Int, MutableList<OcrHit>>()
                var totalElements = 0
                var nonNumeric = 0
                var lenReject = 0
                var expectedReject = 0
                var dupReject = 0
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            totalElements++
                            val txt = element.text.trim()
                            // Per report constraints: part labels are numeric and max 2 digits.
                            if (!txt.matches(Regex("^\\d{1,2}$"))) {
                                if (txt.any { it.isDigit() }) lenReject++ else nonNumeric++
                                continue
                            }
                            val n = txt.toIntOrNull() ?: continue
                            if (n !in expectedNums) {
                                expectedReject++
                                continue
                            }
                            val box = element.boundingBox ?: continue
                            val conf = element.confidence
                            val list = result.getOrPut(n) { mutableListOf() }
                            // avoid near-duplicate boxes for same number
                            val isDup = list.any { existing ->
                                val ex = existing.box
                                kotlin.math.abs(ex.left - box.left) < 8 &&
                                    kotlin.math.abs(ex.top - box.top) < 8 &&
                                    kotlin.math.abs(ex.right - box.right) < 8 &&
                                    kotlin.math.abs(ex.bottom - box.bottom) < 8
                            }
                            if (!isDup) {
                                list.add(OcrHit(box, conf))
                            } else {
                                dupReject++
                            }
                        }
                    }
                }
                // keep best 2 hits per number (double-sided support)
                val trimmed = result.mapValues { (_, hits) ->
                    hits.sortedByDescending { it.conf }.take(2)
                }
                val acceptedBoxes = trimmed.values.sumOf { it.size }
                Log.i(OCR_TAG, "OCR raw stats: totalElements=$totalElements nonNumeric=$nonNumeric lenReject=$lenReject expectedReject=$expectedReject dupReject=$dupReject acceptedNums=${trimmed.size} acceptedBoxes=$acceptedBoxes")
                cont.resume(trimmed)
            }
            .addOnFailureListener { cont.resumeWithException(it) }
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

private fun extractLargestEmbeddedImage(pdfFile: java.io.File, pageIndex: Int): Bitmap? {
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

private fun mergeOcrMaps(
    preferred: Map<Int, List<Rect>>,
    fallback: Map<Int, List<Rect>>
): Map<Int, List<Rect>> {
    if (preferred.isEmpty()) return fallback
    if (fallback.isEmpty()) return preferred
    val out = preferred.mapValues { it.value.toMutableList() }.toMutableMap()
    fallback.forEach { (num, rects) ->
        val list = out.getOrPut(num) { mutableListOf() }
        rects.forEach { r ->
            val dup = list.any { ex ->
                kotlin.math.abs(ex.left - r.left) < 10 &&
                    kotlin.math.abs(ex.top - r.top) < 10 &&
                    kotlin.math.abs(ex.right - r.right) < 10 &&
                    kotlin.math.abs(ex.bottom - r.bottom) < 10
            }
            if (!dup && list.size < 2) list.add(r)
        }
    }
    return out.mapValues { it.value.toList() }
}

private fun preprocessForOcrVariants(src: Bitmap): List<Bitmap> {
    // Keep some non-destructive variants; hard threshold alone misses faint/small labels.
    return listOf(
        src,
        toGrayscaleContrast(src, contrast = 1.20f),
        preprocessForOcr(src, thresholdBias = 0, erode = false),
        preprocessForOcr(src, thresholdBias = 10, erode = false),
        preprocessForOcr(src, thresholdBias = 0, erode = true),
        preprocessForOcr(src, thresholdBias = 10, erode = true)
    )
}

private fun toGrayscaleContrast(src: Bitmap, contrast: Float): Bitmap {
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val inPx = IntArray(w * h)
    val outPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)
    for (i in inPx.indices) {
        val c = inPx[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var y = ((r * 30 + g * 59 + b * 11) / 100)
        y = (((y - 128) * contrast) + 128).toInt().coerceIn(0, 255)
        outPx[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
    }
    out.setPixels(outPx, 0, w, 0, 0, w, h)
    return out
}

private fun preprocessForOcr(src: Bitmap, thresholdBias: Int, erode: Boolean): Bitmap {
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val inPx = IntArray(w * h)
    val gray = IntArray(w * h)
    val outPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)

    // 1) Grayscale + light contrast boost
    var sum = 0L
    for (i in inPx.indices) {
        val c = inPx[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var y = ((r * 30 + g * 59 + b * 11) / 100)
        y = (((y - 128) * 1.35f) + 128).toInt().coerceIn(0, 255)
        gray[i] = y
        sum += y
    }

    // 2) Global threshold (scanner-ish), slightly above mean to suppress color artifacts
    val thr = ((sum / gray.size).toInt() + 10 + thresholdBias).coerceIn(70, 220)
    for (i in gray.indices) {
        val v = if (gray[i] >= thr) 255 else 0
        outPx[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    if (erode) {
        val srcPx = outPx.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var allBlack = true
                for (yy in -1..1) {
                    for (xx in -1..1) {
                        if ((srcPx[(y + yy) * w + (x + xx)] and 0xFF) != 0) allBlack = false
                    }
                }
                outPx[y * w + x] = if (allBlack) {
                    (0xFF shl 24) or 0x000000
                } else {
                    (0xFF shl 24) or 0xFFFFFF
                }
            }
        }
    }

    out.setPixels(outPx, 0, w, 0, 0, w, h)
    return out
}

internal data class AnchoredZoomPan(val zoom: Float, val panX: Float, val panY: Float)

/**
 * Computes the next zoom/pan so the content point under [centroid] stays under the
 * fingers as the user pinches, instead of the zoom always appearing to originate from
 * the view's center (the default graphicsLayer transformOrigin).
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
private fun DiagramView(
    bitmap: Bitmap,
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
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            summary.forEach { (name, count) ->
                FilterChip(
                    selected = selectedPartType == name,
                    onClick = { onSelectPartType(name) },
                    label = { Text("$name ($count)") }
                )
            }
        }

        // Fixed width for the marker column — not resizable.
        val rotColWidth = 20.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
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

        LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
            itemsIndexed(items = parts, key = { _, part -> part.number }) { rowIndex, part ->
                val isBad = part.number in badParts
                val isDraft = part.number in draftBadParts
                val isSelected = part.number == selectedPartNumber
                val zebra = if (rowIndex % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPartClick(part) },
                            onLongClick = { onPartLongPress(part) }
                        )
                        .background(
                            when {
                                isBad -> KKCThemeColors.statusColors.bad.copy(alpha = 0.18f)
                                isDraft -> KKCThemeColors.statusColors.skip.copy(alpha = 0.18f)
                                isSelected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f)
                                else -> zebra
                            }
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
