package com.kkc.sheettracker.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [body] to [target] without ever exposing a reader to a truncated/partial file.
 *
 * Pattern: write the full contents to a sibling temp file (`<name>.tmp-<nanoTime>`, unique per
 * call so concurrent writers in the same process never collide on the temp name), then commit it
 * onto [target] with `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`. `ATOMIC_MOVE` guarantees a
 * concurrent reader on the same filesystem sees either the old file or the fully-written new one,
 * never a half-written one. On filesystems/setups that don't support an atomic move for this pair
 * of paths, `AtomicMoveNotSupportedException` is caught and a plain `Files.move(...,
 * REPLACE_EXISTING)` is used as a fallback (loses the atomicity guarantee, but still avoids a
 * truncating in-place write).
 *
 * This was previously copy-pasted as a private `atomicWrite` method in six separate files
 * (`ProgressStore`, `HardwoodsProgressStore`, `SpecialtyProgressStore`, `SupplyRepository`,
 * `SheetRipProgressStore`, `TabletSpecialtyItemsStore`) — each byte-for-byte identical in logic.
 * Centralized here per METADATA_AUDIT.md R-04 ("Centralize each program's atomic-write +
 * conflict-exclusion into one helper per repo... one hardened writer removes drift").
 *
 * All six call sites write into `Y:\Ready Jobs\...` metadata that is Syncthing-replicated and/or
 * read by peer tablets, Ready Jobs Watcher, or the Hours Tracker backend — see the individual
 * `CROSS-PROGRAM` comments at each call site for the specific consumer contract.
 */
internal fun atomicWriteFile(target: File, body: String) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
    temp.writeText(body)

    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

/**
 * True when [jobFolderName] is a single path segment naming a job folder under [baseDir] that
 * already has a `.metadata` directory. Per-job stores must check this BEFORE writing:
 * [atomicWriteFile] creates missing parents, so an offline write to a renamed or archived job
 * would otherwise recreate a near-empty job folder that HT could treat as a real job.
 */
internal fun isLiveJobFolder(baseDir: File, jobFolderName: String): Boolean {
    if (jobFolderName.isBlank() || jobFolderName == "." || jobFolderName == "..") return false
    if ('/' in jobFolderName || '\\' in jobFolderName) return false
    return File(baseDir, "$jobFolderName/.metadata").isDirectory
}
