package com.kkc.sheettracker.update

import android.content.Context
import com.google.gson.Gson
import java.io.File

data class FallbackPolicy(
    val updaterAgentPackage: String? = null,
    val silentInstallEnabled: Boolean? = null
)

class DeviceOwnerUpdateFallback(private val context: Context) {
    private val gson = Gson()

    fun shouldUseLegacyPrompt(basePath: String, tabletId: String): Boolean {
        val appUpdatesDir = File(basePath, ".appupdates")
        val policyFile = File(appUpdatesDir, "device_policy.json")
        if (!policyFile.isFile) {
            return true
        }

        val policy = runCatching {
            policyFile.reader().use { gson.fromJson(it, FallbackPolicy::class.java) }
        }.getOrNull() ?: return true

        val silentEnabled = policy.silentInstallEnabled ?: false
        if (!silentEnabled) {
            return true
        }

        val updaterPackage = policy.updaterAgentPackage?.ifBlank { null } ?: "com.kkc.updateragent"
        if (!isPackageInstalled(updaterPackage)) {
            return true
        }

        val fallbackSignal = File(appUpdatesDir, "$tabletId/updater-fallback-required.json")
        if (fallbackSignal.isFile) {
            return true
        }

        return false
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
