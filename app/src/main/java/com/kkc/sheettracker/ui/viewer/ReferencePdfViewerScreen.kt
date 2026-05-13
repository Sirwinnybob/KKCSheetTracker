package com.kkc.sheettracker.ui.viewer

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.ReferenceDocType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencePdfViewerScreen(
    jobRepository: JobRepository,
    jobFolderName: String,
    docType: ReferenceDocType,
    startPage: Int,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val sheetIndex = remember(jobFolderName) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    val documentIndex = remember(sheetIndex, docType) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
        }
    }

    val assemblyVirtualRawMap = remember(sheetIndex) {
        sheetIndex?.documents?.assembly?.virtualCombined?.virtualPageToSource
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
    val assemblyVirtualTotalPages = remember(sheetIndex) {
        (sheetIndex?.documents?.assembly?.virtualCombined?.totalVirtualPages ?: 0).coerceAtLeast(0)
    }
    val assemblyVirtualSanitized = remember(
        assemblyVirtualTotalPages,
        assemblyVirtualRawMap,
        documentIndex,
        sheetIndex
    ) {
        sanitizeVirtualAssemblyData(
            totalVirtualPages = assemblyVirtualTotalPages,
            defaultPdfFilename = documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
                ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY).orEmpty(),
            sourceByDisplayPage = assemblyVirtualRawMap,
            cabinetToPages = sheetIndex?.documents?.assembly?.virtualCombined?.cabinetToPages.orEmpty()
        )
    }
    val virtualMapping = remember(docType, assemblyVirtualSanitized) {
        if (docType != ReferenceDocType.ASSEMBLY || assemblyVirtualTotalPages <= 0) {
            null
        } else {
            assemblyVirtualSanitized.mapping
        }
    }
    val navigatorCabinetToPages = remember(docType, documentIndex, assemblyVirtualSanitized, virtualMapping) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> {
                if (virtualMapping != null) {
                    assemblyVirtualSanitized.cabinetToPages
                } else {
                    documentIndex?.cabinetToPages.orEmpty()
                }
            }
            ReferenceDocType.PLANS_ELEVATIONS -> documentIndex?.cabinetToPages.orEmpty()
            ReferenceDocType.DELIVERY_SHEETS -> emptyMap()
        }
    }
    val navigatorPlanViewLabels = remember(docType, documentIndex) {
        if (docType != ReferenceDocType.PLANS_ELEVATIONS) {
            emptyMap()
        } else {
            val pageToRoom = documentIndex?.pageDetails
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

    val resumeKey = remember(jobFolderName, docType) { "reference_resume_v1_${jobFolderName}_${docType.name}" }
    var currentPage by remember(jobFolderName, docType, startPage) {
        mutableIntStateOf(prefs.getInt(resumeKey, startPage).coerceAtLeast(1))
    }
    LaunchedEffect(currentPage, resumeKey) {
        prefs.edit().putInt(resumeKey, currentPage).apply()
    }

    val defaultPdfFilename = remember(documentIndex, docType, jobFolderName) {
        documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, docType)
            ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (docType) {
                            ReferenceDocType.ASSEMBLY -> "Assembly Sheets"
                            ReferenceDocType.PLANS_ELEVATIONS -> "Plans & Elevations"
                            ReferenceDocType.DELIVERY_SHEETS -> "Cover Sheet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                )
            )
        }
    ) { padding ->
        UnifiedReferenceViewer(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            displayPage = currentPage,
            onDisplayPageChange = { currentPage = it },
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
            navigatorWarningMessage = if (docType == ReferenceDocType.ASSEMBLY) {
                assemblyVirtualSanitized.warningMessage
            } else {
                null
            },
            missingText = "Reference PDF not found.",
            unreadableText = "Unable to read PDF pages."
        )
    }
}

