package com.kkc.sheettracker.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.hazeEffect

data class ReferenceModalSnapshot(
    val isOpen: Boolean = false,
    val docType: ReferenceDocType = ReferenceDocType.PLANS_ELEVATIONS,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1,
    val modalX: Float = 24f,
    val modalY: Float = 24f,
    val modalWidth: Float = 360f,
    val modalHeight: Float = 480f
) {
    fun pageForActiveDoc(): Int =
        if (docType == ReferenceDocType.ASSEMBLY) assemblyPage else plansPage

    fun withDocType(next: ReferenceDocType): ReferenceModalSnapshot = copy(docType = next)

    fun withPage(page: Int): ReferenceModalSnapshot {
        val safe = page.coerceAtLeast(1)
        return if (docType == ReferenceDocType.ASSEMBLY) copy(assemblyPage = safe) else copy(plansPage = safe)
    }
}

/** First page mapped to [cabinet] in the active doc's page space, or null if none. */
fun resolveJumpPage(cabinetToPages: Map<String, List<Int>>, cabinet: Int): Int? =
    cabinetToPages[cabinet.toString()]?.firstOrNull()

class ReferenceModalOverlayState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    var noRefNoteToken by mutableStateOf(0)
        private set

    fun toggleOpen(defaultDocType: ReferenceDocType?) = setOpen(!snapshot.isOpen, defaultDocType)

    fun setOpen(open: Boolean, defaultDocType: ReferenceDocType?) {
        var next = snapshot
        if (open && defaultDocType != null) next = next.withDocType(defaultDocType)
        next = next.copy(isOpen = open)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun setDocType(docType: ReferenceDocType) {
        if (snapshot.docType == docType) return
        snapshot = snapshot.withDocType(docType)
        persist()
    }

    fun setPage(page: Int) {
        val next = snapshot.withPage(page)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun showNoRefNote() { noRefNoteToken += 1 }

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
    jobRepository: com.kkc.sheettracker.data.JobRepository,
    jobFolderName: String,
    refreshGeneration: Long,
    isDarkTheme: Boolean,
    hasPlans: Boolean,
    hasAssembly: Boolean,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    if (!snapshot.isOpen) return

    androidx.activity.compose.BackHandler(enabled = snapshot.isOpen) {
        state.setOpen(false, null)
    }

    val referenceData = com.kkc.sheettracker.ui.viewer.rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = snapshot.docType,
        refreshGeneration = refreshGeneration,
        isDarkTheme = isDarkTheme
    )

    // "No reference for this cabinet" transient note — cleared on doc change or next successful jump.
    var noteVisibleForToken by remember { mutableStateOf(-1) }
    LaunchedEffect(state.noRefNoteToken) {
        if (state.noRefNoteToken > 0) {
            noteVisibleForToken = state.noRefNoteToken
            kotlinx.coroutines.delay(2500)
            if (noteVisibleForToken == state.noRefNoteToken) noteVisibleForToken = -1
        }
    }
    val showNote = noteVisibleForToken == state.noRefNoteToken && state.noRefNoteToken > 0

    val margin = 12f
    val minWidth = 300f
    val minHeight = 360f
    val density = LocalDensity.current.density

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
                                selected = snapshot.docType == ReferenceDocType.PLANS_ELEVATIONS,
                                onClick = { state.setDocType(ReferenceDocType.PLANS_ELEVATIONS) },
                                enabled = hasPlans,
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text("Plans & Elev.", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.ASSEMBLY,
                                onClick = { state.setDocType(ReferenceDocType.ASSEMBLY) },
                                enabled = hasAssembly,
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text("Assembly", maxLines = 1) }
                            )
                        }
                        IconButton(onClick = { state.setOpen(false, null) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close reference popup")
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewer(
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
            }
        }
    }
}
