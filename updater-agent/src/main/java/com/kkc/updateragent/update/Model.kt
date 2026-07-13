package com.kkc.updateragent.update

data class UpdateFeedManifest(
    val schemaVersion: String = "v1",
    val generatedAt: String? = null,
    val apps: List<AppUpdateEntry> = emptyList()
)

data class AppUpdateEntry(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkFile: String,
    val sha256: String,
    val minRequiredVersionCode: Long = 0,
    val rolloutChannel: String = "stable",
    val publishedAt: String,
    val allowDowngrade: Boolean = false
)

data class DevicePolicyConfig(
    val schemaVersion: String = "v1",
    val basePath: String? = null,
    val updaterAgentPackage: String = "com.kkc.updateragent",
    val silentInstallEnabled: Boolean = true,
    val pollIntervalMinutes: Long = 15,
    val maintenanceWindow: MaintenanceWindow? = null,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val managedPackages: List<ManagedPackagePolicy> = emptyList()
)

data class ManagedPackagePolicy(
    val packageName: String,
    val rolloutChannel: String = "stable",
    val installMode: String = "normal",
    val minRequiredVersionCode: Long = 0,
    val allowDowngrade: Boolean = false,
    val expectedSignerSha256: List<String> = emptyList()
)

data class MaintenanceWindow(
    val startHourLocal: Int,
    val endHourLocal: Int
)

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val retryBackoffMinutes: Long = 10
)

data class InstallAuditRecord(
    val timestamp: String,
    val packageName: String,
    val fromVersionCode: Long?,
    val toVersionCode: Long?,
    val result: String,
    val error: String? = null
)

data class InstallDecision(
    val shouldInstall: Boolean,
    val reason: String,
    val forced: Boolean = false
)

/**
 * AUD-12: classifies the update feed so the worker can distinguish a deliberate
 * no-update from an unavailable share or a corrupt policy/manifest, and audit/retry
 * accordingly instead of silently returning success.
 */
sealed class FeedState {
    /** Base share is not mounted/reachable — transient, retry without auditing. */
    object ShareUnavailable : FeedState()

    /** Share is present but no device policy is configured — deliberate no-op. */
    object NoPolicy : FeedState()

    /** Policy file exists but is unreadable/invalid — audit and retry. */
    object InvalidPolicy : FeedState()

    /** Policy is valid but no manifest is present — deliberate no-op. */
    object NoManifest : FeedState()

    /** Manifest file exists but is unreadable/invalid — audit and retry. */
    object InvalidManifest : FeedState()

    /** Both policy and manifest are present and valid. */
    data class Ready(
        val policy: DevicePolicyConfig,
        val manifest: UpdateFeedManifest
    ) : FeedState()
}
