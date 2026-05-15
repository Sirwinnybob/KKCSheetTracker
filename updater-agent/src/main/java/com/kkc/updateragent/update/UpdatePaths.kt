package com.kkc.updateragent.update

import android.os.Environment
import java.io.File

class UpdatePaths(private val basePath: String) {
    val baseDir: File = File(basePath)
    val appUpdatesRoot: File = File(baseDir, ".appupdates")
    val appsRoot: File = File(appUpdatesRoot, "apps")
    val manifestFile: File = File(appsRoot, "manifest.json")
    val policyFile: File = File(appUpdatesRoot, "device_policy.json")

    fun appArtifactsDir(packageName: String): File {
        return File(appsRoot, packageName)
    }

    fun tabletLogFile(tabletId: String): File {
        return File(appUpdatesRoot, "$tabletId/install-log.ndjson")
    }

    fun fallbackRequiredFile(tabletId: String): File {
        return File(appUpdatesRoot, "$tabletId/updater-fallback-required.json")
    }

    companion object {
        fun defaultBasePath(): String {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val candidates = listOf("$root/Ready Jobs", "$root/SyncJobs/Ready Jobs")
            return candidates.firstOrNull { File(it).isDirectory } ?: candidates.first()
        }
    }
}
