package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import java.io.File

internal data class ContinuousPdfPageIdentity(
    val displayPage: Int,
    val pdfFilename: String,
    val sourcePage: Int
)

internal data class ContinuousPdfSourceIdentity(
    val pdfFilename: String,
    val absolutePath: String?,
    val exists: Boolean,
    val length: Long,
    val lastModified: Long
)

internal data class ContinuousPdfDocumentIdentity(
    val pages: List<ContinuousPdfPageIdentity>,
    val sources: List<ContinuousPdfSourceIdentity>
)

internal fun resolveContinuousPdfDocumentIdentity(
    totalPages: Int,
    resolvePage: (Int) -> ResolvedPageSource,
    pdfFileForFilename: (String) -> File?
): ContinuousPdfDocumentIdentity {
    val pages = (1..totalPages.coerceAtLeast(0)).map { displayPage ->
        val resolved = resolvePage(displayPage)
        ContinuousPdfPageIdentity(displayPage, resolved.pdfFilename, resolved.sourcePage)
    }
    val sources = pages.asSequence()
        .map { it.pdfFilename }
        .distinct()
        .sorted()
        .map { filename ->
            val file = runCatching { pdfFileForFilename(filename) }.getOrNull()
            val exists = runCatching { file?.isFile == true }.getOrDefault(false)
            ContinuousPdfSourceIdentity(
                pdfFilename = filename,
                absolutePath = file?.absolutePath,
                exists = exists,
                length = if (exists) runCatching { file?.length() ?: 0L }.getOrDefault(0L) else 0L,
                lastModified = if (exists) runCatching { file?.lastModified() ?: 0L }.getOrDefault(0L) else 0L
            )
        }
        .toList()
    return ContinuousPdfDocumentIdentity(pages, sources)
}
