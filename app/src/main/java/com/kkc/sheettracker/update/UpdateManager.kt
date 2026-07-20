package com.kkc.sheettracker.update

import android.content.Context
import android.content.pm.PackageManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

data class ExternalApp(
    val packageName: String,
    val appName: String
)

data class ExternalAppUpdate(
    val packageName: String,
    val appName: String,
    val apkFile: File,
    val versionCode: Long,
    val versionName: String,
    val canSkip: Boolean
)

data class ApkInfo(
    val file: File,
    val packageName: String,
    val versionCode: Long,
    val versionName: String
)

class UpdateManager(
    private val activity: Activity,
    private val onRequestInstallPermission: (onGranted: () -> Unit) -> Unit
) {

    companion object {
        private const val TAG = "UpdateManager"
        private val JOB_FOLDER_NAMES = arrayOf("Ready Jobs", "Jobs", "JOBS")
        private const val PREFS_NAME = "UpdateManagerPrefs"
        private const val PREF_CUSTOM_UPDATE_PATH = "custom_update_path"
    }

    private val externalApps = listOf(
        ExternalApp("com.anandmuralidhar.assimpandroid", "Assimp"),
        ExternalApp("com.example.timecard", "Hours Tracker")
    )

    private val skippedExternalPackagesInSession = mutableSetOf<String>()

    @Volatile
    var resolvedUpdatePath: String? = null
        private set

    var isSilentUpdateSupported by mutableStateOf(false)

    var basePath: String? = null
    var tabletId: String? = null

    var pendingUpdateApk by mutableStateOf<File?>(null)
        private set

    var pendingExternalUpdates by mutableStateOf<List<ExternalAppUpdate>>(emptyList())
        private set

    val pendingExternalUpdate: ExternalAppUpdate?
        get() = pendingExternalUpdates.firstOrNull()

    fun installPendingUpdate() {
        pendingUpdateApk?.let { installApk(it) }
    }

    fun installPendingUpdateSilently() {
        try {
            val intent = Intent("com.kkc.updateragent.TRIGGER_UPDATE").apply {
                setPackage("com.kkc.updateragent")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            activity.sendBroadcast(intent)
            Toast.makeText(activity, "Silent update triggered. App will close shortly.", Toast.LENGTH_LONG).show()

            val currentBasePath = basePath
            val currentTabletId = tabletId
            if (currentBasePath != null && currentTabletId != null) {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postDelayed({
                    val fallbackFile = File(currentBasePath, ".appupdates/$currentTabletId/updater-fallback-required.json")
                    if (fallbackFile.isFile) {
                        Log.w(TAG, "Silent update failed/fell back. Reverting to legacy prompt.")
                        isSilentUpdateSupported = false
                    }
                }, 5000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send update broadcast", e)
            Toast.makeText(activity, "Failed to trigger silent update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun installExternalUpdate(update: ExternalAppUpdate) {
        installApk(update.apkFile)
        pendingExternalUpdates = pendingExternalUpdates.filter { it.packageName != update.packageName }
    }

    fun skipExternalUpdate(update: ExternalAppUpdate) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        prefs.edit().putLong("skipped_version_${update.packageName}", update.versionCode).apply()
        skippedExternalPackagesInSession.add(update.packageName)
        pendingExternalUpdates = pendingExternalUpdates.filter { it.packageName != update.packageName }
    }

    private data class UpdateScanResult(
        val selfUpdateDir: File?,
        val selfApk: File?,
        val externalUpdates: List<ExternalAppUpdate>
    )

    /**
     * Runs the storage walk + APK-archive parsing off the main thread (each foreground triggered a
     * synchronous sweep of getPackageArchiveInfo over every APK — tens of ms each, worse on
     * networked/SD storage), then applies Compose state + any dialog back on the main thread.
     * The boolean return is retained for source compatibility but is always false now (no caller
     * uses it); state changes land asynchronously via applyUpdateScan.
     */
    fun checkForUpdates(checkSelf: Boolean = true): Boolean {
        Thread {
            val scan = scanForUpdates(checkSelf)
            activity.runOnUiThread { applyUpdateScan(scan, checkSelf) }
        }.apply { name = "UpdateScan"; isDaemon = true }.start()
        return false
    }

    /** Background: directory walks + APK parsing only — no Compose state writes, no dialogs. */
    private fun scanForUpdates(checkSelf: Boolean): UpdateScanResult {
        val selfUpdateDir = findUpdateDirectory()
        val releaseUpdateDir = findReleaseUpdateDirectory()
        val selfApk = if (checkSelf && selfUpdateDir != null) {
            computeSelfUpdateApk(selfUpdateDir)
        } else {
            null
        }
        val externalUpdates = if (releaseUpdateDir != null) {
            computeExternalUpdates(releaseUpdateDir)
        } else {
            emptyList()
        }
        return UpdateScanResult(selfUpdateDir, selfApk, externalUpdates)
    }

    /** Main thread: apply the scan results (Compose state + manual-path dialog). */
    private fun applyUpdateScan(scan: UpdateScanResult, checkSelf: Boolean) {
        // Preserve prior behavior: pendingUpdateApk is only ever set, never cleared by a scan.
        scan.selfApk?.let { pendingUpdateApk = it }
        pendingExternalUpdates = scan.externalUpdates
        if (checkSelf && scan.selfUpdateDir == null) {
            showManualPathDialog()
        }
    }

    fun reinstallLatest() {
        val updateDir = resolvedUpdatePath?.let { File(it) } ?: findUpdateDirectory()
        if (updateDir == null || !updateDir.exists()) {
            Toast.makeText(activity, "Update folder not found", Toast.LENGTH_SHORT).show()
            return
        }

        val apkFiles = updateDir.listFiles { _, name -> name.lowercase().endsWith(".apk") }
        if (apkFiles.isNullOrEmpty()) {
            Toast.makeText(activity, "No APK files found", Toast.LENGTH_SHORT).show()
            return
        }

        var newestApk: File? = null
        var newestVersionCode = -1L
        for (apk in apkFiles) {
            val apkVersion = getApkVersionCode(apk)
            if (apkVersion > newestVersionCode ||
                (apkVersion == newestVersionCode && apkVersion >= 0 &&
                    (newestApk == null || apk.lastModified() > newestApk.lastModified()))
            ) {
                newestVersionCode = apkVersion
                newestApk = apk
            }
        }

        if (newestApk != null) {
            Log.d(TAG, "Reinstalling ${newestApk.name} v$newestVersionCode")
            installApk(newestApk)
        } else {
            Toast.makeText(activity, "No valid APK found", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shared custom-path read + JOB_FOLDER_NAMES walk for both finders.
     * [subfolders] selects the update-folder set (debug `.Testing_Updates` vs release `.Updates`/
     * `Updates`). When [recordResolved] is true (self-update path) the match is stored in
     * resolvedUpdatePath and a stale custom path is cleared — matching the prior
     * findUpdateDirectory behavior; the release finder passes false (record/clear neither).
     */
    private fun resolveUpdateDir(subfolders: Array<String>, recordResolved: Boolean): File? {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val customPath = prefs.getString(PREF_CUSTOM_UPDATE_PATH, null)
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) {
                if (recordResolved) resolvedUpdatePath = customPath
                return customDir
            } else if (recordResolved) {
                prefs.edit().remove(PREF_CUSTOM_UPDATE_PATH).apply()
            }
        }

        val storageRoot = Environment.getExternalStorageDirectory()
        for (jobFolder in JOB_FOLDER_NAMES) {
            val jobDir = File(storageRoot, jobFolder)
            if (!jobDir.exists() || !jobDir.isDirectory) continue
            for (updateSubfolder in subfolders) {
                val updateDir = File(jobDir, updateSubfolder)
                if (updateDir.exists() && updateDir.isDirectory) {
                    if (recordResolved) resolvedUpdatePath = updateDir.absolutePath
                    return updateDir
                }
            }
        }
        return null
    }

    private fun findUpdateDirectory(): File? {
        val isDebug = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val subfolders = if (isDebug) arrayOf(".Testing_Updates") else arrayOf(".Updates", "Updates")
        return resolveUpdateDir(subfolders, recordResolved = true)
    }

    private fun findReleaseUpdateDirectory(): File? =
        resolveUpdateDir(arrayOf(".Updates", "Updates"), recordResolved = false)

    /** Pure: returns the newest valid self-update APK in [updateDir], or null. No state writes. */
    private fun computeSelfUpdateApk(updateDir: File): File? {
        if (!updateDir.exists() || !updateDir.isDirectory) return null
        val apkFiles = updateDir.listFiles { _, name -> name.lowercase().endsWith(".apk") }
        if (apkFiles.isNullOrEmpty()) return null

        val currentVersionCode = getCurrentVersionCode()
        var newestApk: File? = null
        var newestVersionCode = -1L
        for (apk in apkFiles) {
            val apkVersion = getApkVersionCode(apk)
            if (apkVersion > currentVersionCode &&
                (apkVersion > newestVersionCode ||
                    (apkVersion == newestVersionCode &&
                        (newestApk == null || apk.lastModified() > newestApk.lastModified())))
            ) {
                newestVersionCode = apkVersion
                newestApk = apk
            }
        }
        return newestApk
    }

    /** Pure: returns the list of pending external-app updates in [updateDir]. No state writes. */
    private fun computeExternalUpdates(updateDir: File): List<ExternalAppUpdate> {
        if (!updateDir.exists() || !updateDir.isDirectory) {
            return emptyList()
        }
        val apkFiles = updateDir.listFiles { _, name -> name.lowercase().endsWith(".apk") }
        if (apkFiles.isNullOrEmpty()) {
            return emptyList()
        }

        val newestMap = mutableMapOf<String, ApkInfo>()
        for (apk in apkFiles) {
            val info = getApkInfo(apk) ?: continue
            val existing = newestMap[info.packageName]
            if (existing == null || 
                info.versionCode > existing.versionCode ||
                (info.versionCode == existing.versionCode && apk.lastModified() > existing.file.lastModified())
            ) {
                newestMap[info.packageName] = info
            }
        }

        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val externalList = mutableListOf<ExternalAppUpdate>()
        for (app in externalApps) {
            val installedVersion = getInstalledVersionCode(app.packageName)
            if (installedVersion == -1L) {
                continue
            }

            if (skippedExternalPackagesInSession.contains(app.packageName)) {
                continue
            }

            val appInfo = newestMap[app.packageName]
            if (appInfo != null && appInfo.versionCode > installedVersion) {
                val persistedSkippedVersion = prefs.getLong("skipped_version_${app.packageName}", -1L)
                val canSkip = appInfo.versionCode != persistedSkippedVersion
                
                externalList.add(
                    ExternalAppUpdate(
                        packageName = app.packageName,
                        appName = app.appName,
                        apkFile = appInfo.file,
                        versionCode = appInfo.versionCode,
                        versionName = appInfo.versionName,
                        canSkip = canSkip
                    )
                )
            }
        }
        return externalList
    }

    private fun getApkInfo(apkFile: File): ApkInfo? {
        return try {
            val pInfo = activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (pInfo != null) {
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
                else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
                ApkInfo(
                    file = apkFile,
                    packageName = pInfo.packageName,
                    versionCode = code,
                    versionName = pInfo.versionName ?: ""
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading APK info for ${apkFile.name}", e)
            null
        }
    }

    private fun getInstalledVersionCode(packageName: String): Long {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
            else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if package $packageName is installed", e)
            -1L
        }
    }

    private fun showManualPathDialog() {
        val basePath = "${Environment.getExternalStorageDirectory().absolutePath}/"
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(basePath)
            setSelection(basePath.length)
            hint = "e.g., ${basePath}Ready Jobs/.Updates"
        }

        AlertDialog.Builder(activity)
            .setTitle("Update Folder Not Found")
            .setMessage(
                "Could not find the updates folder.\n\n" +
                    "Searched for (based on build type):\n" +
                    "• Ready Jobs/.Updates\n" +
                    "• Ready Jobs/.Testing_Updates\n" +
                    "• Jobs/.Updates\n\n" +
                    "Please enter the full path to your updates folder:"
            )
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val enteredPath = input.text.toString().trim()
                if (enteredPath.isNotEmpty()) {
                    val customDir = File(enteredPath)
                    if (customDir.exists() && customDir.isDirectory) {
                        activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                            .edit().putString(PREF_CUSTOM_UPDATE_PATH, enteredPath).apply()
                        resolvedUpdatePath = enteredPath
                        checkForUpdates(checkSelf = true)
                    } else {
                        Toast.makeText(activity, "Folder not found: $enteredPath", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
            else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version code", e)
            -1L
        }
    }

    private fun getApkVersionCode(apkFile: File): Long {
        return try {
            val pInfo = activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (pInfo != null) {
                if (pInfo.packageName != activity.packageName) return -1L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
                else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } else -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error reading APK version", e)
            -1L
        }
    }

    private fun installApk(apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            onRequestInstallPermission { installApk(apkFile) }
            return
        }
        try {
            Log.d(TAG, "Preparing update APK: ${apkFile.absolutePath}")
            val cacheDir = activity.cacheDir
            val updateApk = File(cacheDir, "update.apk")
            if (updateApk.exists()) {
                updateApk.delete()
            }
            apkFile.copyTo(updateApk, overwrite = true)
            Log.d(TAG, "Copied update APK to cache: ${updateApk.absolutePath} (size: ${updateApk.length()})")

            val apkUri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", updateApk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pm = activity.packageManager
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }

            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo.packageName
                Log.d(TAG, "Granting read URI permission to resolver package: $packageName")
                activity.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val extraPackages = listOf(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.google.android.providers.media.module",
                "com.android.providers.media.module"
            )
            for (pkg in extraPackages) {
                try {
                    activity.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    Log.d(TAG, "Explicitly granted read URI permission to: $pkg")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not grant URI permission to $pkg: ${e.message}")
                }
            }

            Log.d(TAG, "Launching PackageInstaller activity with Intent")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}
