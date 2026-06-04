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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.kkc.sheettracker.clock.ClockInNotificationContract
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.navigation.AppNavigation
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.sync.DataStoreSyncthingPreferencesStore
import com.kkc.sheettracker.sync.SyncthingInstallResolver
import com.kkc.sheettracker.sync.SyncthingIntentConfig
import com.kkc.sheettracker.sync.SyncthingRuntimeConfig
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.sync.SyncthingSupervisor
import androidx.lifecycle.lifecycleScope
import com.kkc.sheettracker.ui.migration.MigrationRequiredScreen
import com.kkc.sheettracker.ui.theme.KKCTheme
import com.kkc.sheettracker.update.DeviceOwnerUpdateFallback
import com.kkc.sheettracker.update.UpdateManager
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: UpdateManager
    private lateinit var scanCoordinator: ScanCoordinator
    private lateinit var appStateStore: AppStateStore
    private lateinit var syncthingSupervisor: SyncthingSupervisor
    private lateinit var clockInState: ClockInState
    private lateinit var supplySubscriptionManager: SupplySubscriptionManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    private companion object {
        const val EXTRA_VIEW_ONLY_MODE = "extra_view_only_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissions()

        val prefs = getSharedPreferences("kkc_tracker", MODE_PRIVATE)
        var tabletId = prefs.getString("tablet_id", null)
        if (tabletId == null) {
            tabletId = "${Build.MODEL}-${System.currentTimeMillis() % 10000}"
            prefs.edit().putString("tablet_id", tabletId).apply()
        }

        val basePath = prefs.getString("base_path", null)
            ?: findDefaultBasePath()
        val useLegacyUpdatePrompt = DeviceOwnerUpdateFallback(this)
            .shouldUseLegacyPrompt(basePath = basePath, tabletId = tabletId)
        updateManager = UpdateManager(this)
        if (useLegacyUpdatePrompt) {
            updateManager.checkForUpdates()
        }
        val migrationMarkerPath = File(basePath, ".appupdates/migration_complete.json")
        val migrationReady = migrationMarkerPath.isFile
        val persistedViewOnlyOptIn = prefs.getBoolean("allow_view_only_without_migration", false)
        val forceViewOnlyMode = (intent?.getBooleanExtra(EXTRA_VIEW_ONLY_MODE, false) == true) || persistedViewOnlyOptIn
        val isViewOnlyMode = !migrationReady && forceViewOnlyMode

        if (migrationReady && persistedViewOnlyOptIn) {
            prefs.edit().putBoolean("allow_view_only_without_migration", false).apply()
        }

        if (!migrationReady && !forceViewOnlyMode) {
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
                            onContinueViewOnly = {
                                prefs.edit().putBoolean("allow_view_only_without_migration", true).apply()
                                startActivity(
                                    Intent(this@MainActivity, MainActivity::class.java).apply {
                                        putExtra(EXTRA_VIEW_ONLY_MODE, true)
                                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                                finish()
                            },
                            onExit = { finishAffinity() }
                        )
                    }
                }
            }
            return
        }

        val syncthingTarget = SyncthingInstallResolver.resolve(applicationContext)
        syncthingSupervisor = SyncthingSupervisor(
            context = applicationContext,
            runtimeConfig = SyncthingRuntimeConfig(
                intents = SyncthingIntentConfig(
                    packageName = syncthingTarget.packageName,
                    startAction = syncthingTarget.startAction,
                    stopAction = syncthingTarget.stopAction
                )
            ),
            preferencesStore = DataStoreSyncthingPreferencesStore.create(applicationContext)
        )
        syncthingSupervisor.startMonitoring()

        val baseDir = File(basePath)
        val jobRepository = JobRepository(baseDir, isDebugBuild = BuildConfig.DEBUG)
        val progressStore = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(filesDir, "state"),
            readOnly = isViewOnlyMode
        )
        scanCoordinator = ScanCoordinator(baseDir, jobRepository)
        appStateStore = AppStateStore(scanCoordinator, progressStore)
        val supplyRepository = SupplyRepository(basePath)
        supplySubscriptionManager = SupplySubscriptionManager(applicationContext, supplyRepository)
        clockInState = ClockInState.create(this)
        if (clockInState.snapshot.isActive) {
            ClockInNotificationContract.startOrUpdateService(this)
        } else {
            ClockInNotificationContract.stopService(this)
        }
        handleNotificationIntent(intent)
        scanCoordinator.refresh(RefreshReason.APP_START, force = true)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
            var useStandardSheets by remember { mutableStateOf(prefs.getBoolean("use_standard_sheets", false)) }
            var employeeName by rememberSaveable { mutableStateOf(prefs.getString("employee_name", "") ?: "") }
            var workMode by remember {
                mutableStateOf(
                    WorkMode.fromStored(prefs.getString("work_mode", null))
                )
            }
            val syncthingStatus by syncthingSupervisor.status.collectAsState()
            val syncthingApiKey by syncthingSupervisor.apiKey.collectAsState()
            val composeScope = rememberCoroutineScope()
            var showSyncthingSetupPrompt by rememberSaveable { mutableStateOf(true) }
            var setupApiKeyInput by rememberSaveable { mutableStateOf("") }
            var acknowledgedSyncFailureAttemptAtMs by rememberSaveable { mutableStateOf<Long?>(null) }
            var showViewOnlyNotice by rememberSaveable { mutableStateOf(isViewOnlyMode) }

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
                        isViewOnlyMode = isViewOnlyMode,
                        appStateFlags = AppStateFeatureFlags(prefs, BuildConfig.DEBUG),
                        tabletId = tabletId,
                        basePath = basePath,
                        isDebugBuild = BuildConfig.DEBUG,
                        isDarkTheme = isDarkTheme,
                        useStandardSheets = useStandardSheets,
                        workMode = workMode,
                        employeeName = employeeName,
                        supplySubscriptionManager = supplySubscriptionManager,
                        onEmployeeNameChanged = { name ->
                            employeeName = name
                            prefs.edit().putString("employee_name", name).apply()
                        },
                        clockInState = clockInState,
                        onThemeChanged = { dark ->
                            isDarkTheme = dark
                            prefs.edit().putBoolean("dark_theme", dark).apply()
                        },
                        onUseStandardSheetsChanged = { useStd ->
                            useStandardSheets = useStd
                            prefs.edit().putBoolean("use_standard_sheets", useStd).apply()
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
                        },
                        syncthingApiKey = syncthingApiKey,
                        syncthingStatus = syncthingStatus,
                        onSyncthingApiKeySave = { apiKey ->
                            composeScope.launch {
                                syncthingSupervisor.saveApiKey(apiKey)
                            }
                        },
                        onSyncthingCheckNow = {
                            syncthingSupervisor.checkNow()
                        },
                        onSyncthingStartNow = {
                            syncthingSupervisor.startNow()
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

                    if (showSyncthingSetupPrompt && syncthingApiKey.isBlank()) {
                        AlertDialog(
                            onDismissRequest = { showSyncthingSetupPrompt = false },
                            title = { Text("Syncthing API Key Required") },
                            text = {
                                OutlinedTextField(
                                    value = setupApiKeyInput,
                                    onValueChange = { setupApiKeyInput = it },
                                    label = { Text("Syncthing API Key") },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        composeScope.launch {
                                            syncthingSupervisor.saveApiKey(setupApiKeyInput.trim())
                                        }
                                        showSyncthingSetupPrompt = false
                                    },
                                    enabled = setupApiKeyInput.trim().isNotBlank()
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSyncthingSetupPrompt = false }) {
                                    Text("Later")
                                }
                            }
                        )
                    }

                    if (showViewOnlyNotice) {
                        AlertDialog(
                            onDismissRequest = { showViewOnlyNotice = false },
                            title = { Text("View-Only Mode") },
                            text = {
                                Text(
                                    "Dataset migration is not complete. You can browse jobs and cut lists, but progress changes are disabled on this tablet."
                                )
                            },
                            confirmButton = {
                                Button(onClick = { showViewOnlyNotice = false }) {
                                    Text("OK")
                                }
                            }
                        )
                    }

                    val latestStartAttemptAtMs = syncthingStatus.lastStartAttemptAtMs
                    val showSyncNotRunningModal =
                        syncthingStatus.status == SyncthingServiceStatus.START_FAILED &&
                            latestStartAttemptAtMs != null &&
                            latestStartAttemptAtMs != acknowledgedSyncFailureAttemptAtMs

                    if (showSyncNotRunningModal) {
                        AlertDialog(
                            onDismissRequest = {
                                acknowledgedSyncFailureAttemptAtMs = latestStartAttemptAtMs
                            },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            title = {
                                Text(
                                    text = "Sync NOT Running",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            },
                            text = {
                                Text(
                                    text = "KKC could not start Syncthing automatically. Open Syncthing manually or tap Retry Start.",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        syncthingSupervisor.startNow()
                                        acknowledgedSyncFailureAttemptAtMs = latestStartAttemptAtMs
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Text("Retry Start")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        acknowledgedSyncFailureAttemptAtMs = latestStartAttemptAtMs
                                    }
                                ) {
                                    Text(
                                        text = "Dismiss",
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
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
        if (::syncthingSupervisor.isInitialized) {
            syncthingSupervisor.checkNow()
        }
        if (::scanCoordinator.isInitialized) {
            scanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::supplySubscriptionManager.isInitialized) {
            lifecycleScope.launch {
                supplySubscriptionManager.scanForUpdates()
            }
        }
    }

    override fun onDestroy() {
        if (::syncthingSupervisor.isInitialized) {
            syncthingSupervisor.close()
        }
        if (::supplySubscriptionManager.isInitialized) {
            supplySubscriptionManager.close()
        }
        super.onDestroy()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notificationGranted) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

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

    private fun handleNotificationIntent(intent: Intent?) {
        if (!::clockInState.isInitialized) return
        if (ClockInNotificationContract.shouldOpenClockOutPrompt(intent)) {
            clockInState.refreshFromDisk()
            if (clockInState.snapshot.isActive) {
                clockInState.triggerPrompt()
                ClockInNotificationContract.startOrUpdateService(this)
            }
            intent?.removeExtra(ClockInNotificationContract.EXTRA_OPEN_CLOCK_OUT_PROMPT)
        }
    }
}
