package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.kkc.sheettracker.data.PreparedDiagram
import java.io.File
import kotlin.math.roundToInt

private const val SIDECAR_IMAGE_TAG = "CncSidecarImages"

internal fun resolveCncSidecarFile(pdfFile: File, relativeOrAbsolute: String?): File? {
    val trimmed = relativeOrAbsolute?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val candidate = File(trimmed)
    return if (candidate.isAbsolute) candidate else File(pdfFile.parentFile, trimmed)
}

internal fun cncSidecarImagePaths(legacyPath: String?, jxlPath: String?): List<String> {
    val legacy = legacyPath?.trim()?.takeIf { it.isNotEmpty() }
    val explicitJxl = jxlPath?.trim()?.takeIf { it.isNotEmpty() }
    val companion = legacy?.let {
        if (it.endsWith(".jxl", ignoreCase = true)) it else it.substringBeforeLast('.') + ".jxl"
    }
    return listOfNotNull(explicitJxl, companion, legacy).distinct()
}

/** Reads image assets only; pdfFile supplies the directory, never PDF contents. */
internal fun <T> loadPreferredCncSidecar(
    pdfFile: File,
    legacyPath: String?,
    jxlPath: String? = null,
    decode: (File) -> T?
): T? {
    val legacyFile = resolveCncSidecarFile(pdfFile, legacyPath)?.takeIf { it.isFile }
    for (path in cncSidecarImagePaths(legacyPath, jxlPath)) {
        val file = resolveCncSidecarFile(pdfFile, path) ?: continue
        if (!file.isFile) continue
        // Syncthing may deliver a replacement PNG before its JXL companion.
        if (file.extension.equals("jxl", ignoreCase = true) && legacyFile != null &&
            file.lastModified() < legacyFile.lastModified()) continue
        try {
            decode(file)?.let { return it }
        } catch (e: Exception) {
            Log.w(SIDECAR_IMAGE_TAG, "Image decode failed; trying fallback: ${file.name}", e)
        } catch (e: LinkageError) {
            Log.w(SIDECAR_IMAGE_TAG, "Native JXL decoder unavailable; trying legacy image", e)
        }
    }
    return null
}

private data class DecodedCncImage(val bitmap: Bitmap, val sourceWidth: Int)

private fun decodeCncImage(file: File, maxWidth: Int, maxHeight: Int, rgb565: Boolean): DecodedCncImage? {
    if (file.extension.equals("jxl", ignoreCase = true)) {
        val bytes = file.readBytes()
        val size = JxlCoder.getSize(bytes) ?: return null
        if (size.width <= 0 || size.height <= 0) return null
        val scale = minOf(1f, maxWidth.toFloat() / size.width, maxHeight.toFloat() / size.height)
        val config = if (rgb565) PreferredColorConfig.RGB_565 else PreferredColorConfig.RGBA_8888
        val bitmap = if (scale >= 1f) {
            JxlCoder.decode(bytes, preferredColorConfig = config)
        } else {
            JxlCoder.decodeSampled(
                bytes,
                (size.width * scale).roundToInt().coerceAtLeast(1),
                (size.height * scale).roundToInt().coerceAtLeast(1),
                preferredColorConfig = config
            )
        }
        return DecodedCncImage(bitmap, size.width)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Sample near the display size, then fit exactly; avoid decoding large
    // thumbnail PNGs at their full stored resolution before shrinking them.
    val ratio = maxOf(bounds.outWidth.toFloat() / maxWidth, bounds.outHeight.toFloat() / maxHeight)
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 && sample * 2 <= ratio) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    val scale = minOf(1f, maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height)
    val bitmap = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
        decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1),
        (decoded.height * scale).roundToInt().coerceAtLeast(1), false
    ).also { if (it !== decoded) decoded.recycle() }
    return DecodedCncImage(bitmap, bounds.outWidth)
}

internal fun loadCncSidecarBitmap(
    pdfFile: File,
    imagePath: String?,
    jxlPath: String? = null,
    maxWidth: Int = DIAGRAM_MAX_EDGE_PX,
    maxHeight: Int = DIAGRAM_MAX_EDGE_PX
): Bitmap? = loadPreferredCncSidecar(pdfFile, imagePath, jxlPath) {
    decodeCncImage(it, maxWidth, maxHeight, rgb565 = false)?.bitmap
}

internal fun loadCncSidecarDiagram(
    pdfFile: File,
    diagramPath: String?,
    ocrImageWidth: Int?,
    diagramJxlPath: String? = null
): PreparedDiagram? = loadPreferredCncSidecar(pdfFile, diagramPath, diagramJxlPath) {
    val image = decodeCncImage(it, DIAGRAM_MAX_EDGE_PX, DIAGRAM_MAX_EDGE_PX, rgb565 = true)
        ?: return@loadPreferredCncSidecar null
    PreparedDiagram(image.bitmap, sidecarDiagramSourceScale(image.bitmap.width, ocrImageWidth ?: image.sourceWidth))
}
