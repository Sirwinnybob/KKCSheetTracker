package com.kkc.updateragent.update

import java.io.File

class UpdateFeedRepository {

    fun readPolicy(paths: UpdatePaths): DevicePolicyConfig? {
        val policyFile = paths.policyFile
        if (!policyFile.isFile) return null
        return try {
            policyFile.reader().use { Json.gson.fromJson(it, RawDevicePolicyConfig::class.java) }?.let { sanitizePolicy(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun readManifest(paths: UpdatePaths): UpdateFeedManifest? {
        val manifestFile = paths.manifestFile
        if (!manifestFile.isFile) return null
        return try {
            manifestFile.reader().use { Json.gson.fromJson(it, RawUpdateFeedManifest::class.java) }?.let { sanitizeManifest(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveApkFile(paths: UpdatePaths, entry: AppUpdateEntry): File {
        return File(paths.appArtifactsDir(entry.packageName), entry.apkFile)
    }

    private fun sanitizePolicy(config: RawDevicePolicyConfig): DevicePolicyConfig {
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

    private fun sanitizeManifest(manifest: RawUpdateFeedManifest): UpdateFeedManifest {
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

    private data class RawUpdateFeedManifest(
        val schemaVersion: String? = null,
        val generatedAt: String? = null,
        val apps: List<RawAppUpdateEntry>? = null
    )

    private data class RawAppUpdateEntry(
        val packageName: String? = null,
        val versionCode: Long = 0,
        val versionName: String? = null,
        val apkFile: String? = null,
        val sha256: String? = null,
        val minRequiredVersionCode: Long = 0,
        val rolloutChannel: String? = null,
        val publishedAt: String? = null,
        val allowDowngrade: Boolean = false
    )

    private data class RawDevicePolicyConfig(
        val schemaVersion: String? = null,
        val basePath: String? = null,
        val updaterAgentPackage: String? = null,
        val silentInstallEnabled: Boolean = true,
        val pollIntervalMinutes: Long = 15,
        val maintenanceWindow: MaintenanceWindow? = null,
        val retryPolicy: RawRetryPolicy? = null,
        val managedPackages: List<RawManagedPackagePolicy>? = null
    )

    private data class RawManagedPackagePolicy(
        val packageName: String? = null,
        val rolloutChannel: String? = null,
        val installMode: String? = null,
        val minRequiredVersionCode: Long = 0,
        val allowDowngrade: Boolean = false,
        val expectedSignerSha256: List<String>? = null
    )

    private data class RawRetryPolicy(
        val maxAttempts: Int = 3,
        val retryBackoffMinutes: Long = 10
    )
}
