package com.kkc.sheettracker.ui.components

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.viewer.DiagramView
import com.kkc.sheettracker.ui.viewer.ReferenceViewerData
import com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewer
import com.kkc.sheettracker.ui.viewer.extractLargestEmbeddedImage
import com.kkc.sheettracker.ui.viewer.rememberReferenceViewerData
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val REFERENCE_MODAL_SHEET_CACHE_PAGES = 4

data class ReferenceModalSnapshot(
    val isOpen: Boolean = false,
    val docType: ReferenceDocType = ReferenceDocType.PLANS_ELEVATIONS,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1,
    val sheetPage: Int = 1,
    val modalX: Float = 24f,
    val modalY: Float = 24f,
    val modalWidth: Float = 360f,
    val modalHeight: Float = 480f
) {
    fun pageForActiveDoc(): Int = when (docType) {
        ReferenceDocType.ASSEMBLY -> assemblyPage
        ReferenceDocType.SHEET -> sheetPage
        else -> plansPage
    }

    /**
     * Switches doc type. When switching to [ReferenceDocType.SHEET] with [syncPage] provided,
     * snaps the Sheet tab's page to it (the main viewer's current Sheet page) — a one-time sync
     * on tab switch, not continuous following. Ignored for any other target doc type.
     */
    fun withDocType(next: ReferenceDocType, syncPage: Int? = null): ReferenceModalSnapshot {
        val base = copy(docType = next)
        return if (next == ReferenceDocType.SHEET && syncPage != null) {
            base.copy(sheetPage = syncPage.coerceAtLeast(1))
        } else {
            base
        }
    }

    fun withPage(page: Int): ReferenceModalSnapshot {
        val safe = page.coerceAtLeast(1)
        return when (docType) {
            ReferenceDocType.ASSEMBLY -> copy(assemblyPage = safe)
            ReferenceDocType.SHEET -> copy(sheetPage = safe)
            else -> copy(plansPage = safe)
        }
    }
}

/** First page mapped to [cabinet] in the active doc's page space, or null if none. */
fun resolveJumpPage(cabinetToPages: Map<String, List<Int>>, cabinet: Int): Int? =
    cabinetToPages[cabinet.toString()]?.firstOrNull()

/**
 * Resolves the doc type to show when opening the popup: keep the user's persisted [current] doc
 * when its reference is still available, otherwise fall back to [fallback] (or [current] if none).
 */
fun coerceDocTypeForOpen(
    current: ReferenceDocType,
    hasPlans: Boolean,
    hasAssembly: Boolean,
    fallback: ReferenceDocType?
): ReferenceDocType {
    val available = current == ReferenceDocType.SHEET ||
                    (current == ReferenceDocType.PLANS_ELEVATIONS && hasPlans) ||
                    (current == ReferenceDocType.ASSEMBLY && hasAssembly)
    return if (available) current else (fallback ?: current)
}

