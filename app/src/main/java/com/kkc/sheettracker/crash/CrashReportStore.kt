package com.kkc.sheettracker.crash

import com.google.gson.GsonBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReportContext(
    val tabletId: String? = null,
    val workMode: String? = null,
    val currentTab: String? = null,
    val currentRoute: String? = null,
    val activeJobFolderName: String? = null,
    val basePath: String? = null
)

data class CrashEnvironment(
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val androidRelease: String? = null,
    val androidSdk: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null
)

data class CrashWriteResult(
    val writtenFile: File,
    val wrotePending: Boolean
)

class CrashReportStore(
    private val pendingDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retentionLimit: Int = DEFAULT_RETENTION_LIMIT
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US)
    }
    private val tabletIdRegex = Regex("""^[0-9T:-]+_(.+?)_crash""")

    private fun extractTabletId(fileName: String): String {
        return tabletIdRegex.find(fileName)?.groupValues?.get(1) ?: "unknown-tablet"
    }

    fun recordCrash(
        baseDir: File?,
        context: CrashReportContext,
        environment: CrashEnvironment,
        throwable: Throwable
    ): CrashWriteResult {
        val now = clock()
        val report = buildReport(now, context, environment, throwable)
        val fileName = crashFileName(now, context.tabletId)
        val json = gson.toJson(report)

        val sharedDir = baseDir?.let { File(it, ".metadata/crashes") }
        if (sharedDir != null) {
            runCatching {
                val file = writeCrashFile(sharedDir, fileName, json)
                enforceRetention(sharedDir)
                return CrashWriteResult(writtenFile = file, wrotePending = false)
            }
        }

        val pendingFile = writeCrashFile(pendingDir, fileName, json)
        enforceRetention(pendingDir)
        return CrashWriteResult(writtenFile = pendingFile, wrotePending = true)
    }

    fun flushPending(baseDir: File): List<File> {
        val files = pendingDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .sortedBy { it.lastModified() }
        if (files.isEmpty()) return emptyList()

        val crashDir = File(baseDir, ".metadata/crashes")
        crashDir.mkdirs()
        val copied = mutableListOf<File>()
        for (file in files) {
            val target = uniqueFile(crashDir, file.name)
            file.copyTo(target, overwrite = false)
            if (target.exists()) {
                file.delete()
                copied += target
            }
        }
        enforceRetention(crashDir)
        return copied
    }

    fun enforceRetention(crashDir: File) {
        if (retentionLimit <= 0) return
        val reports = crashDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        val groups = reports.groupBy { extractTabletId(it.name) }
        for ((_, groupReports) in groups) {
            val sorted = groupReports.sortedWith(
                compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }
            )
            sorted.drop(retentionLimit).forEach { it.delete() }
        }
    }

    private fun buildReport(
        timestampMs: Long,
        context: CrashReportContext,
        environment: CrashEnvironment,
        throwable: Throwable
    ): Map<String, Any?> {
        return linkedMapOf(
            "schemaVersion" to 1,
            "timestampMs" to timestampMs,
            "timestampLocal" to timestampFormat.get()!!.format(Date(timestampMs)),
            "tabletId" to context.tabletId,
            "workMode" to context.workMode,
            "currentTab" to context.currentTab,
            "currentRoute" to context.currentRoute,
            "activeJobFolderName" to context.activeJobFolderName,
            "basePath" to context.basePath,
            "appVersionName" to environment.appVersionName,
            "appVersionCode" to environment.appVersionCode,
            "androidRelease" to environment.androidRelease,
            "androidSdk" to environment.androidSdk,
            "manufacturer" to environment.manufacturer,
            "model" to environment.model,
            "exceptionType" to throwable::class.java.name,
            "message" to throwable.message,
            "stackTrace" to stackTraceString(throwable)
        )
    }

    private fun writeCrashFile(dir: File, fileName: String, json: String): File {
        dir.mkdirs()
        val target = uniqueFile(dir, fileName)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var suffix = 1
        while (candidate.exists()) {
            val suffixedName = if (extension.isBlank()) {
                "$base-$suffix"
            } else {
                "$base-$suffix.$extension"
            }
            candidate = File(dir, suffixedName)
            suffix++
        }
        return candidate
    }

    private fun crashFileName(timestampMs: Long, tabletId: String?): String {
        val timestamp = timestampFormat.get()!!.format(Date(timestampMs))
        return "${timestamp}_${sanitizeFilePart(tabletId)}_crash.json"
    }

    private fun sanitizeFilePart(value: String?): String {
        val sanitized = value
            .orEmpty()
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return sanitized.ifBlank { "unknown-tablet" }
    }

    private fun stackTraceString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    companion object {
        const val DEFAULT_RETENTION_LIMIT = 100
    }
}
