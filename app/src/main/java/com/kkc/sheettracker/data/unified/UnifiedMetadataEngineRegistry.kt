package com.kkc.sheettracker.data.unified

import java.io.File
import java.util.concurrent.ConcurrentHashMap

object UnifiedMetadataEngineRegistry {
    private val byKey = ConcurrentHashMap<String, UnifiedMetadataEngine>()

    fun getOrCreate(
        baseDir: File,
        isDebugBuild: Boolean,
        pdfPageCounter: (File) -> Int = { 0 }
    ): UnifiedMetadataEngine {
        val key = "${baseDir.absolutePath}|$isDebugBuild"
        return byKey[key] ?: synchronized(this) {
            byKey[key] ?: FileBackedUnifiedMetadataEngine(
                basePath = baseDir.absolutePath,
                isDebugBuild = isDebugBuild,
                pdfPageCounter = pdfPageCounter
            ).also { byKey[key] = it }
        }
    }

    fun clear(baseDir: File, isDebugBuild: Boolean) {
        val key = "${baseDir.absolutePath}|$isDebugBuild"
        byKey.remove(key)
    }

    fun clearAll() {
        byKey.clear()
    }
}
