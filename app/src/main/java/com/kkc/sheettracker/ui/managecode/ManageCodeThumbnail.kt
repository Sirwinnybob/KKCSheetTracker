package com.kkc.sheettracker.ui.managecode

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun renderManageCodeThumbnail(pdfFile: File, pageNumber: Int, targetWidthPx: Int = 96): Bitmap? =
    withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pageNumber < 1) return@withContext null
        runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val index = pageNumber - 1
                    if (index !in 0 until renderer.pageCount) return@use null
                    renderer.openPage(index).use { page ->
                        val scale = targetWidthPx.toFloat() / page.width
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }.getOrNull()
    }
