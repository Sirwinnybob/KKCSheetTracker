package com.kkc.sheettracker.data

import android.os.FileObserver
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.PdfMarkupPageKey
import com.kkc.sheettracker.data.models.PdfPageMarkup
import com.kkc.sheettracker.data.models.PdfTabletMarkup
import com.kkc.sheettracker.logging.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

class PdfMarkupStore(
    private val baseDir: File,
    private val tabletId: String,
    private val readOnly: Boolean = false
) {
    companion object {
        private const val TAG = "PdfMarkupDebug"
        private const val MARKUP_OBSERVER_EVENTS = FileObserver.CLOSE_WRITE or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
    }

    private val gson = Gson()
    private val writeLocksByJob = ConcurrentHashMap<String, Any>()

    private fun normalizedPdfFilename(pdfFilename: String): String =
        pdfFilename.trim().lowercase(Locale.US)

    private fun jobDir(jobFolderName: String): File {
        require(isSafeJobFolderName(jobFolderName)) { "Job folder escapes base directory: $jobFolderName" }
        val basePath = baseDir.absoluteFile.toPath().normalize()
        val resolvedPath = basePath.resolve(jobFolderName).normalize()
        val withinBase = resolvedPath == basePath || resolvedPath.startsWith(basePath)
        require(withinBase) { "Job folder escapes base directory: $jobFolderName" }
        return resolvedPath.toFile()
    }

    private fun isSafeJobFolderName(jobFolderName: String): Boolean {
        return jobFolderName.isNotBlank() &&
            jobFolderName != "." &&
            jobFolderName != ".." &&
            jobFolderName.indexOfAny(charArrayOf('/', '\\')) == -1
    }

    private fun trackerDir(jobFolderName: String): File =
        File(jobDir(jobFolderName), ".metadata/pdf_markup/.tracker")

    private fun tabletMarkupFile(jobFolderName: String): File =
        File(trackerDir(jobFolderName), "$tabletId.markup.json")

    private fun legacyTabletMarkupFile(jobFolderName: String): File =
        File(trackerDir(jobFolderName), "$tabletId.json")

    fun loadTabletMarkup(jobFolderName: String): PdfTabletMarkup {
        val file = tabletMarkupFile(jobFolderName).takeIf { it.exists() }
            ?: legacyTabletMarkupFile(jobFolderName).takeIf { it.exists() }
            ?: return PdfTabletMarkup(tabletId = tabletId)
        val loaded = runCatching {
            gson.fromJson(file.readText(), PdfTabletMarkup::class.java)
        }.getOrNull()?.sanitize(fallbackTabletId = tabletId) ?: PdfTabletMarkup(tabletId = tabletId)
        AppLog.d(
            TAG,
            "loadTabletMarkup job=$jobFolderName tablet=$tabletId file=${file.name} pages=${loaded.pages.size}"
        )
        return loaded
    }

    fun savePageMarkup(
        jobFolderName: String,
        pdfFilename: String,
        page: Int,
        strokes: List<PdfInkStroke>,
        deletedStrokeIds: List<String>
    ) {
        if (readOnly) return
        val normalizedFilename = normalizedPdfFilename(pdfFilename)
        val lock = writeLocksByJob.getOrPut(jobFolderName) { Any() }
        synchronized(lock) {
            val current = loadTabletMarkup(jobFolderName)
            val nextPages = current.pages
                .filterNot { it.pdfFilename == normalizedFilename && it.page == page } +
                PdfPageMarkup(
                    pdfFilename = normalizedFilename,
                    page = page,
                    strokes = strokes,
                    deletedStrokeIds = deletedStrokeIds
                )
            AppLog.d(
                TAG,
                "savePageMarkup job=$jobFolderName tablet=$tabletId pdf=$normalizedFilename page=$page strokes=${strokes.size} deleted=${deletedStrokeIds.size} totalPages=${nextPages.size}"
            )
            saveTabletMarkup(jobFolderName, PdfTabletMarkup(tabletId = tabletId, pages = nextPages))
        }
    }

    fun loadTabletPageMarkup(
        jobFolderName: String,
        pdfFilename: String,
        page: Int
    ): PdfPageMarkup? {
        val normalizedFilename = normalizedPdfFilename(pdfFilename)
        return loadTabletMarkup(jobFolderName).pages.firstOrNull {
            it.pdfFilename == normalizedFilename && it.page == page
        }
    }

    fun getMergedActiveStrokes(
        jobFolderName: String,
        pdfFilename: String,
        page: Int
    ): List<PdfInkStroke> {
        val normalizedFilename = normalizedPdfFilename(pdfFilename)
        val strokes = getMergedActiveStrokesByPage(jobFolderName)[
            PdfMarkupPageKey(normalizedFilename, page)
        ].orEmpty()
        AppLog.d(
            TAG,
            "getMergedActiveStrokes job=$jobFolderName pdf=$normalizedFilename page=$page result=${strokes.size}"
        )
        return strokes
    }

    fun trackerContentVersion(jobFolderName: String): Long {
        val dir = trackerDir(jobFolderName)
        if (!dir.exists()) return 0L
        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") &&
                    (
                        it.name.endsWith(".markup.json", ignoreCase = true) ||
                            (it.extension.equals("json", ignoreCase = true) && !it.name.endsWith(".markup.json", ignoreCase = true))
                    )
            }
            ?.sortedBy { it.name }
            ?.fold(17L) { acc, file ->
                var next = acc * 31L + file.name.lowercase(Locale.US).hashCode().toLong()
                next = next * 31L + file.lastModified()
                next * 31L + file.length()
            }
            ?: 0L
    }

    @Suppress("DEPRECATION")
    fun createTrackerChangeObserver(
        jobFolderName: String,
        onChanged: () -> Unit
    ): FileObserver? {
        val dir = trackerDir(jobFolderName)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return object : FileObserver(dir.absolutePath, MARKUP_OBSERVER_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                val filename = path
                if (filename == null) {
                    if (event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                        onChanged()
                    }
                    return
                }
                if (!filename.endsWith(".json", ignoreCase = true)) return
                if (filename.startsWith(".") || filename.contains(".sync-conflict-")) return
                onChanged()
            }
        }
    }

    fun getMergedActiveStrokesByPage(jobFolderName: String): Map<PdfMarkupPageKey, List<PdfInkStroke>> {
        val markupByPage = LinkedHashMap<PdfMarkupPageKey, MutableList<PdfPageMarkup>>()
        loadAllTabletMarkup(jobFolderName).forEach { tabletMarkup ->
            tabletMarkup.pages.forEach { pageMarkup ->
                val key = PdfMarkupPageKey(normalizedPdfFilename(pageMarkup.pdfFilename), pageMarkup.page)
                markupByPage.getOrPut(key) { mutableListOf() }.add(pageMarkup)
            }
        }

        return markupByPage.mapValues { (_, pageMarkups) ->
            val deletedIds = pageMarkups
                .flatMap { it.deletedStrokeIds }
                .toSet()
            val activeById = LinkedHashMap<String, PdfInkStroke>()
            pageMarkups.forEach { pageMarkup ->
                pageMarkup.strokes.forEach { stroke ->
                    if (stroke.id !in deletedIds) {
                        activeById[stroke.id] = stroke
                    }
                }
            }
            activeById.values.toList()
        }
    }

    private fun saveTabletMarkup(jobFolderName: String, markup: PdfTabletMarkup) {
        val destFile = tabletMarkupFile(jobFolderName)
        atomicWriteFile(destFile, gson.toJson(markup))
        AppLog.d(
            TAG,
            "saveTabletMarkup job=$jobFolderName path=${destFile.absolutePath} exists=${destFile.exists()} bytes=${destFile.length()}"
        )
    }

    private fun loadAllTabletMarkup(jobFolderName: String): List<PdfTabletMarkup> {
        val dir = trackerDir(jobFolderName)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") &&
                    (
                        it.name.endsWith(".markup.json", ignoreCase = true) ||
                            (it.extension.equals("json", ignoreCase = true) && !it.name.endsWith(".markup.json", ignoreCase = true))
                    )
            }
            ?.sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
            ?.mapNotNull { file ->
                val fallbackTabletId = file.name
                    .removeSuffix(".markup.json")
                    .removeSuffix(".json")
                runCatching {
                    gson.fromJson(file.readText(), PdfTabletMarkup::class.java)
                }.getOrNull()?.sanitize(fallbackTabletId = fallbackTabletId)
            }
            .orEmpty()
    }

    private fun PdfTabletMarkup.sanitize(fallbackTabletId: String): PdfTabletMarkup {
        val safeTabletId = tabletId.ifBlank { fallbackTabletId }
        val safePages = pages.orEmpty().mapNotNull { pageMarkup ->
            if (pageMarkup.pdfFilename.isBlank() || pageMarkup.page <= 0) {
                null
            } else {
                PdfPageMarkup(
                    pdfFilename = normalizedPdfFilename(pageMarkup.pdfFilename),
                    page = pageMarkup.page,
                    strokes = pageMarkup.strokes.orEmpty().filter { it.id.isNotBlank() },
                    deletedStrokeIds = pageMarkup.deletedStrokeIds.orEmpty().filter { it.isNotBlank() }
                )
            }
        }
        return PdfTabletMarkup(
            tabletId = safeTabletId,
            pages = safePages
        )
    }
}
