package com.kkc.updateragent.update

import java.io.File

class UpdateFeedRepository {

    fun readPolicy(paths: UpdatePaths): DevicePolicyConfig? {
        val policyFile = paths.policyFile
        if (!policyFile.isFile) return null
        return try {
            policyFile.reader().use { Json.gson.fromJson(it, DevicePolicyConfig::class.java) }?.let { sanitizePolicy(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun readManifest(paths: UpdatePaths): UpdateFeedManifest? {
        val manifestFile = paths.manifestFile
        if (!manifestFile.isFile) return null
        return try {
            manifestFile.reader().use { Json.gson.fromJson(it, UpdateFeedManifest::class.java) }?.let { sanitizeManifest(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveApkFile(paths: UpdatePaths, entry: AppUpdateEntry): File {
        return File(paths.appArtifactsDir(entry.packageName), entry.apkFile)
    }

    private fun sanitizePolicy(config: DevicePolicyConfig?): DevicePolicyConfig {
        if (config == null) return DevicePolicyConfig()
        return DevicePolicyConfig(
            schemaVersion = config.schemaVersion ?: "v1",
            basePath = config.basePath,
            updaterAgentPackage = config.updaterAgentPackage ?: "com.kkc.updateragent",
            silentInstallEnabled = config.silentInstallEnabled,
            pollIntervalMinutes = config.pollIntervalMinutes,
            maintenanceWindow = config.maintenanceWindow,
            retryPolicy = config.retryPolicy?.let { rp ->
                RetryPolicy(
                    maxAttempts = rp.maxAttempts,
                    retryBackoffMinutes = rp.retryBackoffMinutes
                )
            } ?: RetryPolicy(),
            managedPackages = (config.managedPackages ?: emptyList()).map { pkg ->
                ManagedPackagePolicy(
                    packageName = pkg.packageName ?: "",
                    rolloutChannel = pkg.rolloutChannel ?: "stable",
                    installMode = pkg.installMode ?: "normal",
                    minRequiredVersionCode = pkg.minRequiredVersionCode,
                    allowDowngrade = pkg.allowDowngrade,
                    expectedSignerSha256 = pkg.expectedSignerSha256 ?: emptyList()
                )
            }
        )
    }

    private fun sanitizeManifest(manifest: UpdateFeedManifest?): UpdateFeedManifest {
        if (manifest == null) return UpdateFeedManifest()
        return UpdateFeedManifest(
            schemaVersion = manifest.schemaVersion ?: "v1",
            generatedAt = manifest.generatedAt,
            apps = (manifest.apps ?: emptyList()).map { app ->
                AppUpdateEntry(
                    packageName = app.packageName ?: "",
                    versionCode = app.versionCode,
                    versionName = app.versionName ?: "",
                    apkFile = app.apkFile ?: "",
                    sha256 = app.sha256 ?: "",
                    minRequiredVersionCode = app.minRequiredVersionCode,
                    rolloutChannel = app.rolloutChannel ?: "stable",
                    publishedAt = app.publishedAt ?: "",
                    allowDowngrade = app.allowDowngrade
                )
            }
        )
    }
}
