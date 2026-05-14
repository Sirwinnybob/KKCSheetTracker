package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.HardwoodRowRevisionState
import com.kkc.sheettracker.data.models.HardwoodSearchEntry
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File
import kotlin.math.ceil
import java.util.Locale

class HardwoodsRepository(private var baseDir: File) {
    private val gson = Gson()
    private val revisionFilePathSuffix = ".metadata/hardwoods/cutlist_revisions.json"
    private var unifiedEngine: UnifiedMetadataEngine? = null

    private fun engine(): UnifiedMetadataEngine {
        val existing = unifiedEngine
        if (existing != null) return existing
        return UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG
        ).also { unifiedEngine = it }
    }

    fun updateBaseDir(newBaseDir: File) {
        baseDir = newBaseDir
        unifiedEngine = null
    }

    fun currentBasePath(): String = baseDir.absolutePath

    fun scanJobs(): List<HardwoodJob> {
        return engine().listJobs()
            .mapNotNull { info -> engine().getHardwoodsSnapshot(info.folderName)?.job }
            .sortedWith { a, b ->
                val numberCmp = compareJobNumbersDesc(a.jobNumber, b.jobNumber)
                if (numberCmp != 0) numberCmp else a.folderName.compareTo(b.folderName, ignoreCase = true)
            }
    }

    fun buildSearchIndex(jobs: List<HardwoodJob>): List<HardwoodSearchEntry> {
        val out = mutableListOf<HardwoodSearchEntry>()
        for (job in jobs) {
            val index = job.index ?: continue
            for (doc in index.documents) {
                for (row in doc.rows) {
                    out += HardwoodSearchEntry(
                        jobFolderName = job.folderName,
                        jobNumber = job.jobNumber,
                        jobName = job.jobName,
                        docType = doc.docType,
                        pdfFilename = doc.pdfFilename,
                        rowId = row.rowId,
                        description = row.description,
                        width = row.width,
                        length = row.length,
                        cabinetNumbers = row.cabinets,
                        rawCabinetText = row.rawCabinetText
                    )
                }
            }
        }
        return out
    }

    fun loadHardwoodsIndex(jobFolderName: String): HardwoodCutlistIndex? {
        return engine().getHardwoodsSnapshot(jobFolderName)?.job?.index
    }

    fun loadHardwoodsRevisionHistory(jobFolderName: String): HardwoodRevisionHistory? {
        val file = File(baseDir, "$jobFolderName/$revisionFilePathSuffix")
        if (!file.exists() || !file.isFile) return null
        return try {
            gson.fromJson(file.readText(), HardwoodRevisionHistory::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun findRowRevisionState(
        jobFolderName: String,
        docType: String,
        rowId: String
    ): HardwoodRowRevisionState? {
        val history = loadHardwoodsRevisionHistory(jobFolderName) ?: return null
        return history.currentRowStates.firstOrNull {
            it.docType == docType && it.rowId == rowId
        }
    }

    fun getRowRevisionStates(jobFolderName: String): Map<Pair<String, String>, HardwoodRowRevisionState> {
        val history = loadHardwoodsRevisionHistory(jobFolderName) ?: return emptyMap()
        return history.currentRowStates.associateBy { it.docType to it.rowId }
    }

    fun getHardwoodsPdfFile(jobFolderName: String, pdfFilename: String, preferDarkMode: Boolean): File? {
        val jobDir = File(baseDir, jobFolderName)
        val light = File(jobDir, pdfFilename)
        val dark = File(jobDir, "DARK MODE/$pdfFilename")
        return when {
            preferDarkMode && dark.exists() -> dark
            light.exists() -> light
            dark.exists() -> dark
            else -> null
        }
    }

    fun findHardwoodsPdfFilename(jobFolderName: String, docType: HardwoodDocType): String? {
        val needle = when (docType) {
            HardwoodDocType.FACE_FRAME_CUT_LIST -> "face frame cut list"
            HardwoodDocType.NAILER_CUT_LIST -> "nailer cut list"
            HardwoodDocType.DOOR_CUT_LIST -> "door cut list"
            HardwoodDocType.DOOR_LIST -> "door list"
        }
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return null

        fun findIn(dir: File): String? {
            val files = dir.listFiles() ?: return null
            return files.firstOrNull { file ->
                file.isFile &&
                    file.extension.lowercase(Locale.US) == "pdf" &&
                    file.name.lowercase(Locale.US).contains(needle)
            }?.name
        }
        return findIn(jobDir) ?: findIn(File(jobDir, "DARK MODE"))
    }

    fun countPdfPages(pdfFile: File?): Int {
        if (pdfFile == null || !pdfFile.exists()) return 0
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (_: Exception) {
            0
        }
    }

    fun loadBoardStock(jobFolderName: String): List<BoardStockRow> {
        return engine().getBoardStockRows(jobFolderName, includeProgressOverlay = false).rows
    }

    private fun loadManualBoardStock(jobFolderName: String): List<BoardStockRow> {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/board_stock_manual.json")
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val rootObj = gson.fromJson(file.readText(), JsonObject::class.java)
            val entries = rootObj?.getAsJsonArray("entries") ?: JsonArray()
            entries.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val material = obj.get("material")?.asString?.trim().orEmpty()
                val widthRaw = obj.get("width")?.asString ?: obj.get("normalizedWidth")?.asString.orEmpty()
                val feet = obj.get("totalFeet")?.asDouble ?: 0.0
                val normalizedWidth = normalizeWidth(widthRaw) ?: return@mapNotNull null
                if (feet <= 0.0) return@mapNotNull null
                BoardStockRow(
                    stableKey = "board_stock|$material|${formatWidth(normalizedWidth)}|MANUAL",
                    material = material,
                    width = formatWidth(normalizedWidth),
                    normalizedWidth = normalizedWidth,
                    source = BoardStockSource.MANUAL,
                    sourceLabel = BoardStockSource.MANUAL.name,
                    totalFeet = feet,
                    neededRips = ceil(feet / 10.0).toInt(),
                    manualCategory = obj.get("category")?.asString,
                    manualSubtype = obj.get("subtype")?.asString,
                    notes = obj.get("notes")?.asString
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseFeet(value: String): Double {
        val cleaned = value.trim().replace(",", "")
        if (cleaned.isEmpty()) return 0.0
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun normalizeWidth(width: String): Double? {
        val cleaned = width.trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    private fun formatWidth(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    private fun sourcePriority(source: BoardStockSource): Int {
        return when (source) {
            BoardStockSource.FRAME -> 0
            BoardStockSource.NAILER -> 1
            BoardStockSource.DOOR -> 2
            BoardStockSource.MANUAL -> 3
        }
    }
}
