package com.kkc.sheettracker.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.components.ReferencePdfPane

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
    val sheetIndex = remember(jobFolderName) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    val assemblyVirtualMap = remember(sheetIndex) {
        sheetIndex?.documents?.assembly?.virtualCombined?.virtualPageToSource.orEmpty()
    }
    val assemblyVirtualTotalPages = remember(sheetIndex) {
        (sheetIndex?.documents?.assembly?.virtualCombined?.totalVirtualPages ?: 0).coerceAtLeast(0)
    }
    val hasVirtualAssembly = docType == ReferenceDocType.ASSEMBLY && assemblyVirtualTotalPages > 0
    val documentIndex = remember(sheetIndex, docType) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
        }
    }

    fun resolveAssemblyFilename(virtualPage: Int): String {
        val mapped = assemblyVirtualMap[virtualPage.toString()]?.pdfFilename?.takeIf { it.isNotBlank() }
        val fallback = documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY)
        return mapped ?: fallback.orEmpty()
    }

    fun resolveAssemblySourcePage(virtualPage: Int): Int {
        return assemblyVirtualMap[virtualPage.toString()]?.page?.takeIf { it > 0 } ?: virtualPage
    }

    fun resolveVirtualPage(sourceFilename: String, sourcePage: Int): Int? {
        if (sourceFilename.isBlank() || sourcePage <= 0) return null
        val match = assemblyVirtualMap.entries.firstOrNull { (_, source) ->
            source.pdfFilename.equals(sourceFilename, ignoreCase = true) && source.page == sourcePage
        } ?: return null
        return match.key.toIntOrNull()
    }

    var currentPage by remember(jobFolderName, docType, startPage) { mutableIntStateOf(startPage.coerceAtLeast(1)) }
    var showCombinedSheetNavigator by remember { mutableStateOf(false) }

    val virtualPage = if (hasVirtualAssembly) {
        currentPage.coerceIn(1, assemblyVirtualTotalPages.coerceAtLeast(1))
    } else {
        currentPage
    }
    val pdfFilename = remember(documentIndex, docType, jobFolderName, virtualPage, hasVirtualAssembly) {
        if (hasVirtualAssembly) {
            resolveAssemblyFilename(virtualPage)
        } else {
            documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
                ?: jobRepository.findReferencePdfFilename(jobFolderName, docType)
                ?: ""
        }
    }
    val pdfFile = remember(jobFolderName, pdfFilename, isDarkTheme) {
        if (pdfFilename.isBlank()) null else jobRepository.getJobRootPdfFile(jobFolderName, pdfFilename, preferDarkMode = isDarkTheme)
    }
    val sourcePage = if (hasVirtualAssembly) resolveAssemblySourcePage(virtualPage) else currentPage

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
        ReferencePdfPane(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            pdfFile = pdfFile,
            currentPage = sourcePage,
            onCurrentPageChange = { nextSourcePage ->
                if (hasVirtualAssembly) {
                    val mapped = resolveVirtualPage(pdfFilename, nextSourcePage)
                    if (mapped != null) {
                        currentPage = mapped
                    }
                } else {
                    currentPage = nextSourcePage
                }
            },
            missingText = "Reference PDF not found.",
            unreadableText = "Unable to read PDF pages.",
            displayPageOverride = if (hasVirtualAssembly) virtualPage else null,
            displayTotalPagesOverride = if (hasVirtualAssembly) assemblyVirtualTotalPages else null,
            onStepPage = if (hasVirtualAssembly) {
                { delta ->
                    currentPage = (virtualPage + delta).coerceIn(1, assemblyVirtualTotalPages.coerceAtLeast(1))
                }
            } else {
                null
            },
            onOpenSheetNavigator = if (hasVirtualAssembly) {
                { showCombinedSheetNavigator = true }
            } else {
                null
            }
        )
    }

    if (showCombinedSheetNavigator && hasVirtualAssembly) {
        val pages = remember(assemblyVirtualTotalPages) { (1..assemblyVirtualTotalPages).toList() }
        ModalBottomSheet(onDismissRequest = { showCombinedSheetNavigator = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(620.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Sheet Navigator", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Combined FF/FL assembly pages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pages, key = { it }) { page ->
                        val mapped = assemblyVirtualMap[page.toString()]
                        val sourceLabel = mapped?.let { source ->
                            val shortName = source.pdfFilename.substringBeforeLast(".pdf")
                            "$shortName - Page ${source.page}"
                        } ?: "Page $page"
                        val selected = page == virtualPage
                        Surface(
                            tonalElevation = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPage = page
                                    showCombinedSheetNavigator = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    "Sheet $page",
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    sourceLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
