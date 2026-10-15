package com.kkc.sheettracker.data

import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CancellationException

/** CROSS-PROGRAM: HT ready_jobs_worker_core/adapters/pdf_preview_publish.py owns these files.
 * Read-only access to its full-page caches. Paths are relative to the actual PDF,
 * including CNC, nested documents and DARK MODE; never borrow a different variant's cache. */
private data class PdfPreviewManifest(
    val file: File,
    val contents: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val pageCount: Int
)

private fun readPdfPreviewManifest(pdf: File): PdfPreviewManifest? {
    if (!pdf.isFile) return null
    val parent = pdf.parentFile ?: return null
    val markerName = "${pdf.nameWithoutExtension}.preview.json"
    val marker = File(parent, ".metadata/.fullimages-state/$markerName").takeIf { it.isFile }
        ?: File(parent, ".metadata/.fullimages/$markerName").takeIf { it.isFile }
        ?: return null
    val contents = marker.readText()
    val value = JsonParser.parseString(contents).asJsonObject
    if (value.get("policyVersion")?.asInt !in listOf(1, 2) ||
        value.get("sourceFile")?.asString != pdf.name ||
        value.get("maxEdge")?.asInt != 2560 || value.get("dpi")?.asInt != 150) return null
    val source = value.getAsJsonObject("source") ?: return null
    val size = source.get("size")?.asLong ?: return null
    // Python records nanoseconds; Android File and Syncthing expose milliseconds.
    val modified = (source.get("mtimeNs")?.asLong ?: return null) / 1_000_000L
    val pages = value.get("pageCount")?.asInt ?: return null
    if (size <= 0 || modified <= 0 || pages <= 0 ||
        pdf.length() != size || pdf.lastModified() != modified) return null
    return PdfPreviewManifest(marker, contents, size, modified, pages)
}

private data class PdfPreviewCandidate(
    val manifest: PdfPreviewManifest,
    val image: File,
    val imageSize: Long,
    val imageModified: Long
)

private fun pdfPreviewCandidate(pdf: File, pageIndex: Int): PdfPreviewCandidate? {
    if (pageIndex < 0) return null
    val manifest = readPdfPreviewManifest(pdf) ?: return null
    if (pageIndex >= manifest.pageCount) return null
    val page = (pageIndex + 1).toString().padStart(3, '0')
    val image = File(pdf.parentFile, ".metadata/.fullimages/${pdf.nameWithoutExtension}_p${page}_render.jxl")
    if (!image.isFile) return null
    val size = image.length()
    val modified = image.lastModified()
    // Worker stamps every page to the source time. Equality also rejects an outgoing image
    // when a replacement PDF/marker arrives first and the new PDF has an older timestamp.
    if (size <= 0 || modified != manifest.sourceModified) return null
    return PdfPreviewCandidate(manifest, image, size, modified)
}

internal fun pdfPagePreviewCount(pdf: File): Int? = try {
    readPdfPreviewManifest(pdf)?.pageCount
} catch (_: Exception) {
    null
}

/** A missing/partial/stale cache, codec failure or replacement during decoding uses the PDF.
 * The callback makes this contract independent of Android bitmap/native codec APIs. */
internal fun <T> loadPdfPagePreview(pdf: File, pageIndex: Int, decode: (File) -> T?): T? = try {
    val before = pdfPreviewCandidate(pdf, pageIndex)
    if (before == null) null else {
        val decoded = decode(before.image)
        if (decoded != null && pdfPreviewCandidate(pdf, pageIndex) == before) decoded else null
    }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}
