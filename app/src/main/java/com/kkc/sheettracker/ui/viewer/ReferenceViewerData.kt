package com.kkc.sheettracker.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.ReferenceDocType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReferenceViewerData(
    val defaultPdfFilename: String,
    val virtualMapping: UnifiedVirtualPageMapping?,
    val navigatorCabinetToPages: Map<String, List<Int>>,
    val navigatorPlanViewLabels: Map<Int, String>,
    val warningMessage: String?
)

@Composable
fun rememberReferenceViewerData(
    jobRepository: JobRepository,
    jobFolderName: String,
    docType: ReferenceDocType,
    refreshGeneration: Long,
    isDarkTheme: Boolean
): ReferenceViewerData {
    val sheetIndex by produceState<CabinetSheetIndex?>(
        initialValue = null,
        key1 = jobFolderName,
        key2 = refreshGeneration
    ) {
        value = withContext(Dispatchers.IO) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    }
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
    val assemblyFallbackPdfFilename by produceState(
        initialValue = "",
        key1 = documentIndex,
        key2 = jobFolderName
    ) {
        value = documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) {
                jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY)
            }.orEmpty()
    }
    val assemblyVirtualSanitized = remember(
        assemblyVirtualTotalPages,
        assemblyVirtualRawMap,
        documentIndex,
        sheetIndex,
        assemblyFallbackPdfFilename
    ) {
        sanitizeVirtualAssemblyData(
            totalVirtualPages = assemblyVirtualTotalPages,
            defaultPdfFilename = assemblyFallbackPdfFilename,
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
    val defaultPdfFilename by produceState(
        documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }.orEmpty(),
        documentIndex,
        docType,
        jobFolderName,
        refreshGeneration
    ) {
        value = documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) {
                jobRepository.findReferencePdfFilename(jobFolderName, docType)
            }.orEmpty()
    }

    return ReferenceViewerData(
        defaultPdfFilename = defaultPdfFilename,
        virtualMapping = virtualMapping,
        navigatorCabinetToPages = navigatorCabinetToPages,
        navigatorPlanViewLabels = navigatorPlanViewLabels,
        warningMessage = if (docType == ReferenceDocType.ASSEMBLY) {
            assemblyVirtualSanitized.warningMessage
        } else {
            null
        }
    )
}
