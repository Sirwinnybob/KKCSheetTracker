package com.kkc.sheettracker.data

import java.io.File

/**
 * Cheap staleness fingerprint: hashes each job folder's cache_static.json mtime (one stat
 * per job, no file reads). Coordinators compare this against the signature from their last
 * successful scan to skip a full re-scan when nothing on disk has changed.
 */
internal fun computeLightStalenessSignature(baseDir: File): Long {
    if (!baseDir.exists() || !baseDir.isDirectory) return Long.MIN_VALUE
    var hash = 1125899906842597L
    fun mix(v: Long) { hash = (hash * 31L) xor v }
    val dirs = baseDir.listFiles() ?: return Long.MIN_VALUE
    mix(dirs.size.toLong())
    // Mix the root job_board.json mtime once (not per-job) so a board change alone flips the signature.
    val boardFile = File(baseDir, "job_board.json")
    mix(if (boardFile.isFile) boardFile.lastModified() else 0L)
    dirs.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        mix(dir.name.hashCode().toLong())
        val cacheFile = File(dir, ".metadata/cache_static.json")
        mix(if (cacheFile.isFile) cacheFile.lastModified() else 0L)
        val indexFile = File(dir, ".metadata/cache_index.json")
        mix(if (indexFile.isFile) indexFile.lastModified() else 0L)
        val gateFile = File(dir, ".metadata/deployment_gate.json")
        mix(if (gateFile.isFile) gateFile.lastModified() else 0L)
    }
    return hash
}
