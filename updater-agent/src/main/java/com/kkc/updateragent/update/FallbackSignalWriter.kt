package com.kkc.updateragent.update

import java.io.File
import java.time.Instant

class FallbackSignalWriter {
    fun writeFallbackRequired(file: File, reason: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val payload = mapOf(
            "timestamp" to Instant.now().toString(),
            "reason" to reason
        )
        file.writeText(Json.gson.toJson(payload))
    }

    fun clear(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }
}
