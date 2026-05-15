package com.kkc.updateragent.update

import java.io.File
import java.time.Instant

class AuditLogWriter {
    fun append(logFile: File, record: InstallAuditRecord) {
        val parent = logFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        logFile.appendText(Json.gson.toJson(record) + "\n")
    }

    fun appendResult(
        logFile: File,
        packageName: String,
        fromVersionCode: Long?,
        toVersionCode: Long?,
        result: String,
        error: String? = null
    ) {
        append(
            logFile = logFile,
            record = InstallAuditRecord(
                timestamp = Instant.now().toString(),
                packageName = packageName,
                fromVersionCode = fromVersionCode,
                toVersionCode = toVersionCode,
                result = result,
                error = error
            )
        )
    }
}
