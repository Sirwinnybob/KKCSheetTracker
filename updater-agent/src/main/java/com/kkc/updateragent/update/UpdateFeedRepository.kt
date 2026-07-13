package com.kkc.updateragent.update

import java.io.File

class UpdateFeedRepository {

    fun readPolicy(paths: UpdatePaths): DevicePolicyConfig? {
        val policyFile = paths.policyFile
        if (!policyFile.isFile) return null
        val raw = try {
            policyFile.reader().use { Json.gson.fromJson(it, RawDevicePolicyConfig::class.java) }
        } catch (_: Exception) {
            null
        } ?: return null
        val config = sanitizePolicy(raw)
        // AUD-02: fail closed on a tampered/malformed policy rather than authorizing an
        // unsigned or ambiguous managed package.
        return if (isPolicyValid(config)) config else null
    }

    fun readManifest(paths: UpdatePaths): UpdateFeedManifest? {
        val manifestFile = paths.manifestFile
        if (!manifestFile.isFile) return null
        val raw = try {
            manifestFile.reader().use { Json.gson.fromJson(it, RawUpdateFeedManifest::class.java) }
        } catch (_: Exception) {
            null
        } ?: return null
        val manifest = sanitizeManifest(raw)
        return if (isManifestValid(manifest)) manifest else null
    }

    /**
     * Resolve the on-disk APK for an entry, enforcing that the artifact stays inside
     * `.appupdates/apps/<packageName>` after canonical resolution. AUD-02: a raw manifest
     * `apkFile` containing `..` segments (or absolute paths) must not escape the artifact
     * directory. Returns null when the path is unsafe or blank.
     */
    fun resolveApkFile(paths: UpdatePaths, entry: AppUpdateEntry): File? {
        if (entry.apkFile.isBlank()) return null
        val artifactsDir = paths.appArtifactsDir(entry.packageName)
        val candidate = File(artifactsDir, entry.apkFile)
        val canonicalDir = try { artifactsDir.canonicalFile } catch (_: Exception) { return null }
        val canonicalCandidate = try { candidate.canonicalFile } catch (_: Exception) { return null }
        if (canonicalCandidate == canonicalDir) return null
        val contained = canonicalCandidate.path == canonicalDir.path ||
            canonicalCandidate.path.startsWith(canonicalDir.path + File.separator)
        return if (contained) candidate else null
    }

    /**
     * AUD-02 policy validation: every managed package must have a non-blank, unique
     * package name and a non-empty signer allowlist. Any violation rejects the whole
     * policy so a compromised feed cannot slip in an unsigned/duplicate entry.
     */
    private fun isPolicyValid(config: DevicePolicyConfig): Boolean {
        val seen = HashSet<String>()
        for (pkg in config.managedPackages) {
            val name = pkg.packageName.trim()
            if (name.isEmpty()) return false
            if (!seen.add(name)) return false
            val signers = pkg.expectedSignerSha256.map { it.trim() }.filter { it.isNotEmpty() }
            if (signers.isEmpty()) return false
        }
        return true
    }

    /**
     * AUD-02 manifest validation: reject blank or duplicate app package entries before the
     * worker builds its `packageName -> entry` map (associateBy would silently drop dupes).
     */
    private fun isManifestValid(manifest: UpdateFeedManifest): Boolean {
        val seen = HashSet<String>()
        for (app in manifest.apps) {
            val name = app.packageName.trim()
            if (name.isEmpty()) return false
            if (!seen.add(name)) return false
        }
        return true
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
