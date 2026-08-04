package com.kkc.sheettracker.data

import java.io.File

/**
 * Cheap Jobs-list staleness fingerprint: hashes only deployment-gate and cache-index mtimes
 * (one stat per file, no reads). Full cache data is intentionally excluded: it is loaded only
 * when an operator opens that job.
 */
internal fun computeLightStalenessSignature(baseDir: File): Long {
    if (!baseDir.exists() || !baseDir.isDirectory) return Long.MIN_VALUE
    var hash = 1125899906842597L
    fun mix(v: Long) { hash = (hash * 31L) xor v }
    val dirs = baseDir.listFiles() ?: return Long.MIN_VALUE
    mix(dirs.size.toLong())
    dirs.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        mix(dir.name.hashCode().toLong())
        val indexFile = File(dir, ".metadata/cache_index.json")
        mix(if (indexFile.isFile) indexFile.lastModified() else 0L)
        val gateFile = File(dir, ".metadata/deployment_gate.json")
        mix(if (gateFile.isFile) gateFile.lastModified() else 0L)
    }
    return hash
}

/**
 * Specialty list fingerprint. Specialty's list cards are allowed to project counts from the
 * compact Hours Tracker sidecars, so their mtimes must participate in regular refreshes. This
 * intentionally excludes cache_static and CNC/Hardwoods metadata.
 */
internal fun computeSpecialtyListStalenessSignature(baseDir: File): Long {
    if (!baseDir.exists() || !baseDir.isDirectory) return Long.MIN_VALUE
    var hash = 1125899906842597L
    fun mix(value: Long) { hash = (hash * 31L) xor value }
    fun mixFile(file: File) { mix(if (file.isFile) file.lastModified() else 0L) }

    val dirs = baseDir.listFiles() ?: return Long.MIN_VALUE
    mix(dirs.size.toLong())
    dirs.filter { it.isDirectory }
        .sortedBy { it.name.lowercase() }
        .forEach { dir ->
            mix(dir.name.hashCode().toLong())
            val metadataDir = File(dir, ".metadata")
            mixFile(File(metadataDir, "cache_index.json"))
            mixFile(File(metadataDir, "deployment_gate.json"))

            val adminDir = File(metadataDir, "admin")
            mixFile(File(adminDir, "specialty_items.json"))
            mixFile(File(adminDir, "checklist.json"))
            val trackerFiles = File(adminDir, ".tracker")
                .listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension.equals("json", ignoreCase = true) &&
                        !it.name.startsWith(".") &&
                        !it.name.contains(".sync-conflict-")
                }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            mix(trackerFiles.size.toLong())
            trackerFiles.forEach { trackerFile ->
                mix(trackerFile.name.hashCode().toLong())
                mixFile(trackerFile)
            }
        }
    return hash
}
