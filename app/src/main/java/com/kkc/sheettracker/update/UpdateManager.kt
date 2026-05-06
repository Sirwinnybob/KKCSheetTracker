package com.kkc.sheettracker.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

class UpdateManager(private val activity: Activity) {

    companion object {
        private const val TAG = "UpdateManager"
        private val JOB_FOLDER_NAMES = arrayOf("Ready Jobs", "Jobs", "JOBS")
        private const val PREFS_NAME = "UpdateManagerPrefs"
        private const val PREF_CUSTOM_UPDATE_PATH = "custom_update_path"
    }

    var resolvedUpdatePath: String? = null
        private set

    var pendingUpdateApk by mutableStateOf<File?>(null)
        private set

    fun installPendingUpdate() {
        pendingUpdateApk?.let { installApk(it) }
    }

    fun checkForUpdates(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestStoragePermission()
            return true
        }

        if (!activity.packageManager.canRequestPackageInstalls()) {
            requestInstallPermission()
            return true
        }

        val updateDir = findUpdateDirectory()
        if (updateDir == null) {
            showManualPathDialog()
            return true
        }

        return checkForUpdatesInDirectory(updateDir)
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
                    (newestApk == null || apk.lastModified() > newestApk!!.lastModified()))
            ) {
                newestVersionCode = apkVersion
                newestApk = apk
            }
        }

        if (newestApk != null) {
            Log.d(TAG, "Reinstalling ${newestApk!!.name} v$newestVersionCode")
            installApk(newestApk!!)
        } else {
            Toast.makeText(activity, "No valid APK found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findUpdateDirectory(): File? {
        val storageRoot = Environment.getExternalStorageDirectory()

        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val customPath = prefs.getString(PREF_CUSTOM_UPDATE_PATH, null)
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) {
                resolvedUpdatePath = customPath
                return customDir
            } else {
                prefs.edit().remove(PREF_CUSTOM_UPDATE_PATH).apply()
            }
        }

        val isDebug = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        for (jobFolder in JOB_FOLDER_NAMES) {
            val jobDir = File(storageRoot, jobFolder)
            if (!jobDir.exists() || !jobDir.isDirectory) continue
            val foldersToCheck = if (isDebug) arrayOf(".Testing_Updates") else arrayOf(".Updates", "Updates")
            for (updateSubfolder in foldersToCheck) {
                val updateDir = File(jobDir, updateSubfolder)
                if (updateDir.exists() && updateDir.isDirectory) {
                    resolvedUpdatePath = updateDir.absolutePath
                    return updateDir
                }
            }
        }
        return null
    }

    private fun checkForUpdatesInDirectory(updateDir: File): Boolean {
        if (!updateDir.exists() || !updateDir.isDirectory) return false
        val apkFiles = updateDir.listFiles { _, name -> name.lowercase().endsWith(".apk") }
        if (apkFiles.isNullOrEmpty()) return false

        val currentVersionCode = getCurrentVersionCode()
        var newestApk: File? = null
        var newestVersionCode = -1L
        for (apk in apkFiles) {
            val apkVersion = getApkVersionCode(apk)
            if (apkVersion > currentVersionCode &&
                (apkVersion > newestVersionCode ||
                    (apkVersion == newestVersionCode &&
                        (newestApk == null || apk.lastModified() > newestApk!!.lastModified())))
            ) {
                newestVersionCode = apkVersion
                newestApk = apk
            }
        }

        if (newestApk != null) {
            pendingUpdateApk = newestApk
            return true
        }
        return false
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
                        checkForUpdatesInDirectory(customDir)
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
            requestInstallPermission()
            return
        }
        try {
            val apkUri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("InlinedApi")
    private fun requestStoragePermission() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage("To check for updates, this app needs access to manage all files. Please grant this permission in the next screen.")
            .setPositiveButton("OK") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        addCategory("android.intent.category.DEFAULT")
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestInstallPermission() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage("To perform updates, this app needs permission to install unknown apps. Please grant this permission in the next screen.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }
}
