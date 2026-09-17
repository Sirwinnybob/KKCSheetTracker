package com.kkc.sheettracker.perf

import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CpuSpikeContext(
    val tabletId: String? = null,
    val workMode: String? = null,
    val currentTab: String? = null,
    val currentRoute: String? = null,
    val activeJobFolderName: String? = null,
    val basePath: String? = null
)

data class CpuSpikeEnvironment(
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val androidRelease: String? = null,
    val androidSdk: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null
)

data class CpuSpikeEntry(
    val entryType: String,
    val triggerReason: String,
    val cpuPercent: Double,
    val peakCpuPercent: Double? = null,
    val avgCpuPercent: Double? = null,
    val durationMs: Long? = null
)

class CpuSpikeLogStore(
    private val pendingDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retentionLimit: Int = DEFAULT_RETENTION_LIMIT
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US)
    }
    private val tabletIdRegex = Regex("""^[0-9T:-]+_(.+?)_cpuspike""")

    private fun extractTabletId(fileName: String): String {
        return tabletIdRegex.find(fileName)?.groupValues?.get(1) ?: "unknown-tablet"
    }

    fun recordEntry(
        baseDir: File?,
        context: CpuSpikeContext,
        environment: CpuSpikeEnvironment,
        entry: CpuSpikeEntry
    ) {
        val now = clock()
        val report = buildReport(now, context, environment, entry)
        val fileName = logFileName(now, context.tabletId)
        val json = gson.toJson(report)

        val sharedDir = baseDir?.let { File(it, ".metadata/cpu_spikes") }
        if (sharedDir != null) {
            val wrote = runCatching {
                writeLogFile(sharedDir, fileName, json)
                enforceRetention(sharedDir)
                true
            }.getOrDefault(false)
            if (wrote) return
        }

        runCatching {
            writeLogFile(pendingDir, fileName, json)
            enforceRetention(pendingDir)
        }
    }

    fun flushPending(baseDir: File): List<File> {
        val files = pendingDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .sortedBy { it.lastModified() }
        if (files.isEmpty()) return emptyList()

        val targetDir = File(baseDir, ".metadata/cpu_spikes")
        targetDir.mkdirs()
        val copied = mutableListOf<File>()
        for (file in files) {
            val target = uniqueFile(targetDir, file.name)
            file.copyTo(target, overwrite = false)
            if (target.exists()) {
                file.delete()
                copied += target
            }
        }
        enforceRetention(targetDir)
        return copied
    }

    fun enforceRetention(dir: File) {
        if (retentionLimit <= 0) return
        val reports = dir.listFiles()
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
        context: CpuSpikeContext,
        environment: CpuSpikeEnvironment,
        entry: CpuSpikeEntry
    ): Map<String, Any?> {
        return linkedMapOf(
            "schemaVersion" to 1,
            "timestampMs" to timestampMs,
            "timestampLocal" to timestampFormat.get()!!.format(Date(timestampMs)),
            "entryType" to entry.entryType,
            "triggerReason" to entry.triggerReason,
            "cpuPercent" to entry.cpuPercent,
            "peakCpuPercent" to entry.peakCpuPercent,
            "avgCpuPercent" to entry.avgCpuPercent,
            "durationMs" to entry.durationMs,
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
            "model" to environment.model
        )
    }

    private fun writeLogFile(dir: File, fileName: String, json: String) {
        dir.mkdirs()
        val target = uniqueFile(dir, fileName)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var suffix = 1
        while (candidate.exists()) {
            val suffixedName = if (extension.isBlank()) "$base-$suffix" else "$base-$suffix.$extension"
            candidate = File(dir, suffixedName)
            suffix++
        }
        return candidate
    }

    private fun logFileName(timestampMs: Long, tabletId: String?): String {
        val timestamp = timestampFormat.get()!!.format(Date(timestampMs))
        return "${timestamp}_${sanitizeFilePart(tabletId)}_cpuspike.json"
    }

    private fun sanitizeFilePart(value: String?): String {
        val sanitized = value
            .orEmpty()
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return sanitized.ifBlank { "unknown-tablet" }
    }

    companion object {
        const val DEFAULT_RETENTION_LIMIT = 200
    }
}
