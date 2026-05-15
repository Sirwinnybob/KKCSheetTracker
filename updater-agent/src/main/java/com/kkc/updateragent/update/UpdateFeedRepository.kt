package com.kkc.updateragent.update

import java.io.File

class UpdateFeedRepository {

    fun readPolicy(paths: UpdatePaths): DevicePolicyConfig? {
        val policyFile = paths.policyFile
        if (!policyFile.isFile) return null
        return policyFile.reader().use { Json.gson.fromJson(it, DevicePolicyConfig::class.java) }
    }

    fun readManifest(paths: UpdatePaths): UpdateFeedManifest? {
        val manifestFile = paths.manifestFile
        if (!manifestFile.isFile) return null
        return manifestFile.reader().use { Json.gson.fromJson(it, UpdateFeedManifest::class.java) }
    }

    fun resolveApkFile(paths: UpdatePaths, entry: AppUpdateEntry): File {
        return File(paths.appArtifactsDir(entry.packageName), entry.apkFile)
    }
}
