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
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.components.ImmersiveSystemBars
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.markup.rememberPdfMarkupToolState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencePdfViewerScreen(
    jobRepository: JobRepository,
    jobFolderName: String,
    docType: ReferenceDocType,
    startPage: Int,
    refreshGeneration: Long = 0L,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onUiVisibilityChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val trackerPrefs = remember { context.getSharedPreferences("kkc_tracker", android.content.Context.MODE_PRIVATE) }
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
    var markupEnabled by rememberSaveable { mutableStateOf(false) }
    val markupToolState = rememberPdfMarkupToolState()
    // Restore bottom nav visibility when navigating back.
    DisposableEffect(Unit) { onDispose { onUiVisibilityChanged(true) } }
    val pdfMarkupStore = remember {
        val basePath = trackerPrefs.getString("base_path", null)
        val tabletId = trackerPrefs.getString("tablet_id", null)
        if (basePath.isNullOrBlank() || tabletId.isNullOrBlank()) {
            null
        } else {
            PdfMarkupStore(File(basePath), tabletId)
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
                
                windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars
            )
        }
    ) { padding ->
        UnifiedReferenceViewer(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
            onSingleTap = {
                showUi = !showUi
                onUiVisibilityChanged(showUi)
            }
        )
    }
}