class ReferenceModalOverlayState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    var noRefNoteToken by mutableStateOf(0)
        private set

    var noRefNoteVisible by mutableStateOf(false)
        private set

    fun toggleOpen(hasPlans: Boolean, hasAssembly: Boolean, fallbackDoc: ReferenceDocType?) =
        setOpen(!snapshot.isOpen, hasPlans, hasAssembly, fallbackDoc)

    fun setOpen(
        open: Boolean,
        hasPlans: Boolean = false,
        hasAssembly: Boolean = false,
        fallbackDoc: ReferenceDocType? = null
    ) {
        var next = snapshot
        if (open) next = next.withDocType(coerceDocTypeForOpen(next.docType, hasPlans, hasAssembly, fallbackDoc))
        next = next.copy(isOpen = open)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun setDocType(docType: ReferenceDocType, syncPage: Int? = null) {
        clearNoRefNote()
        val next = snapshot.withDocType(docType, syncPage)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun setPage(page: Int) {
        clearNoRefNote()
        val next = snapshot.withPage(page)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun showNoRefNote() {
        noRefNoteVisible = true
        noRefNoteToken += 1
    }

    /** Transient (not persisted) — hides the "no reference" note immediately. */
    fun clearNoRefNote() { if (noRefNoteVisible) noRefNoteVisible = false }

    fun updateModalBounds(x: Float, y: Float, width: Float, height: Float, persistNow: Boolean) {
        val next = snapshot.copy(modalX = x, modalY = y, modalWidth = width, modalHeight = height)
        if (next == snapshot) return
        snapshot = next
        if (persistNow) persist()
    }

    fun clampToViewport(vw: Float, vh: Float, margin: Float, minW: Float, minH: Float) {
        val maxW = (vw - margin * 2f).coerceAtLeast(minW)
        val maxH = (vh - margin * 2f).coerceAtLeast(minH)
        val w = snapshot.modalWidth.coerceIn(minW, maxW)
        val h = snapshot.modalHeight.coerceIn(minH, maxH)
        val x = snapshot.modalX.coerceIn(margin, (vw - w - margin).coerceAtLeast(margin))
        val y = snapshot.modalY.coerceIn(margin, (vh - h - margin).coerceAtLeast(margin))
        val next = snapshot.copy(modalX = x, modalY = y, modalWidth = w, modalHeight = h)
        if (next != snapshot) { snapshot = next; persist() }
    }

    private fun persist() {
        prefs.edit()
            .putBoolean(KEY_OPEN, snapshot.isOpen)
            .putString(KEY_DOC, snapshot.docType.name)
            .putInt(KEY_PLANS_PAGE, snapshot.plansPage)
            .putInt(KEY_ASM_PAGE, snapshot.assemblyPage)
            .putInt(KEY_SHEET_PAGE, snapshot.sheetPage)
            .putFloat(KEY_X, snapshot.modalX)
            .putFloat(KEY_Y, snapshot.modalY)
            .putFloat(KEY_W, snapshot.modalWidth)
            .putFloat(KEY_H, snapshot.modalHeight)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_tracker"
        private const val KEY_OPEN = "refmodal_open"
        private const val KEY_DOC = "refmodal_doc"
        private const val KEY_PLANS_PAGE = "refmodal_plans_page"
        private const val KEY_ASM_PAGE = "refmodal_asm_page"
        private const val KEY_SHEET_PAGE = "refmodal_sheet_page"
        private const val KEY_X = "refmodal_x_dp"
        private const val KEY_Y = "refmodal_y_dp"
        private const val KEY_W = "refmodal_w_dp"
        private const val KEY_H = "refmodal_h_dp"

        fun create(context: Context): ReferenceModalOverlayState =
            ReferenceModalOverlayState(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        private fun load(prefs: SharedPreferences): ReferenceModalSnapshot {
            val doc = prefs.getString(KEY_DOC, null)
                ?.let { runCatching { ReferenceDocType.valueOf(it) }.getOrNull() }
                ?: ReferenceDocType.PLANS_ELEVATIONS
            return ReferenceModalSnapshot(
                isOpen = prefs.getBoolean(KEY_OPEN, false),
                docType = doc,
                plansPage = prefs.getInt(KEY_PLANS_PAGE, 1),
                assemblyPage = prefs.getInt(KEY_ASM_PAGE, 1),
                sheetPage = prefs.getInt(KEY_SHEET_PAGE, 1),
                modalX = prefs.getFloat(KEY_X, 24f),
                modalY = prefs.getFloat(KEY_Y, 24f),
                modalWidth = prefs.getFloat(KEY_W, 360f),
                modalHeight = prefs.getFloat(KEY_H, 480f)
            )
        }
    }
}

@Composable
fun rememberReferenceModalOverlayState(): ReferenceModalOverlayState {
    val context = LocalContext.current
    return remember { ReferenceModalOverlayState.create(context) }
}

@Composable
fun ReferenceModalHost(
    state: ReferenceModalOverlayState,
    jobRepository: JobRepository,
    jobFolderName: String,
    refreshGeneration: Long,
    isDarkTheme: Boolean,
    hasPlans: Boolean,
    hasAssembly: Boolean,
    selectedCabinet: Int?,
    sheetPdfFilename: String,
    sheetPdfFile: File?,
    currentSheetPage: Int,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    if (!snapshot.isOpen) return

    BackHandler(enabled = snapshot.isOpen) {
        state.setOpen(false)
    }

    val referenceData = if (snapshot.docType == ReferenceDocType.SHEET) {
        // The Sheet tab isn't a reference document looked up by JobRepository — it's the CNC PDF
        // already open behind the popup. Build the viewer data directly from the caller-supplied
        // file/filename; no virtual mapping, cabinet index, or JobRepository lookup applies to it.
        ReferenceViewerData(
            defaultPdfFilename = sheetPdfFilename,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPlanViewLabels = emptyMap(),
            warningMessage = null
        )
    } else {
        rememberReferenceViewerData(
            jobRepository = jobRepository,
            jobFolderName = jobFolderName,
            docType = snapshot.docType,
            refreshGeneration = refreshGeneration,
            isDarkTheme = isDarkTheme
        )
    }

    // Part-tap jump: only exists while the modal is open (the Host early-returns when closed),
    // so the reference-doc I/O in rememberReferenceViewerData is not paid for on every screen
    // composition. `handledCabinet` is seeded with the cabinet selected at open time, so reopening
    // the modal does NOT auto-jump to the already-selected part — the jump fires only on a FRESH
    // tap while open. Toggling docType must not auto-jump (setDocType restores that doc's last
    // page), so docType is intentionally not a key here.
    var handledCabinet by remember { mutableStateOf(selectedCabinet) }
    LaunchedEffect(selectedCabinet) {
        if (selectedCabinet == handledCabinet) return@LaunchedEffect
        handledCabinet = selectedCabinet
        // Nothing to jump to on the Sheet tab — it IS the current sheet.
        if (snapshot.docType == ReferenceDocType.SHEET) return@LaunchedEffect
        val cabinet = selectedCabinet ?: return@LaunchedEffect
        val target = resolveJumpPage(referenceData.navigatorCabinetToPages, cabinet)
        if (target != null) state.setPage(target) else state.showNoRefNote()
    }

    // Sheet tab bitmap resolution: mirrors the main viewer's diagram crop (extractLargestEmbeddedImage)
    // with a full-page render fallback for pages with no extractable embedded image. Keyed on
    // sheetPdfFile so switching sheets/jobs resets state instead of showing a stale bitmap; the page
    // count effect is separate from the per-page bitmap effect since it only needs to run once per file.
    var sheetBitmap by remember(sheetPdfFile) { mutableStateOf<Bitmap?>(null) }
    var sheetTotalPages by remember(sheetPdfFile) { mutableStateOf(0) }
    // Page-keyed bitmap cache scoped to the current sheetPdfFile and refresh generation:
    // revisiting a recently-resolved page is instant, and switching sheets/jobs starts with a
    // fresh bounded cache so bitmaps never leak across different file identities.
    val sheetBitmapCache = remember(sheetPdfFile, refreshGeneration) {
        LruCache<Int, Bitmap>(REFERENCE_MODAL_SHEET_CACHE_PAGES)
    }
    DisposableEffect(sheetBitmapCache) {
        onDispose { sheetBitmapCache.evictAll() }
    }

    LaunchedEffect(sheetPdfFile, refreshGeneration) {
        val file = sheetPdfFile ?: return@LaunchedEffect
        sheetTotalPages = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }

    LaunchedEffect(snapshot.sheetPage, sheetPdfFile, refreshGeneration) {
        // Clear immediately so the loading indicator shows on every page change instead of the
        // previous page's stale bitmap lingering until the new extraction/render finishes.
        sheetBitmap = null
        val file = sheetPdfFile ?: return@LaunchedEffect
        val pageIndex = snapshot.sheetPage - 1
        val cached = sheetBitmapCache.get(pageIndex)
        if (cached != null) {
            sheetBitmap = cached
            return@LaunchedEffect
        }
        // Mirrors SheetViewerScreen's render effect: debounce so a fast page-turn cancels this
        // effect before the expensive extraction/render work even starts.
        kotlinx.coroutines.delay(150L)
        val resolved = withContext(Dispatchers.IO) {
            extractLargestEmbeddedImage(file, pageIndex) ?: renderSheetPageFallback(file, pageIndex)
        }
        if (resolved != null) {
            sheetBitmapCache.put(pageIndex, resolved)
        }
        sheetBitmap = resolved
    }

    // "No reference for this cabinet" transient note — visibility is owned by the state
    // (cleared immediately by setDocType/setPage). This effect only runs the auto-hide timer:
    // each showNoRefNote() bumps noRefNoteToken, restarting the 2500ms countdown, and the
    // token-equality guard prevents a stale timer from hiding a freshly-shown note.
    LaunchedEffect(state.noRefNoteToken) {
        if (state.noRefNoteToken > 0 && state.noRefNoteVisible) {
            val tokenAtStart = state.noRefNoteToken
            kotlinx.coroutines.delay(2500)
            if (state.noRefNoteToken == tokenAtStart) state.clearNoRefNote()
        }
    }
    val showNote = state.noRefNoteVisible

    val margin = 12f
    val minWidth = 300f
    val minHeight = 360f
    val density = LocalDensity.current.density

    // zIndex within this screen's overlay Box; calculator overlay is composed separately in NavGraph
    Box(modifier = modifier.fillMaxSize().zIndex(9f)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(4.dp)
        ) {
            val vw = maxWidth.value
            val vh = maxHeight.value
            LaunchedEffect(vw, vh, snapshot.modalX, snapshot.modalY, snapshot.modalWidth, snapshot.modalHeight) {
                state.clampToViewport(vw, vh, margin, minWidth, minHeight)
            }
            val maxWDp = (vw - margin * 2f).coerceAtLeast(minWidth)
            val maxHDp = (vh - margin * 2f).coerceAtLeast(minHeight)
            val widthDp = snapshot.modalWidth.coerceIn(minWidth, maxWDp)
            val heightDp = snapshot.modalHeight.coerceIn(minHeight, maxHDp)

            val shape = RoundedCornerShape(15.dp)
            val frosted = LocalKKCThemeTokens.current.frosted
            val panelModifier = if (hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeDefaults.style(
                        backgroundColor = MaterialTheme.colorScheme.surface.copy(
                            alpha = frosted.backgroundAlpha.coerceIn(0.72f, 0.95f)
                        ),
                        blurRadius = frosted.blurDp.coerceAtLeast(1f).dp
                    )
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surface)
            }

            Box(
                modifier = Modifier
                    .offset(snapshot.modalX.dp, snapshot.modalY.dp)
                    .width(widthDp.dp)
                    .height(heightDp.dp)
                    .shadow(10.dp, shape, clip = false)
                    .clip(shape)
                    .then(panelModifier)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header: drag handle + doc toggle + close
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f))
                            .pointerInput(vw, vh, widthDp, heightDp) {
                                var liveX = 0f
                                var liveY = 0f
                                detectDragGestures(
                                    onDragStart = { liveX = state.snapshot.modalX; liveY = state.snapshot.modalY },
                                    onDragEnd = {
                                        val s = state.snapshot
                                        state.updateModalBounds(s.modalX, s.modalY, s.modalWidth, s.modalHeight, true)
                                    }
                                ) { change, drag ->
                                    change.consume()
                                    liveX = (liveX + drag.x / density)
                                        .coerceIn(margin, (vw - widthDp - margin).coerceAtLeast(margin))
                                    liveY = (liveY + drag.y / density)
                                        .coerceIn(margin, (vh - heightDp - margin).coerceAtLeast(margin))
                                    state.updateModalBounds(liveX, liveY, state.snapshot.modalWidth, state.snapshot.modalHeight, false)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.SHEET,
                                onClick = { state.setDocType(ReferenceDocType.SHEET, syncPage = currentSheetPage) },
                                enabled = true, // Sheet is the CNC PDF already open behind the popup — always available.
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                label = { Text("Sheet", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.PLANS_ELEVATIONS,
                                onClick = { state.setDocType(ReferenceDocType.PLANS_ELEVATIONS) },
                                enabled = hasPlans,
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                label = { Text("Plans & Elev.", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.ASSEMBLY,
                                onClick = { state.setDocType(ReferenceDocType.ASSEMBLY) },
                                enabled = hasAssembly,
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                label = { Text("Assembly", maxLines = 1) }
                            )
                        }
                        IconButton(onClick = { state.setOpen(false) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close reference popup")
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (snapshot.docType == ReferenceDocType.SHEET) {
                            val bmp = sheetBitmap
                            if (bmp != null) {
                                DiagramView(
                                    bitmap = bmp,
                                    parts = emptyList(),
                                    selectedPartNumber = null,
                                    diagramBboxes = emptyMap(),
                                    resetZoomTrigger = snapshot.sheetPage,
                                    onTapPart = {},
                                    onLongPressPart = {},
                                    modifier = Modifier.fillMaxSize(),
                                    onTapEmpty = null
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            if (sheetTotalPages > 1) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                    tonalElevation = 3.dp,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { state.setPage(snapshot.sheetPage - 1) },
                                            enabled = snapshot.sheetPage > 1,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous", modifier = Modifier.size(20.dp))
                                        }
                                        Text("${snapshot.sheetPage}/$sheetTotalPages", style = MaterialTheme.typography.labelMedium)
                                        IconButton(
                                            onClick = { state.setPage(snapshot.sheetPage + 1) },
                                            enabled = snapshot.sheetPage < sheetTotalPages,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            UnifiedReferenceViewer(
                                modifier = Modifier.fillMaxSize(),
                                displayPage = snapshot.pageForActiveDoc(),
                                onDisplayPageChange = { state.setPage(it) },
                                defaultPdfFilename = referenceData.defaultPdfFilename,
                                pdfFileForFilename = { filename ->
                                    jobRepository.getJobRootPdfFile(
                                        jobFolderName = jobFolderName,
                                        pdfFilename = filename,
                                        preferDarkMode = isDarkTheme
                                    )
                                },
                                fileIdentitySeed = refreshGeneration,
                                preferDarkMode = isDarkTheme,
                                virtualMapping = referenceData.virtualMapping,
                                navigatorCabinetToPages = referenceData.navigatorCabinetToPages,
                                navigatorPlanViewLabels = referenceData.navigatorPlanViewLabels,
                                navigatorWarningMessage = referenceData.warningMessage,
                                missingText = "Reference PDF not found.",
                                unreadableText = "Unable to read PDF pages.",
                                showHeaderRow = false,
                                showNavigationButtons = true,
                                compactArrows = true
                            )
                        }
                        if (showNote) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    "No reference sheet for this cabinet in ${if (snapshot.docType == ReferenceDocType.ASSEMBLY) "Assembly" else "Plans"}.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Resize corner (mirrors CalculatorPanel's ◢ handle): drag to change width/height.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .pointerInput(vw, vh) {
                            var liveW = 0f
                            var liveH = 0f
                            detectDragGestures(
                                onDragStart = { val s = state.snapshot; liveW = s.modalWidth; liveH = s.modalHeight },
                                onDragEnd = {
                                    val s = state.snapshot
                                    state.updateModalBounds(s.modalX, s.modalY, s.modalWidth, s.modalHeight, true)
                                }
                            ) { change, drag ->
                                change.consume()
                                liveW = (liveW + drag.x / density).coerceIn(minWidth, maxWDp)
                                liveH = (liveH + drag.y / density).coerceIn(minHeight, maxHDp)
                                state.updateModalBounds(state.snapshot.modalX, state.snapshot.modalY, liveW, liveH, false)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "◢",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Full-page render fallback for the popup's Sheet tab, used only when [extractLargestEmbeddedImage]
 * finds no embedded diagram image on the page. Standalone PdfRenderer render — no caching.
 */
private fun renderSheetPageFallback(pdfFile: File, pageIndex: Int): Bitmap? {
    if (!pdfFile.exists()) return null
    return try {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val scale = 2
                    val bmp = Bitmap.createBitmap(
                        page.width * scale,
                        page.height * scale,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }
}
