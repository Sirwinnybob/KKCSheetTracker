package com.kkc.sheettracker.update

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kkc.sheettracker.logging.AppLog
import java.io.File

data class FallbackPolicy(
    val updaterAgentPackage: String? = null,
    val silentInstallEnabled: Boolean? = null
)

class DeviceOwnerUpdateFallback(private val context: Context) {
    private val gson = Gson()

    companion object {
        private const val TAG = "DeviceOwnerUpdateFallback"
    }

    fun shouldUseLegacyPrompt(basePath: String, tabletId: String): Boolean {
        val appUpdatesDir = File(basePath, ".appupdates")
        val policyFile = File(appUpdatesDir, "device_policy.json")
        if (!policyFile.isFile) {
            AppLog.d(TAG, "shouldUseLegacyPrompt=true: policyFile is not a file: ${policyFile.absolutePath}")
            return true
        }

        val policy = runCatching {
            policyFile.reader().use { gson.fromJson(it, FallbackPolicy::class.java) }
        }.getOrElse { error ->
            Log.e(TAG, "shouldUseLegacyPrompt=true: Failed to parse policy file", error)
            return true
        }

        val silentEnabled = policy.silentInstallEnabled ?: false
        if (!silentEnabled) {
            AppLog.d(TAG, "shouldUseLegacyPrompt=true: silentInstallEnabled is false")
            return true
        }

        val updaterPackage = policy.updaterAgentPackage?.ifBlank { null } ?: "com.kkc.updateragent"
        if (!isPackageInstalled(updaterPackage)) {
            AppLog.d(TAG, "shouldUseLegacyPrompt=true: updaterPackage not installed: $updaterPackage")
            return true
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(updaterPackage)) {
            AppLog.d(TAG, "shouldUseLegacyPrompt=true: updaterPackage is not device owner: $updaterPackage")
            return true
        }

        val fallbackSignal = File(appUpdatesDir, "$tabletId/updater-fallback-required.json")
        if (fallbackSignal.isFile) {
            AppLog.d(TAG, "shouldUseLegacyPrompt=true: fallbackSignal file exists: ${fallbackSignal.absolutePath}")
            return true
        }

        AppLog.d(TAG, "shouldUseLegacyPrompt=false: All checks passed. Using silent update flow.")
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
