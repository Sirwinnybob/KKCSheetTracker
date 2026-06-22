package com.kkc.sheettracker.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import android.graphics.pdf.PdfRenderer
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.JobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class FileTreeEntry(
    val name: String,
    val relativePath: String,
    val file: File,
    val isDirectory: Boolean,
    val depth: Int,
    val parentRelativePath: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintDocumentsBottomSheet(
    jobFolderName: String,
    jobRepository: JobRepository,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allEntries by remember { mutableStateOf<List<FileTreeEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var collapsedFolders by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(jobFolderName) {
        withContext(Dispatchers.IO) {
            val jobDir = jobRepository.getJobDirectory(jobFolderName)
            
            val scanned = if (jobDir.exists() && jobDir.isDirectory) {
                scanJobDirectory(jobDir)
            } else {
                emptyList()
            }
            allEntries = scanned
            collapsedFolders = scanned.filter { it.isDirectory }.map { it.relativePath }.toSet()
            isLoading = false
        }
    }

    val visibleEntries = remember(allEntries, collapsedFolders) {
        allEntries.filter { entry ->
            if (entry.parentRelativePath.isEmpty()) true
            else {
                var isAncestorCollapsed = false
                var parent = entry.parentRelativePath
                while (parent.isNotEmpty()) {
                    if (collapsedFolders.contains(parent)) {
                        isAncestorCollapsed = true
                        break
                    }
                    val idx = parent.lastIndexOf(File.separatorChar)
                    parent = if (idx != -1) parent.substring(0, idx) else ""
                }
                !isAncestorCollapsed
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        ImmersiveDialogDecor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 550.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Print Job Documents",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (allEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No printable documents (PDF/Images) found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(bottom = 24.dp)
                ) {
                    items(visibleEntries, key = { it.relativePath }) { entry ->
                        val isCollapsed = collapsedFolders.contains(entry.relativePath)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        collapsedFolders = if (isCollapsed) {
                                            collapsedFolders - entry.relativePath
                                        } else {
                                            collapsedFolders + entry.relativePath
                                        }
                                    } else {
                                        printFile(context, entry.file)
                                        onDismissRequest()
                                    }
                                }
                                .padding(
                                    start = (entry.depth * 16).dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                    end = 8.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (entry.isDirectory) {
                                Icon(
                                    imageVector = if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Folder",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.width(24.dp)) // Alignment offset for files
                                Surface(
                                    modifier = Modifier.size(36.dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                ) {
                                    FilePreviewThumbnail(file = entry.file, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = entry.name,
                                style = if (entry.isDirectory) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyMedium,
                                color = if (entry.isDirectory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
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

private fun scanJobDirectory(jobDir: File): List<FileTreeEntry> {
    val result = mutableListOf<FileTreeEntry>()
    scanDirRecursive(jobDir, jobDir, 0, "", result)
    return result
}

private fun scanDirRecursive(
    rootDir: File,
    currentDir: File,
    depth: Int,
    parentRelativePath: String,
    result: MutableList<FileTreeEntry>
) {
    val files = currentDir.listFiles() ?: return
    files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { file ->
        if (file.isHidden || file.name.startsWith(".")) {
            return@forEach
        }
        val relativePath = file.relativeTo(rootDir).path
        val isPrintable = !file.isDirectory && (
            file.name.lowercase().endsWith(".pdf") ||
            file.name.lowercase().endsWith(".png") ||
            file.name.lowercase().endsWith(".jpg") ||
            file.name.lowercase().endsWith(".jpeg")
        )

        if (file.isDirectory) {
            if (hasPrintableFiles(file)) {
                result.add(
                    FileTreeEntry(
                        name = file.name,
                        relativePath = relativePath,
                        file = file,
                        isDirectory = true,
                        depth = depth,
                        parentRelativePath = parentRelativePath
                    )
                )
                scanDirRecursive(rootDir, file, depth + 1, relativePath, result)
            }
        } else if (isPrintable) {
            result.add(
                FileTreeEntry(
                    name = file.name,
                    relativePath = relativePath,
                    file = file,
                    isDirectory = false,
                    depth = depth,
                    parentRelativePath = parentRelativePath
                )
            )
        }
    }
}

private fun hasPrintableFiles(dir: File): Boolean {
    if (dir.isHidden || dir.name.startsWith(".")) return false
    val files = dir.listFiles() ?: return false
    return files.any {
        if (it.isHidden || it.name.startsWith(".")) {
            false
        } else {
            (it.isDirectory && hasPrintableFiles(it)) ||
            (!it.isDirectory && (
                it.name.lowercase().endsWith(".pdf") ||
                it.name.lowercase().endsWith(".png") ||
                it.name.lowercase().endsWith(".jpg") ||
                it.name.lowercase().endsWith(".jpeg")
            ))
        }
    }
}

private fun printFile(context: Context, file: File) {
    val name = file.name.lowercase()
    if (name.endsWith(".pdf")) {
        printPdfFile(context, file)
    } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
        printImageFile(context, file)
    }
}

private fun printPdfFile(context: Context, file: File) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = "KKC Sheet Tracker - ${file.name}"
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            var input: FileInputStream? = null
            var output: FileOutputStream? = null
            try {
                input = FileInputStream(file)
                output = FileOutputStream(destination?.fileDescriptor)
                val buf = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } >= 0) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onWriteCancelled()
                        return
                    }
                    output.write(buf, 0, bytesRead)
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                Log.e("PrintDocuments", "Failed to print PDF: ${file.name}", e)
                callback?.onWriteFailed(e.toString())
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
            }
        }
    }
    printManager.print(jobName, adapter, null)
}

private fun printImageFile(context: Context, file: File) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = "KKC Sheet Tracker - ${file.name}"
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(792, 612, 1).create()
            val page = pdfDocument.startPage(pageInfo)

            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val canvas = page.canvas
                    val scaleX = canvas.width.toFloat() / bitmap.width.toFloat()
                    val scaleY = canvas.height.toFloat() / bitmap.height.toFloat()
                    val scale = minOf(scaleX, scaleY)

                    val scaledWidth = bitmap.width * scale
                    val scaledHeight = bitmap.height * scale
                    val left = (canvas.width - scaledWidth) / 2f
                    val top = (canvas.height - scaledHeight) / 2f

                    val destRect = android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight)
                    canvas.drawBitmap(bitmap, null, destRect, null)
                    bitmap.recycle()
                }
                pdfDocument.finishPage(page)

                FileOutputStream(destination?.fileDescriptor).use { output ->
                    pdfDocument.writeTo(output)
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                Log.e("PrintDocuments", "Failed to print Image: ${file.name}", e)
                callback?.onWriteFailed(e.toString())
            } finally {
                pdfDocument.close()
            }
        }
    }
    printManager.print(jobName, adapter, null)
}

@Composable
fun FilePreviewThumbnail(
    file: File,
    modifier: Modifier = Modifier
) {
    var pdfBitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    val isPdf = file.name.lowercase().endsWith(".pdf")

    if (isPdf) {
        LaunchedEffect(file) {
            withContext(Dispatchers.IO) {
                pdfBitmap = renderPdfFirstPageThumbnail(file)
            }
        }
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            val bmp = pdfBitmap
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "PDF Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "PDF File",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    } else {
        coil.compose.SubcomposeAsyncImage(
            model = file,
            contentDescription = "Image Preview",
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            error = {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Image File",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )
    }
}

private fun renderPdfFirstPageThumbnail(pdfFile: File): Bitmap? {
    if (!pdfFile.exists()) return null
    var fd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null
    return try {
        fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)
        if (renderer.pageCount > 0) {
            page = renderer.openPage(0)
            val targetSize = 120
            val scale = targetSize.toFloat() / maxOf(page.width, page.height).toFloat().coerceAtLeast(1f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { page?.close() }
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
    }
}
