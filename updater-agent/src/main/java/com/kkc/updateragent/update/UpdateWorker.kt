package com.kkc.updateragent.update

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant

class UpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val repository = UpdateFeedRepository()
    private val packageStateReader = PackageStateReader(appContext)
    private val decider = InstallDecider()
    private val verifier = IntegrityVerifier(appContext)
    private val installer = ApkInstaller(appContext)
    private val auditLogger = AuditLogWriter()
    private val fallbackSignalWriter = FallbackSignalWriter()
    private val deviceOwnerState = DeviceOwnerState(appContext)

    override suspend fun doWork(): Result {
        val configuredBasePath = inputData.getString(UpdateScheduler.KEY_BASE_PATH)
        val basePath = configuredBasePath ?: UpdatePaths.defaultBasePath()
        val paths = UpdatePaths(basePath)

        val policy = repository.readPolicy(paths) ?: return Result.success()
        val manifest = repository.readManifest(paths) ?: return Result.success()

        val tabletId = resolveTabletId()
        val logFile = paths.tabletLogFile(tabletId)
        val fallbackSignalFile = paths.fallbackRequiredFile(tabletId)

        if (!policy.silentInstallEnabled) {
            auditLogger.appendResult(logFile, "_system", null, null, "skipped", "silent install disabled")
            return Result.success()
        }

        if (!deviceOwnerState.isDeviceOwner()) {
            fallbackSignalWriter.writeFallbackRequired(
                fallbackSignalFile,
                "Updater agent is not enrolled as device owner"
            )
            auditLogger.appendResult(logFile, "_system", null, null, "failed", "not device owner")
            return Result.retry()
        }

        if (!MaintenanceWindowEvaluator.isOpen(policy.maintenanceWindow)) {
            auditLogger.appendResult(logFile, "_system", null, null, "skipped", "outside maintenance window")
            return Result.success()
        }

        val manifestByPackage = manifest.apps.associateBy { it.packageName }
        var anyFailure = false
        var fallbackNeeded = false

        for (pkg in policy.managedPackages) {
            val entry = manifestByPackage[pkg.packageName]
            if (entry == null || entry.rolloutChannel != pkg.rolloutChannel) {
                auditLogger.appendResult(
                    logFile,
                    pkg.packageName,
                    null,
                    null,
                    "skipped",
                    "no matching manifest entry for rollout channel"
                )
                continue
            }

            val installedVersion = packageStateReader.installedVersionCode(pkg.packageName)
            val decision = decider.decide(installedVersion, entry, pkg)
            if (!decision.shouldInstall) {
                auditLogger.appendResult(
                    logFile,
                    pkg.packageName,
                    installedVersion,
                    installedVersion,
                    "skipped",
                    decision.reason
                )
                continue
            }

            val apkFile = repository.resolveApkFile(paths, entry)
            if (!verifier.verifySha256(apkFile, entry.sha256)) {
                anyFailure = true
                auditLogger.appendResult(
                    logFile,
                    pkg.packageName,
                    installedVersion,
                    entry.versionCode,
                    "failed",
                    "sha256 mismatch"
                )
                continue
            }

            val signerCheck = verifier.verifySigner(pkg.packageName, apkFile, pkg.expectedSignerSha256)
            if (!signerCheck.first) {
                anyFailure = true
                auditLogger.appendResult(
                    logFile,
                    pkg.packageName,
                    installedVersion,
                    entry.versionCode,
                    "failed",
                    signerCheck.second ?: "signer verification failed"
                )
                continue
            }

            val outcome = installer.install(
                packageName = pkg.packageName,
                apkFile = apkFile,
                allowDowngrade = entry.allowDowngrade || pkg.allowDowngrade
            )

            if (!outcome.success) {
                anyFailure = true
                if (outcome.status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    fallbackNeeded = true
                }
                auditLogger.appendResult(
                    logFile,
                    pkg.packageName,
                    installedVersion,
                    entry.versionCode,
                    "failed",
                    outcome.message ?: "install failed"
                )
            } else {
                auditLogger.appendResult(
                    logFile,
                    pkg.packageName,
                    installedVersion,
                    entry.versionCode,
                    "installed",
                    if (decision.forced) "forced minimum version" else null
                )
            }
        }

        if (fallbackNeeded) {
            fallbackSignalWriter.writeFallbackRequired(
                fallbackSignalFile,
                "Silent install requires user action on this device/build (${Instant.now()})"
            )
        } else {
            fallbackSignalWriter.clear(fallbackSignalFile)
        }

        if (anyFailure) {
            Log.w(TAG, "One or more package updates failed")
            return Result.retry()
        }
        return Result.success()
    }

    private fun resolveTabletId(): String {
        val prefs = applicationContext.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE)
        val existing = prefs.getString("updater_tablet_id", null)
        if (!existing.isNullOrBlank()) return existing

        val androidId = runCatching {
            Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val suffix = androidId.takeLast(6).ifBlank { (System.currentTimeMillis() % 10_000).toString() }
        val generated = "${Build.MODEL}-${suffix}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        prefs.edit().putString("updater_tablet_id", generated).apply()
        return generated
    }

    companion object {
        private const val TAG = "KKCUpdaterWorker"
    }
}
