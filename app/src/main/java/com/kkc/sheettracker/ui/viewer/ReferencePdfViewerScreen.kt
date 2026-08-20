package com.kkc.sheettracker.ui.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.PdfMarkupStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.IdlePhase
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.components.ImmersiveSystemBars
import com.kkc.sheettracker.ui.components.LocalIdlePhase
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.markup.rememberPdfMarkupToolState
import dev.chrisbanes.haze.HazeState
import java.io.File

internal data class FullscreenTapResult(
    val showUi: Boolean,
    val wakePending: Boolean
)

internal fun applyFullscreenSingleTap(showUi: Boolean, wakePending: Boolean): FullscreenTapResult =
    if (wakePending) {
        FullscreenTapResult(showUi = true, wakePending = false)
    } else {
        FullscreenTapResult(showUi = !showUi, wakePending = false)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencePdfViewerScreen(
    jobRepository: JobRepository,
    jobFolderName: String,
    docType: ReferenceDocType,
    startPage: Int,
    refreshGeneration: Long = 0L,
    continuousScrollDefault: Boolean = false,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onUiVisibilityChanged: (Boolean) -> Unit = {},
    overridePdfMarkupStore: PdfMarkupStore? = null,
    pdfMarkupReadOnly: Boolean = false
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val referenceData = rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = docType,
        refreshGeneration = refreshGeneration,
        isDarkTheme = isDarkTheme
    )

    val resumeKey = remember(jobFolderName, docType) { "reference_resume_v1_${jobFolderName}_${docType.name}" }
    var currentPage by remember(jobFolderName, docType, startPage) {
        mutableIntStateOf(prefs.getInt(resumeKey, startPage).coerceAtLeast(1))
    }
    LaunchedEffect(currentPage, resumeKey) {
        prefs.edit().putInt(resumeKey, currentPage).apply()
    }

    // True fullscreen: hide system bars for the lifetime of this screen.
    ImmersiveSystemBars()
    // Tap-to-show/hide overlay UI.
    var showUi by rememberSaveable { mutableStateOf(true) }
    val idlePhase by LocalIdlePhase.current.collectAsState()
    var fullscreenWakePending by remember { mutableStateOf(false) }
    LaunchedEffect(idlePhase) {
        if (idlePhase != IdlePhase.ACTIVE) {
            fullscreenWakePending = true
        }
    }
    var markupEnabled by rememberSaveable { mutableStateOf(false) }
    var continuousScrollEnabled by rememberSaveable(jobFolderName, docType) { mutableStateOf(continuousScrollDefault) }
    var tocRequestToken by remember(jobFolderName, docType) { mutableIntStateOf(0) }
    val markupToolState = rememberPdfMarkupToolState()
    // Lets the continuous-scroll scrollbar's expanded panel blur the PDF content behind it —
    // same frosted pattern used elsewhere in the app, not a plain opaque panel.
    val hazeState = remember { HazeState() }
    // Restore bottom nav visibility when navigating back.
    DisposableEffect(Unit) { onDispose { onUiVisibilityChanged(true) } }
    val pdfMarkupStore = overridePdfMarkupStore ?: remember(pdfMarkupReadOnly) {
        val trackerPrefs = context.getSharedPreferences("kkc_tracker", android.content.Context.MODE_PRIVATE)
        val basePath = trackerPrefs.getString("base_path", null)
        val tabletId = trackerPrefs.getString("tablet_id", null)
        if (basePath.isNullOrBlank() || tabletId.isNullOrBlank()) {
            null
        } else {
            PdfMarkupStore(File(basePath), tabletId, readOnly = pdfMarkupReadOnly)
        }
    }

    val topBarAlpha by animateFloatAsState(if (showUi) 1f else 0f, tween(220), label = "topBarAlpha")

    Scaffold(
        topBar = {
            // graphicsLayer alpha — no layout shift, no PDF re-render during animation.
            TopAppBar(
                modifier = Modifier
                    .graphicsLayer { alpha = topBarAlpha }
                    .headerBackground(),
                title = {
                    Text(
                        when (docType) {
                            ReferenceDocType.ASSEMBLY -> "Assembly Sheets"
                            ReferenceDocType.PLANS_ELEVATIONS -> "Plans & Elevations"
                            ReferenceDocType.DELIVERY_SHEETS -> "Cover Sheet"
                            ReferenceDocType.SHEET -> "Sheet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { tocRequestToken += 1 }) {
                        Icon(Icons.Default.UnfoldMore, contentDescription = "Sheet list")
                    }
                    IconButton(onClick = { continuousScrollEnabled = !continuousScrollEnabled }) {
                        Icon(
                            if (continuousScrollEnabled) Icons.Default.ViewDay else Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = if (continuousScrollEnabled) "Switch to single page" else "Switch to continuous scroll",
                            tint = if (continuousScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars
            )
        }
    ) { padding ->
        UnifiedReferenceViewer(
            // NOT .hazeSource(hazeState) here — this screen's only hazeEffect consumer is the
            // continuous-scroll scrollbar's own panel, which is a DESCENDANT of this modifier's
            // node. A hazeSource wrapping its own hazeEffect consumer is self-referential and
            // silently produced no visible blur (confirmed on-device: nav bar/calculator/clock-in
            // modal elsewhere in the app render blur correctly, so Haze itself works fine — this
            // screen just had two overlapping hazeSource registrations under the same HazeState,
            // one of them self-referential). The scrollbar gets its own clean, non-self-referential
            // source directly from ContinuousReferencePdfPane — see UnifiedReferenceViewer.
            modifier = Modifier
                .fillMaxSize()
                .then(if (continuousScrollEnabled) Modifier else Modifier.padding(padding)),
            displayPage = currentPage,
            onDisplayPageChange = { currentPage = it },
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
            pdfMarkupStore = pdfMarkupStore,
            pdfMarkupJobFolderName = jobFolderName,
            markupEnabled = markupEnabled,
            onToggleMarkupEnabled = { markupEnabled = !markupEnabled },
            markupToolState = markupToolState,
            continuousScrollEnabled = continuousScrollEnabled,
            continuousChromeTopPadding = padding.calculateTopPadding(),
            isSplitPaneActive = false,
            hazeState = hazeState,
            tocRequestToken = tocRequestToken,
            onSingleTap = {
                val tapResult = applyFullscreenSingleTap(showUi, fullscreenWakePending)
                showUi = tapResult.showUi
                fullscreenWakePending = tapResult.wakePending
                onUiVisibilityChanged(showUi)
            }
        )
    }
}

