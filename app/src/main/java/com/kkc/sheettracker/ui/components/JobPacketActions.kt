package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.JobPdfCatalog
import com.kkc.sheettracker.data.models.JobPdfRef
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.ui.components.icons.ReferenceDeliveryIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceHandoffIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun jobPacketDocuments(catalog: JobPdfCatalog): List<JobPdfRef> {
    fun firstMatch(stem: String): JobPdfRef? {
        val standaloneName = "$stem.pdf"
        val prefixedSuffix = " - $standaloneName"
        return catalog.otherDocs.firstNotNullOfOrNull { document ->
            val filename = document.pdfFilename
            if (
                filename.isBlank() ||
                filename.contains(".sync-conflict-", ignoreCase = true) ||
                !filename.endsWith(".pdf", ignoreCase = true)
            ) {
                return@firstNotNullOfOrNull null
            }

            if (
                filename.equals(standaloneName, ignoreCase = true) ||
                (
                    filename.endsWith(prefixedSuffix, ignoreCase = true) &&
                        filename.dropLast(prefixedSuffix.length).isNotBlank()
                    )
            ) {
                document.copy(label = stem.toPacketLabel())
            } else {
                null
            }
        }
    }

    return listOfNotNull(
        firstMatch("HANDOFF SHEET"),
        firstMatch("DELIVERY CHECKLIST")
    )
}

private fun String.toPacketLabel(): String = when (this) {
    "HANDOFF SHEET" -> "Handoff Sheet"
    else -> "Delivery Checklist"
}

private fun JobPdfRef.packetIcon(): ImageVector = when (label) {
    "Handoff Sheet" -> ReferenceHandoffIcon
    else -> ReferenceDeliveryIcon
}

@Composable
fun rememberJobPacketActions(
    jobRepository: JobRepository,
    jobFolderName: String,
    onOpenJobPacketDocument: (JobPdfRef) -> Unit
): List<KKCPillAction> {
    return key(jobRepository, jobFolderName) {
        val documents by produceState(initialValue = emptyList<JobPdfRef>()) {
            value = withContext(Dispatchers.IO) {
                jobPacketDocuments(jobRepository.getJobPdfCatalog(jobFolderName))
            }
        }

        remember(documents, onOpenJobPacketDocument) {
            documents.map { document ->
                KKCPillAction(
                    label = document.label,
                    onClick = { onOpenJobPacketDocument(document) },
                    icon = document.packetIcon()
                )
            }
        }
    }
}
