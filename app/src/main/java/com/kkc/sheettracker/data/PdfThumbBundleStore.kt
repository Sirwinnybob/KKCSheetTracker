package com.kkc.sheettracker.data

import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** CROSS-PROGRAM: HT ready_jobs_worker_core/adapters/pdf_thumb_bundle.py owns this file.
 * Read-only access to the per-PDF zip of 200px WebP page previews at
 * `<pdf parent>/.metadata/.fullimages-state/<stem>.thumbs.zip`: entries `p001.webp`... plus
 * `manifest.json` carrying the PDF's size and nanosecond mtime, validated here exactly like the
 * JXL completion marker. A missing, partial, stale or unreadable zip yields null and callers fall
 * back to decoding the full-page JXLs. */
internal const val PDF_THUMB_BUNDLE_VERSION = 1
internal const val PDF_THUMB_BUNDLE_WIDTH = 200

internal fun pdfThumbBundleFile(pdf: File): File? = pdf.parentFile?.let {
    File(it, ".metadata/.fullimages-state/${pdf.nameWithoutExtension}.thumbs.zip")
}

private fun validatedPageCount(zip: ZipFile, pdf: File): Int? {
    val entry = zip.getEntry("manifest.json") ?: return null
    val value = JsonParser.parseString(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
        .asJsonObject
    if (value.get("version")?.asInt != PDF_THUMB_BUNDLE_VERSION ||
        value.get("sourceFile")?.asString != pdf.name ||
        value.get("width")?.asInt != PDF_THUMB_BUNDLE_WIDTH) return null
    val source = value.getAsJsonObject("source") ?: return null
    val size = source.get("size")?.asLong ?: return null
    // Python records nanoseconds; Android File and Syncthing expose milliseconds.
    val modified = (source.get("mtimeNs")?.asLong ?: return null) / 1_000_000L
    val pages = value.get("pageCount")?.asInt ?: return null
    if (size <= 0 || modified <= 0 || pages <= 0 ||
        pdf.length() != size || pdf.lastModified() != modified) return null
    return pages
}

/** Decodes every page of the PDF's thumbnail zip through [decode] (bytes of one WebP -> T).
 * Returns zero-based page index -> decoded value, or null when there is no valid, current zip.
 * Pages whose bytes fail to decode are simply absent. */
internal fun <T : Any> loadPdfThumbBundle(pdf: File, decode: (ByteArray) -> T?): Map<Int, T>? = try {
    val bundle = pdfThumbBundleFile(pdf)?.takeIf { pdf.isFile && it.isFile }
    if (bundle == null) null else ZipFile(bundle).use { zip ->
        val pages = validatedPageCount(zip, pdf)
        if (pages == null) null else {
            val decoded = LinkedHashMap<Int, T>(pages)
            for (index in 0 until pages) {
                val entry = zip.getEntry("p${(index + 1).toString().padStart(3, '0')}.webp") ?: continue
                val value = try {
                    decode(zip.getInputStream(entry).use { it.readBytes() })
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (value != null) decoded[index] = value
            }
            // The PDF or zip was replaced while decoding: those pages belong to another version.
            if (validatedPageCountOrNull(pdf, bundle) == pages) decoded else null
        }
    }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

private fun validatedPageCountOrNull(pdf: File, bundle: File): Int? = try {
    ZipFile(bundle).use { validatedPageCount(it, pdf) }
} catch (_: Exception) {
    null
}
