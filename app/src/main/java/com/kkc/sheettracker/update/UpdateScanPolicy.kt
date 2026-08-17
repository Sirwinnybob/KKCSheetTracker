package com.kkc.sheettracker.update

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class ApkArchiveFingerprint(
    val absolutePath: String,
    val length: Long,
    val lastModified: Long
) {
    companion object {
        fun from(file: File): ApkArchiveFingerprint = ApkArchiveFingerprint(
            absolutePath = file.absolutePath,
            length = file.length(),
            lastModified = file.lastModified()
        )
    }
}

internal class UpdateScanGate {
    private val active = AtomicBoolean(false)

    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    fun leave() {
        active.set(false)
    }
}
