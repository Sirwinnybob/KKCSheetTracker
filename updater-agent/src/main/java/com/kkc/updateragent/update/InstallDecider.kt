package com.kkc.updateragent.update

class InstallDecider {
    fun decide(
        installedVersionCode: Long?,
        update: AppUpdateEntry,
        packagePolicy: ManagedPackagePolicy
    ): InstallDecision {
        val installed = installedVersionCode ?: -1L
        val requiredVersion = maxOf(update.minRequiredVersionCode, packagePolicy.minRequiredVersionCode)
        val forced = installed >= 0 && installed < requiredVersion

        if (installed < 0) {
            return InstallDecision(true, "Package not installed", forced = false)
        }

        if (update.versionCode > installed) {
            return InstallDecision(true, "Newer version available", forced = forced)
        }

        val allowDowngrade = update.allowDowngrade || packagePolicy.allowDowngrade
        if (allowDowngrade && update.versionCode < installed) {
            return InstallDecision(true, "Rollback allowed by policy", forced = forced)
        }

        if (forced && update.versionCode >= requiredVersion) {
            return InstallDecision(true, "Forced minimum version policy", forced = true)
        }

        return InstallDecision(false, "Already on latest allowed version", forced = false)
    }
}
