package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodSearchEntry
import java.io.File
import java.util.Locale

class HardwoodsRepository(private var baseDir: File) {
    private val gson = Gson()

    fun updateBaseDir(newBaseDir: File) {
        baseDir = newBaseDir
    }

    fun scanJobs(): List<HardwoodJob> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { jobDir ->
                val match = Regex("""^(\d+)\s*-\s*(.+)$""").find(jobDir.name) ?: return@mapNotNull null
                val jobNumber = match.groupValues[1]
                val jobName = match.groupValues[2].trim()
                HardwoodJob(
                    folderName = jobDir.name,
                    jobNumber = jobNumber,
                    jobName = jobName,
                    index = loadHardwoodsIndex(jobDir.name)
                )
            }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
            ?: emptyList()
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
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
        if (!file.exists() || !file.isFile) return null
        return try {
            gson.fromJson(file.readText(), HardwoodCutlistIndex::class.java)
        } catch (_: Exception) {
            null
        }
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
}
