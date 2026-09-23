package com.kkc.sheettracker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kkc.sheettracker.clock.ClockInNotificationContract
import com.kkc.sheettracker.crash.CrashReporter
import com.kkc.sheettracker.perf.CpuSpikeMonitor
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.TrackerLamportClock
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.data.IdleActivityTracker
import com.kkc.sheettracker.data.IdlePhase
import com.kkc.sheettracker.data.IdlePowerSaveConfig
import com.kkc.sheettracker.data.IdlePowerSaveStore
import com.kkc.sheettracker.ui.components.LowEndModeFlags
import com.kkc.sheettracker.ui.components.WebViewBlurGate
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.LocalScrollPreviewLabelOnly
import com.kkc.sheettracker.ui.components.LocalIdlePhase
import com.kkc.sheettracker.ui.components.LocalIdlePollIntervalOverrideMs
import com.kkc.sheettracker.ui.components.LocalIdleReset
import com.kkc.sheettracker.navigation.AppNavigation
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.onboarding.OnboardingStep
import com.kkc.sheettracker.onboarding.PermissionFlowController
import com.kkc.sheettracker.onboarding.PermissionSnapshot
import com.kkc.sheettracker.sync.DataStoreSyncthingPreferencesStore
import com.kkc.sheettracker.sync.SyncthingInstallResolver
import com.kkc.sheettracker.sync.SyncthingIntentConfig
import com.kkc.sheettracker.sync.SyncthingRuntimeConfig
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.sync.SyncthingSupervisor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kkc.sheettracker.ui.migration.MigrationRequiredScreen
import com.kkc.sheettracker.ui.onboarding.OnboardingGate
import com.kkc.sheettracker.ui.components.PersistentNavigationBarHider
import com.kkc.sheettracker.ui.theme.KKCThemeRepository
import com.kkc.sheettracker.ui.theme.KKCTheme
import com.kkc.sheettracker.ui.theme.SharedPreferencesKKCThemePreferenceStore
import com.kkc.sheettracker.update.UpdateManager
import com.kkc.sheettracker.update.ExternalAppUpdate
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: UpdateManager
    private lateinit var scanCoordinator: ScanCoordinator
    private lateinit var appStateStore: AppStateStore
    private lateinit var syncthingSupervisor: SyncthingSupervisor
    private lateinit var clockInState: ClockInState
    private lateinit var supplySubscriptionManager: SupplySubscriptionManager
    private lateinit var idleActivityTracker: IdleActivityTracker

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshOnboardingStep() }

    private val onboardingSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingSettingsReturnAction
        pendingSettingsReturnAction = null
        if (action != null) action() else refreshOnboardingStep()
    }

    private var pendingOnboardingStep by mutableStateOf<OnboardingStep?>(null)
    private var pendingSettingsReturnAction: (() -> Unit)? = null

    private companion object {
        const val EXTRA_VIEW_ONLY_MODE = "extra_view_only_mode"
        const val SYNCTHING_PROMPT_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val UPDATE_RESCAN_INTERVAL_MS = 20 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        refreshOnboardingStep()
        if (pendingOnboardingStep != null) {
            setContent {
                KKCTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                    PersistentNavigationBarHider()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val step = pendingOnboardingStep
                        if (step != null) {
                            OnboardingGate(
                                step = step,
                                notificationsBlocked = notificationsPermanentlyBlocked(),
                                onRequestNotifications = {
                                    getSharedPreferences("kkc_tracker", MODE_PRIVATE).edit()
                                        .putBoolean("notif_permission_requested", true)
                                        .apply()
                                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                },
                                onOpenNotificationSettings = {
                                    launchNotificationSettingsIntent()
                                },
                                onConfirmStorageAccess = {
                                    launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                },
                                onConfirmInstallPermission = {
                                    launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                }
                            )
                        }
                    }
                }
            }
            return
        }

        val prefs = getSharedPreferences("kkc_tracker", MODE_PRIVATE)
        com.kkc.sheettracker.data.AdminModeController.init(this)
        var tabletId = prefs.getString("tablet_id", null)
        if (tabletId == null) {
            tabletId = "${Build.MODEL}-${System.currentTimeMillis() % 10000}"
            prefs.edit().putString("tablet_id", tabletId).apply()
        }

        val basePath = prefs.getString("base_path", null)
            ?: findDefaultBasePath()
                .also { discoveredPath ->
                    prefs.edit().putString("base_path", discoveredPath).apply()
                }
        CrashReporter.updateContext(
            tabletId = tabletId,
            basePath = basePath,
            workMode = prefs.getString("work_mode", null) ?: WorkMode.CNC.name
        )
        CrashReporter.flushPending(basePath)
        CpuSpikeMonitor.updateContext(
            tabletId = tabletId,
            basePath = basePath,
            workMode = prefs.getString("work_mode", null) ?: WorkMode.CNC.name
        )
        CpuSpikeMonitor.flushPending(basePath)

        updateManager = UpdateManager(
            activity = this,
            onRequestInstallPermission = { onGranted ->
                pendingSettingsReturnAction = {
                    if (packageManager.canRequestPackageInstalls()) {
                        onGranted()
                    }
                }
                launchOnboardingSettingsIntent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        ).apply {
            this.basePath = basePath
            this.tabletId = tabletId
        }
        updateManager.checkForUpdates(checkSelf = true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(UPDATE_RESCAN_INTERVAL_MS)
                    updateManager.checkForUpdates(checkSelf = true)
                }
            }
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
                val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                val followSystemTheme = prefs.getBoolean("follow_system_theme", true)
                val darkThemeOverride = prefs.getBoolean("dark_theme", false)
                val isDarkTheme = if (followSystemTheme) systemDark else darkThemeOverride
                val themePrefs = remember { SharedPreferencesKKCThemePreferenceStore(prefs) }
                val themeRepository = remember(basePath) { KKCThemeRepository(File(basePath), themePrefs) }
                val themeCatalog = remember(themeRepository) { themeRepository.loadCatalog() }
                KKCTheme(darkTheme = isDarkTheme, themeTokens = themeCatalog.activeTheme.tokens) {
                    PersistentNavigationBarHider()
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

        val idlePowerSaveStore = IdlePowerSaveStore(applicationContext)
        val idlePowerSaveConfig = idlePowerSaveStore.configFlow.stateIn(
            lifecycleScope, SharingStarted.Eagerly, IdlePowerSaveConfig()
        )
        idleActivityTracker = IdleActivityTracker(config = idlePowerSaveConfig)
        idleActivityTracker.start()
        syncthingSupervisor.observeIdlePhase(idleActivityTracker.phase)

        val baseDir = File(basePath)
        val jobRepository = JobRepository(baseDir, isDebugBuild = BuildConfig.DEBUG)
        TrackerLamportClock.init(File(filesDir, "state"))
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
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val idlePhase by idleActivityTracker.phase.collectAsState()
            var followSystemTheme by remember { mutableStateOf(prefs.getBoolean("follow_system_theme", true)) }
            var darkThemeOverride by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
            val isDarkTheme = if (idlePhase != IdlePhase.ACTIVE) {
                true
            } else if (followSystemTheme) {
                systemDark
            } else {
                darkThemeOverride
            }
            var useStandardSheets by remember { mutableStateOf(prefs.getBoolean("use_standard_sheets", false)) }
            var continuousScrollDefault by remember { mutableStateOf(prefs.getBoolean("continuous_scroll_default", false)) }
            var employeeName by rememberSaveable { mutableStateOf(prefs.getString("employee_name", "") ?: "") }
            var workMode by remember {
                mutableStateOf(
                    WorkMode.fromStored(prefs.getString("work_mode", null))
                )
            }
            var flexibleModeEnabled by remember {
                mutableStateOf(prefs.getBoolean("flexible_mode_enabled", false))
            }
            LaunchedEffect(workMode) {
                CrashReporter.updateContext(workMode = workMode.name)
                CpuSpikeMonitor.updateContext(workMode = workMode.name)
            }
            val syncthingStatus by syncthingSupervisor.status.collectAsState()
            val syncthingApiKey by syncthingSupervisor.apiKey.collectAsState()
            val composeScope = rememberCoroutineScope()
            var showSyncthingSetupPrompt by rememberSaveable { mutableStateOf(shouldPromptForSyncthingKey(prefs)) }
            var setupApiKeyInput by rememberSaveable { mutableStateOf("") }
            var acknowledgedSyncFailureAttemptAtMs by rememberSaveable { mutableStateOf<Long?>(null) }
            var showViewOnlyNotice by rememberSaveable { mutableStateOf(isViewOnlyMode) }
            val themePrefs = remember { SharedPreferencesKKCThemePreferenceStore(prefs) }
            val themeRepository = remember(basePath) { KKCThemeRepository(File(basePath), themePrefs) }
            var themeCatalog by remember(themeRepository) { mutableStateOf(themeRepository.loadCatalog()) }
            fun reloadThemeCatalog() {
                themeCatalog = themeRepository.loadCatalog()
            }

            val featureFlags = remember { AppStateFeatureFlags(prefs, BuildConfig.DEBUG) }
            val flagsSnapshot by featureFlags.snapshotFlow.collectAsState(initial = featureFlags.snapshot())
            // A visible WebView (3D pane) turns all blur off for as long as it is on screen; this is
            // a transient override, so the user's saved low-end settings are never touched.
            val webViewShowing by WebViewBlurGate.shared.isActive.collectAsState()
            val lowEndFlags = remember(flagsSnapshot, webViewShowing) {
                LowEndModeFlags(
                    masterEnabled = flagsSnapshot.lowEndMode,
                    animationsEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.animationsEnabled,
                    shadowsEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.shadowsEnabled,
                    blurEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.blurEnabled,
                    lazyLoadingEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.lazyLoadingEnabled,
                    webViewBlurSuppressed = webViewShowing,
                )
            }
            val scrollPreviewLabelOnly = remember(flagsSnapshot) { flagsSnapshot.scrollPreviewLabelOnly }

            KKCTheme(darkTheme = isDarkTheme, themeTokens = themeCatalog.activeTheme.tokens) {
                PersistentNavigationBarHider()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalLowEndMode provides lowEndFlags,
                        LocalScrollPreviewLabelOnly provides scrollPreviewLabelOnly,
                        LocalIdlePhase provides idleActivityTracker.phase,
                        LocalIdlePollIntervalOverrideMs provides idleActivityTracker.pollIntervalOverrideMs,
                        LocalIdleReset provides idleActivityTracker::reset
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
                        followSystemTheme = followSystemTheme,
                        darkThemeOverride = darkThemeOverride,
                        useStandardSheets = if (idlePhase != IdlePhase.ACTIVE) false else useStandardSheets,
                        continuousScrollDefault = continuousScrollDefault,
                        workMode = workMode,
                        flexibleModeEnabled = flexibleModeEnabled,
                        employeeName = employeeName,
                        supplySubscriptionManager = supplySubscriptionManager,
                        onEmployeeNameChanged = { name ->
                            employeeName = name
                            prefs.edit().putString("employee_name", name).apply()
                        },
                        clockInState = clockInState,
                        onThemeChanged = { dark ->
                            darkThemeOverride = dark
                            prefs.edit().putBoolean("dark_theme", dark).apply()
                        },
                        onFollowSystemThemeChanged = { follow ->
                            followSystemTheme = follow
                            prefs.edit().putBoolean("follow_system_theme", follow).apply()
                        },
                        onUseStandardSheetsChanged = { useStd ->
                            useStandardSheets = useStd
                            prefs.edit().putBoolean("use_standard_sheets", useStd).apply()
                        },
                        onContinuousScrollDefaultChanged = { enabled ->
                            continuousScrollDefault = enabled
                            prefs.edit().putBoolean("continuous_scroll_default", enabled).apply()
                        },
                        onWorkModeChanged = { mode ->
                            workMode = mode
                            prefs.edit().putString("work_mode", mode.name).apply()
                            CrashReporter.updateContext(workMode = mode.name)
                            CpuSpikeMonitor.updateContext(workMode = mode.name)
                        },
                        onFlexibleModeChanged = { enabled ->
                            flexibleModeEnabled = enabled
                            prefs.edit().putBoolean("flexible_mode_enabled", enabled).apply()
                        },
                        onReinstallLatest = { updateManager.reinstallLatest() },
                        hasPendingUpdates = updateManager.pendingUpdateApk != null || updateManager.pendingExternalUpdates.isNotEmpty(),
                        pendingSelfUpdate = updateManager.pendingUpdateApk,
                        pendingExternalUpdates = updateManager.pendingExternalUpdates,
                        onInstallSelfUpdate = { updateManager.installPendingUpdate() },
                        onInstallExternalUpdate = { update -> updateManager.installExternalUpdate(update) },
                        onInstallAll = {
                            // Hours Tracker must install first: installing Sheet Tracker over itself
                            // kills this process, so anything queued after that point won't fire.
                            updateManager.pendingExternalUpdates.firstOrNull()?.let {
                                updateManager.installExternalUpdate(it)
                            }
                            updateManager.installPendingUpdate()
                        },
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
                        },
                        themeCatalog = themeCatalog,
                        onThemeFollowSyncedDefaultChanged = { follow ->
                            themeRepository.setFollowSyncedDefault(follow)
                            reloadThemeCatalog()
                        },
                        onThemeOverrideChanged = { themeId ->
                            themeRepository.setOverrideThemeId(themeId)
                            reloadThemeCatalog()
                        },
                        onThemeCatalogReload = { reloadThemeCatalog() }
                    )
                }

                    if (showSyncthingSetupPrompt && syncthingApiKey.isBlank()) {
                        val dismissSyncthingPrompt = {
                            prefs.edit().putLong("last_syncthing_prompt_at_ms", System.currentTimeMillis()).apply()
                            showSyncthingSetupPrompt = false
                        }
                        AlertDialog(
                            onDismissRequest = dismissSyncthingPrompt,
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
                                TextButton(onClick = dismissSyncthingPrompt) {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-assert immersive mode every time the window regains focus. The system
        // re-shows the status/nav bars after resume, dialogs, the soft keyboard, and
        // app switches; without this they stay visible. Keeps the app truly fullscreen.
        if (hasFocus) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::idleActivityTracker.isInitialized) {
            idleActivityTracker.reset()
        }
    }

    // onUserInteraction only fires on touch-down; the perf monitor needs every touch/key event so a
    // long scroll or 3D orbit is not mistaken for "the user is idle and the screen keeps redrawing".
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        CpuSpikeMonitor.noteUserInput()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        CpuSpikeMonitor.noteUserInput()
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        CpuSpikeMonitor.setForeground(true)
        CpuSpikeMonitor.attachWindow(window)
        refreshOnboardingStep()
        if (::syncthingSupervisor.isInitialized) {
            syncthingSupervisor.setAppForeground(true)
            syncthingSupervisor.checkNow()
        }
        if (::scanCoordinator.isInitialized) {
            scanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
        }
        if (::updateManager.isInitialized) {
            updateManager.checkForUpdates(checkSelf = true)
        }
    }

    override fun onStop() {
        CpuSpikeMonitor.setForeground(false)
        CpuSpikeMonitor.detachWindow()
        if (::syncthingSupervisor.isInitialized) {
            syncthingSupervisor.setAppForeground(false)
        }
        super.onStop()
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
        if (::idleActivityTracker.isInitialized) {
            idleActivityTracker.stop()
        }
        super.onDestroy()
    }

    private fun refreshOnboardingStep() {
        val next = PermissionFlowController.nextStep(currentPermissionSnapshot())
        val wasPending = pendingOnboardingStep != null
        pendingOnboardingStep = next
        if (wasPending && next == null) {
            recreate()
        }
    }

    private fun currentPermissionSnapshot(): PermissionSnapshot {
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
        return PermissionSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsGranted = notificationsGranted,
            storageGranted = storageGranted,
            installUnknownAppsGranted = packageManager.canRequestPackageInstalls()
        )
    }

    private fun launchOnboardingSettingsIntent(action: String) {
        try {
            onboardingSettingsLauncher.launch(
                Intent(action).apply { data = Uri.parse("package:$packageName") }
            )
        } catch (_: ActivityNotFoundException) {
            if (action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) {
                onboardingSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun shouldPromptForSyncthingKey(prefs: android.content.SharedPreferences): Boolean {
        val lastPromptAtMs = prefs.getLong("last_syncthing_prompt_at_ms", 0L)
        return System.currentTimeMillis() - lastPromptAtMs >= SYNCTHING_PROMPT_INTERVAL_MS
    }

    private fun notificationsPermanentlyBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return false
        val hasAskedBefore = getSharedPreferences("kkc_tracker", MODE_PRIVATE)
            .getBoolean("notif_permission_requested", false)
        return hasAskedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launchNotificationSettingsIntent() {
        onboardingSettingsLauncher.launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        )
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
