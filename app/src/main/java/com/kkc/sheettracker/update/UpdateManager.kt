package com.kkc.sheettracker.update

import android.content.Context
import android.content.pm.PackageManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.kkc.sheettracker.logging.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    val isInstalled: Boolean = true
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
        // Generous: the user can back out of the installer while a large APK is still committing.
        private const val STEP_CONFIRM_GRACE_MS = 20_000L
        private const val STEP_CONFIRM_POLL_MS = 250L
    }

    private val externalApps = listOf(
        ExternalApp("com.anandmuralidhar.assimpandroid", "Assimp"),
        ExternalApp("com.example.timecard", "Hours Tracker"),
        ExternalApp("com.kkc.vnccast", "VNC Cast")
    )

    private val updateScanGate = UpdateScanGate()
    private val apkInfoCache = ConcurrentHashMap<ApkArchiveFingerprint, ApkInfo>()

    @Volatile
    var resolvedUpdatePath: String? = null
        private set

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

    fun installExternalUpdate(update: ExternalAppUpdate) {
        installApk(update.apkFile)
        pendingExternalUpdates = pendingExternalUpdates.filter { it.packageName != update.packageName }
    }

    // ── Update All: one app at a time, this app last ───────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateAllQueue: UpdateAllQueue? = null
    private var stepCheckRunning = false
    private var activityResumed = false

    /** Next step is due but the app wasn't in front; launch it on the next resume (no background starts). */
    private var launchOnResume = false

    /**
     * Installs every pending update in sequence. Each app's installer is opened only after the
     * previous app is confirmed installed at its new version (checked when this activity resumes,
     * i.e. once the system installer has closed). Sheet Tracker goes last. A cancelled or failed
     * install stops the run.
     */
    fun installAll() {
        // A run whose installer never opened (install-permission screen dismissed) is stale; replace it.
        val running = updateAllQueue
        if (running != null && (running.launched || stepCheckRunning)) return
        val selfStep = pendingUpdateApk?.let { apk ->
            getSelfApkInfo(apk)?.let { UpdateStep(activity.packageName, "KKC Sheet Tracker", apk, it.versionCode) }
        }
        val steps = updateAllSteps(selfStep, pendingExternalUpdates)
        if (steps.isEmpty()) {
            Toast.makeText(activity, "Nothing to update", Toast.LENGTH_SHORT).show()
            return
        }
        updateAllQueue = UpdateAllQueue(steps)
        launchOnResume = false
        launchCurrentStep()
    }

    /** Call from the activity's onPause: if a step's installer just opened, it's now in front. */
    fun onActivityPaused() {
        activityResumed = false
        updateAllQueue?.onPaused()
    }

    /** Call from the activity's onDestroy: stop polling and drop the run with the activity. */
    fun cancelUpdateAll() {
        mainHandler.removeCallbacksAndMessages(null)
        updateAllQueue = null
        stepCheckRunning = false
        launchOnResume = false
    }

    /** Call from the activity's onResume: confirms the current step and prompts the next one. */
    fun onActivityResumed() {
        activityResumed = true
        val queue = updateAllQueue ?: return
        if (launchOnResume) {
            launchOnResume = false
            launchCurrentStep()
            return
        }
        if (!queue.readyToConfirm || stepCheckRunning) return
        val step = queue.current ?: return finishUpdateAll(null)
        stepCheckRunning = true
        val startedAt = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                when (checkStep(getInstalledVersionCode(step.packageName), step.targetVersionCode, elapsed, STEP_CONFIRM_GRACE_MS)) {
                    StepCheck.WAIT -> mainHandler.postDelayed(this, STEP_CONFIRM_POLL_MS)
                    StepCheck.ADVANCE -> {
                        stepCheckRunning = false
                        pendingExternalUpdates = pendingExternalUpdates.filter { it.packageName != step.packageName }
                        when {
                            queue.advance() == null -> finishUpdateAll("All updates installed")
                            activityResumed -> launchCurrentStep()
                            else -> launchOnResume = true
                        }
                    }
                    StepCheck.ABORT -> {
                        stepCheckRunning = false
                        finishUpdateAll("Update All stopped: ${step.label} wasn't updated")
                    }
                }
            }
        }
        mainHandler.post(check)
    }

    private fun launchCurrentStep() {
        val queue = updateAllQueue ?: return
        val step = queue.current ?: return finishUpdateAll(null)
        Toast.makeText(activity, "Updating ${step.label} (${queue.position} of ${queue.total})", Toast.LENGTH_SHORT).show()
        AppLog.d(TAG, "Update All step ${queue.position}/${queue.total}: ${step.packageName} -> ${step.targetVersionCode}")
        installApk(step.apkFile, onLaunched = { queue.launched = true })
    }

    private fun finishUpdateAll(message: String?) {
        updateAllQueue = null
        stepCheckRunning = false
        launchOnResume = false
        if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
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
        if (!updateScanGate.tryEnter()) return false
        Thread {
            try {
                val scan = scanForUpdates(checkSelf)
                activity.runOnUiThread { applyUpdateScan(scan, checkSelf) }
            } finally {
                updateScanGate.leave()
            }
        }.apply { name = "UpdateScan"; isDaemon = true }.start()
        return false
    }

    /** Background: directory walks + APK parsing only — no Compose state writes, no dialogs. */
    private fun scanForUpdates(checkSelf: Boolean): UpdateScanResult {
        val selfUpdateDir = findUpdateDirectory()
        val selfApk = if (checkSelf && selfUpdateDir != null) {
            computeSelfUpdateApk(selfUpdateDir)
        } else {
            null
        }
        val externalUpdates = computeExternalUpdates()
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

        val apkFiles = UpdateDirectories.apks(updateDir)
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
            AppLog.d(TAG, "Reinstalling ${newestApk.name} v$newestVersionCode")
            installApk(newestApk)
        } else {
            Toast.makeText(activity, "No valid APK found", Toast.LENGTH_SHORT).show()
        }
    }

    /** Package-specific canonical feed, with a legacy fallback during tablet migration. */
    private fun resolveUpdateDir(debug: Boolean, packageName: String, recordResolved: Boolean): File? {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val customPath = prefs.getString(PREF_CUSTOM_UPDATE_PATH, null)
        val directory = UpdateDirectories.find(
            Environment.getExternalStorageDirectory(), packageName, debug, customPath
        )
        if (recordResolved) {
            resolvedUpdatePath = directory?.absolutePath
            if (customPath != null && !File(customPath).isDirectory) {
                prefs.edit().remove(PREF_CUSTOM_UPDATE_PATH).apply()
            }
        }
        return directory
    }

    private fun findUpdateDirectory(): File? {
        val isDebug = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return resolveUpdateDir(isDebug, activity.packageName, recordResolved = true)
    }

    private fun findReleaseUpdateDirectory(packageName: String): File? =
        resolveUpdateDir(debug = false, packageName = packageName, recordResolved = false)

    /** Pure: returns the newest valid self-update APK in [updateDir], or null. No state writes. */
    private fun computeSelfUpdateApk(updateDir: File): File? {
        if (!updateDir.exists() || !updateDir.isDirectory) return null
        val apkFiles = UpdateDirectories.apks(updateDir)
        if (apkFiles.isNullOrEmpty()) return null

        val currentVersionCode = getCurrentVersionCode()
        var newestApk: File? = null
        var newestVersionCode = -1L
        for (apk in apkFiles) {
            val apkVersion = getSelfApkInfo(apk)?.versionCode ?: -1L
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

    /** Each external app is scanned only in its own package feed (or the legacy shared folder). */
    private fun computeExternalUpdates(): List<ExternalAppUpdate> {
        return findExternalAppUpdates(externalApps, ::getInstalledVersionCode) { packageName ->
            val directory = findReleaseUpdateDirectory(packageName)
            if (directory == null) emptyList()
            else UpdateDirectories.apks(directory).mapNotNull { getApkInfo(it) }
        }
    }

    private fun getApkInfo(apkFile: File): ApkInfo? {
        val fingerprint = ApkArchiveFingerprint.from(apkFile)
        apkInfoCache[fingerprint]?.let { return it }

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
                ).also { apkInfoCache[fingerprint] = it }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading APK info for ${apkFile.name}", e)
            null
        }
    }

    private fun getInstalledVersionCode(packageName: String): Long? {
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
            null
        }
    }

    private fun showManualPathDialog() {
        val basePath = "${Environment.getExternalStorageDirectory().absolutePath}/"
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(basePath)
            setSelection(basePath.length)
            hint = "e.g., ${basePath}Ready Jobs/.appupdates/apps/com.kkc.sheettracker"
        }

        AlertDialog.Builder(activity)
            .setTitle("Update Folder Not Found")
            .setMessage(
                "Could not find the updates folder.\n\n" +
                    "Searched for (based on build type):\n" +
                    "• Ready Jobs/.appupdates/apps/com.kkc.sheettracker\n" +
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
        return getSelfApkInfo(apkFile)?.versionCode ?: -1L
    }

    private fun getSelfApkInfo(apkFile: File): ApkInfo? {
        return getApkInfo(apkFile)?.takeIf { it.packageName == activity.packageName }
    }

    /** [onLaunched] runs only once the system installer is actually opened for [apkFile]. */
    private fun installApk(apkFile: File, onLaunched: () -> Unit = {}) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            onRequestInstallPermission { installApk(apkFile, onLaunched) }
            return
        }
        try {
            AppLog.d(TAG, "Preparing update APK: ${apkFile.absolutePath}")
            val cacheDir = activity.cacheDir
            // Named per source file (not a fixed "update.apk") so that installing two APKs back
            // to back — e.g. Update All installing Hours Tracker then Sheet Tracker — can't have
            // the second copy clobber the first's cached file before its async install intent
            // has read it.
            val updateApk = File(cacheDir, "update_${apkFile.name}")
            if (updateApk.exists()) {
                updateApk.delete()
            }
            apkFile.copyTo(updateApk, overwrite = true)
            AppLog.d(TAG, "Copied update APK to cache: ${updateApk.absolutePath} (size: ${updateApk.length()})")

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
                AppLog.d(TAG, "Granting read URI permission to resolver package: $packageName")
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
                    AppLog.d(TAG, "Explicitly granted read URI permission to: $pkg")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not grant URI permission to $pkg: ${e.message}")
                }
            }

            AppLog.d(TAG, "Launching PackageInstaller activity with Intent")
            activity.startActivity(intent)
            onLaunched()
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            // An Update All run can't confirm a step that never opened; stop instead of stalling.
            if (updateAllQueue != null) finishUpdateAll(null)
        }
    }

}
