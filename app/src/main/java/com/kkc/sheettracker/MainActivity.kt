package com.kkc.sheettracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.navigation.AppNavigation
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.ui.migration.MigrationRequiredScreen
import com.kkc.sheettracker.ui.theme.KKCTheme
import com.kkc.sheettracker.update.UpdateManager
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: UpdateManager
    private lateinit var scanCoordinator: ScanCoordinator
    private lateinit var appStateStore: AppStateStore

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissions()
        updateManager = UpdateManager(this)
        updateManager.checkForUpdates()

        val prefs = getSharedPreferences("kkc_tracker", MODE_PRIVATE)
        var tabletId = prefs.getString("tablet_id", null)
        if (tabletId == null) {
            tabletId = "${Build.MODEL}-${System.currentTimeMillis() % 10000}"
            prefs.edit().putString("tablet_id", tabletId).apply()
        }

        val basePath = prefs.getString("base_path", null)
            ?: findDefaultBasePath()
        val migrationMarkerPath = File(basePath, ".appupdates/migration_complete.json")
        val migrationReady = migrationMarkerPath.isFile

        if (!migrationReady) {
            setContent {
                KKCTheme(darkTheme = prefs.getBoolean("dark_theme", false)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MigrationRequiredScreen(
                            basePath = basePath,
                            markerPath = migrationMarkerPath.absolutePath,
                            onRetry = { recreate() },
                            onExit = { finishAffinity() }
                        )
                    }
                }
            }
            return
        }

        val baseDir = File(basePath)
        val jobRepository = JobRepository(baseDir)
        val progressStore = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(filesDir, "state")
        )
        scanCoordinator = ScanCoordinator(baseDir, jobRepository)
        appStateStore = AppStateStore(scanCoordinator, progressStore)
        scanCoordinator.refresh(RefreshReason.APP_START, force = true)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
            var workMode by remember {
                mutableStateOf(
                    WorkMode.fromStored(prefs.getString("work_mode", null))
                )
            }

            KKCTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = AppStateFeatureFlags(prefs, BuildConfig.DEBUG),
                        tabletId = tabletId,
                        basePath = basePath,
                        isDebugBuild = BuildConfig.DEBUG,
                        isDarkTheme = isDarkTheme,
                        workMode = workMode,
                        onThemeChanged = { dark ->
                            isDarkTheme = dark
                            prefs.edit().putBoolean("dark_theme", dark).apply()
                        },
                        onWorkModeChanged = { mode ->
                            workMode = mode
                            prefs.edit().putString("work_mode", mode.name).apply()
                        },
                        onReinstallLatest = { updateManager.reinstallLatest() },
                        onBasePathChanged = { newPath ->
                            prefs.edit().putString("base_path", newPath).apply()
                            recreate()
                        },
                        onTabletIdChanged = { newId ->
                            prefs.edit().putString("tablet_id", newId).apply()
                            recreate()
                        }
                    )

                    if (updateManager.pendingUpdateApk != null) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Update Available") },
                            text = { Text("A new version of KKC Sheet Tracker is available. Install now?") },
                            confirmButton = {
                                Button(onClick = { updateManager.installPendingUpdate() }) {
                                    Text("Install")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::scanCoordinator.isInitialized) {
            scanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
        }
    }

    private fun findDefaultBasePath(): String {
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath
        val candidates = listOf(
            "$externalRoot/Ready Jobs",
            "$externalRoot/SyncJobs/Ready Jobs"
        )
        for (path in candidates) {
            if (File(path).isDirectory) return path
        }
        return candidates.first()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }
}
