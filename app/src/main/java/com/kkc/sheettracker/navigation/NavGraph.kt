package com.kkc.sheettracker.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kkc.sheettracker.clock.ClockInNotificationContract
import com.kkc.sheettracker.crash.CrashReporter
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.AssemblyViewLayout
import com.kkc.sheettracker.data.AssemblyViewerDefaultsStore
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.PinnedJobsStore
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.SheetRipProgressStore
import com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore
import com.kkc.sheettracker.data.SafetyRepository
import com.kkc.sheettracker.data.SafetySubscriptionManager
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.SpecialtyRepository
import com.kkc.sheettracker.data.SpecialtyScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.TabletSpecialtyItemsStore
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.TrackerChangeMonitor
import com.kkc.sheettracker.data.StaticCachePoller
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.LiveIndexClient
import com.kkc.sheettracker.data.unified.LiveAwareUnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.data.TimecardDiscovery
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.TimeclockMessagesRepository
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.data.IdlePowerSaveStore
import com.kkc.sheettracker.ui.hours.HoursLoginDialog
import com.kkc.sheettracker.ui.timecard.TimecardScreen
import com.kkc.sheettracker.ui.timecard.TimecardStore
import com.kkc.sheettracker.ui.assembly.AssemblyJobDetailScreen
import com.kkc.sheettracker.ui.assembly.AssemblySearchScreen
import com.kkc.sheettracker.ui.assembly.AssemblyViewerScreen
import com.kkc.sheettracker.ui.settings.AssemblyViewerDefaultsScreen
import com.kkc.sheettracker.ui.settings.SpecialtyViewerDefaultsScreen
import com.kkc.sheettracker.ui.components.AppBottomNavBar
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.LocalOnOpenSettings
import com.kkc.sheettracker.ui.components.NavBarDecorationState
import com.kkc.sheettracker.ui.components.CalculatorOverlayHost
import com.kkc.sheettracker.ui.components.ClockInOverlay
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.components.rememberCalculatorOverlayState
import com.kkc.sheettracker.ui.components.LocalIdlePollIntervalOverrideMs
import com.kkc.sheettracker.ui.components.LocalIdleReset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import com.kkc.sheettracker.ui.dashboard.UnifiedModeDashboardScreen
import com.kkc.sheettracker.ui.dashboard.UnifiedModeDashboardSpec
import com.kkc.sheettracker.ui.detail.JobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsSearchScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsWorkspaceScreen
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_RIP_CUT_LIST_ROW_ID
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_SAW_RIP_LIST_ROW_ID
import com.kkc.sheettracker.ui.search.SearchScreen
import com.kkc.sheettracker.ui.settings.SettingsScreen
import com.kkc.sheettracker.ui.supply.SupplyDashboardScreen
import com.kkc.sheettracker.ui.specialty.SpecialtyDoorPanelsScreen
import com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreen
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreen
import com.kkc.sheettracker.ui.viewer.SheetViewerScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

private const val SPECIALTY_DOOR_PANELS_ROUTE_BASE = "specialty/door-panels"
private const val SPECIALTY_DOOR_PANELS_ROUTE_PATTERN = "$SPECIALTY_DOOR_PANELS_ROUTE_BASE/{folderName}"

@Composable
fun AppNavigation(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    isViewOnlyMode: Boolean,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    supplySubscriptionManager: SupplySubscriptionManager,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    hardwoodsProgressStore: HardwoodsProgressStore? = null,
    specialtyProgressStore: SpecialtyProgressStore? = null
) {
    val sharedHardwoodsProgressStore = hardwoodsProgressStore ?: remember(basePath, tabletId, isViewOnlyMode) {
        HardwoodsProgressStore(File(basePath), tabletId, readOnly = isViewOnlyMode)
    }
    val sharedSpecialtyProgressStore = specialtyProgressStore ?: remember(basePath, tabletId, isViewOnlyMode) {
        SpecialtyProgressStore(File(basePath), tabletId, readOnly = isViewOnlyMode)
    }
    val watcherRefreshSignal = remember(basePath) { MutableStateFlow(0L) }
    val watcherRefreshEpoch by watcherRefreshSignal.collectAsState()
    val activeJobFolderName = remember { MutableStateFlow<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val idlePollIntervalOverrideMs = LocalIdlePollIntervalOverrideMs.current
    val trackerChangeMonitor = remember(
        basePath,
        progressStore,
        scanCoordinator,
        sharedHardwoodsProgressStore,
        sharedSpecialtyProgressStore,
        watcherRefreshSignal
    ) {
        // Read-only monitor: reacts to synced tracker file changes by invalidating in-memory caches,
        // then requests a coalesced full refresh.
        TrackerChangeMonitor(
            baseDir = File(basePath),
            progressStore = progressStore,
            hardwoodsProgressStore = sharedHardwoodsProgressStore,
            specialtyProgressStore = sharedSpecialtyProgressStore,
            activeJobFolderName = activeJobFolderName,
            intervalOverrideMs = idlePollIntervalOverrideMs,
            onWatcherRefreshRequested = {
                watcherRefreshSignal.value = System.currentTimeMillis()
            },
            onCncJobsChanged = { jobFolderNames ->
                jobFolderNames.forEach { scanCoordinator.unifiedEngine.invalidateJob(it) }
            }
        )
    }
    DisposableEffect(lifecycleOwner, trackerChangeMonitor) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> trackerChangeMonitor.start()
                Lifecycle.Event.ON_STOP -> trackerChangeMonitor.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            trackerChangeMonitor.stop()
        }
    }

    // StaticCachePoller watches only cache_index.json and deployment_gate.json, then triggers
    // the same coordinator refresh signal used by TrackerChangeMonitor. It never opens full
    // cache_static.json; job detail/viewer routes own those per-job loads.
    val staticCachePoller = remember(basePath, watcherRefreshSignal) {
        StaticCachePoller(
            baseDir = File(basePath),
            intervalOverrideMs = idlePollIntervalOverrideMs,
            onJobCacheUpdated = { _ ->
                watcherRefreshSignal.value = System.currentTimeMillis()
            }
        )
    }
    DisposableEffect(lifecycleOwner, staticCachePoller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> staticCachePoller.start()
                // Sole ON_STOP handler for staticCachePoller -- see the liveIndexClient
                // DisposableEffect below, which intentionally does not also touch it.
                Lifecycle.Event.ON_STOP -> staticCachePoller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            staticCachePoller.stop()
        }
    }

    val liveIndexContext = LocalContext.current
    val liveIndexAdminSyncConfig = remember { AdminSyncConfig.create(liveIndexContext) }
    val liveIndexRegistryEngine = remember(basePath, isDebugBuild) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild)
    }
    val liveIndexEngine = remember(liveIndexRegistryEngine) {
        LiveAwareUnifiedMetadataEngine(liveIndexRegistryEngine)
    }
    val liveIndexClient = remember(liveIndexAdminSyncConfig, liveIndexEngine, tabletId) {
        LiveIndexClient(
            config = liveIndexAdminSyncConfig,
            tabletId = tabletId,
            onSnapshot = { jobs ->
                liveIndexEngine.applySnapshot(jobs)
                watcherRefreshSignal.value = System.currentTimeMillis()
            },
            onDelta = { folderName, index ->
                liveIndexEngine.applyDelta(folderName, index)
                watcherRefreshSignal.value = System.currentTimeMillis()
            },
            onConnectionState = { isConnected ->
                liveIndexEngine.setConnected(isConnected)
                if (isConnected) staticCachePoller.stop() else staticCachePoller.start()
                watcherRefreshSignal.value = System.currentTimeMillis()
            }
        )
    }
    DisposableEffect(lifecycleOwner, liveIndexClient) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> liveIndexClient.start()
                Lifecycle.Event.ON_STOP -> {
                    // staticCachePoller has its own separate DisposableEffect above that already
                    // stops it on ON_STOP -- do not also start it here. Android dispatches ON_STOP
                    // to observers in reverse registration order, so a start() call here would run
                    // BEFORE that other observer's stop() call and get immediately undone.
                    liveIndexClient.stop()
                    liveIndexEngine.setConnected(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            liveIndexClient.stop()
        }
    }

    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(progressStore, hardwoodsRepository, sharedHardwoodsProgressStore, jobRepository) {
        val listener = { jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String, isComplete: Boolean ->
            coroutineScope.launch(Dispatchers.IO) {
                com.kkc.sheettracker.data.syncCncToHardwoods(
                    jobFolderName = jobFolderName,
                    jobRepository = jobRepository,
                    progressStore = progressStore,
                    hardwoodsRepository = hardwoodsRepository,
                    hardwoodsProgressStore = sharedHardwoodsProgressStore
                )
            }
            Unit
        }
        progressStore.onSheetStatusChangedListener = listener
        onDispose {
            if (progressStore.onSheetStatusChangedListener === listener) {
                progressStore.onSheetStatusChangedListener = null
            }
        }
    }

    val flags = remember(appStateFlags) { appStateFlags.snapshot() }
    key(workMode) {
        if (flags.navMultiStackEnabled) {
            MultiBackStackNavigation(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                hardwoodsProgressStore = sharedHardwoodsProgressStore,
                specialtyProgressStore = sharedSpecialtyProgressStore,
                appStateFlags = appStateFlags,
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isViewOnlyMode = isViewOnlyMode,
                isDarkTheme = isDarkTheme,
                followSystemTheme = followSystemTheme,
                darkThemeOverride = darkThemeOverride,
                useStandardSheets = useStandardSheets,
                continuousScrollDefault = continuousScrollDefault,
                workMode = workMode,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                clockInState = clockInState,
                onThemeChanged = onThemeChanged,
                onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onBasePathChanged = onBasePathChanged,
                onTabletIdChanged = onTabletIdChanged,
                syncthingApiKey = syncthingApiKey,
                syncthingStatus = syncthingStatus,
                onSyncthingApiKeySave = onSyncthingApiKeySave,
                onSyncthingCheckNow = onSyncthingCheckNow,
                onSyncthingStartNow = onSyncthingStartNow,
                watcherRefreshEpoch = watcherRefreshEpoch,
                activeJobFolderName = activeJobFolderName,
                supplySubscriptionManager = supplySubscriptionManager,
                themeCatalog = themeCatalog,
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                liveIndexEngine = liveIndexEngine
            )
        } else {
            LegacySingleStackNavigation(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                hardwoodsProgressStore = sharedHardwoodsProgressStore,
                specialtyProgressStore = sharedSpecialtyProgressStore,
                appStateFlags = appStateFlags,
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isViewOnlyMode = isViewOnlyMode,
                isDarkTheme = isDarkTheme,
                followSystemTheme = followSystemTheme,
                darkThemeOverride = darkThemeOverride,
                useStandardSheets = useStandardSheets,
                continuousScrollDefault = continuousScrollDefault,
                workMode = workMode,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                clockInState = clockInState,
                onThemeChanged = onThemeChanged,
                onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onBasePathChanged = onBasePathChanged,
                onTabletIdChanged = onTabletIdChanged,
                syncthingApiKey = syncthingApiKey,
                syncthingStatus = syncthingStatus,
                onSyncthingApiKeySave = onSyncthingApiKeySave,
                onSyncthingCheckNow = onSyncthingCheckNow,
                onSyncthingStartNow = onSyncthingStartNow,
                watcherRefreshEpoch = watcherRefreshEpoch,
                supplySubscriptionManager = supplySubscriptionManager,
                themeCatalog = themeCatalog,
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                unifiedEngine = liveIndexEngine
            )
        }
    }
}

@Composable
private fun MultiBackStackNavigation(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyProgressStore: SpecialtyProgressStore,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isViewOnlyMode: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    watcherRefreshEpoch: Long,
    activeJobFolderName: MutableStateFlow<String?>,
    supplySubscriptionManager: SupplySubscriptionManager,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    liveIndexEngine: UnifiedMetadataEngine
) {
    val preferDarkMode = isDarkTheme && !useStandardSheets
    val supplyNotificationCount by supplySubscriptionManager.notificationCount.collectAsState()
    val safetyRepository = remember(basePath) { SafetyRepository(basePath) }
    val safetySubscriptionManager = remember(safetyRepository) { SafetySubscriptionManager(safetyRepository) }
    val safetyNotificationCount by safetySubscriptionManager.notificationCount.collectAsState()
    val activity = LocalActivity.current
    val calculatorState = rememberCalculatorOverlayState()
    val compactWidth = rememberCompactWidthClass()
    val context = LocalContext.current
    val timecardConfig = remember { TimecardServerConfig.create(context) }
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val assemblyViewerDefaultsStore = remember { AssemblyViewerDefaultsStore.create(context) }
    val specialtyViewerDefaultsStore = remember { SpecialtyViewerDefaultsStore.create(context) }
    val pinnedJobsStore = remember { PinnedJobsStore.create(context) }
    val timecardDiscovery = remember { TimecardDiscovery(context) }
    val timeclockMessagesRepo = remember { TimeclockMessagesRepository(File(basePath)) }
    val timecardStore = remember { TimecardStore(timecardConfig, timecardDiscovery, timeclockMessagesRepo, File(basePath)) }
    DisposableEffect(timecardStore) { onDispose { timecardStore.cancel() } }
    LaunchedEffect(basePath) { EmployeeDirectory.refresh(File(basePath)) }
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
    val specialtyRepository = remember(basePath, specialtyProgressStore) {
        SpecialtyRepository(File(basePath), specialtyProgressStore)
    }
    val specialtyScanCoordinator = remember(specialtyRepository) { SpecialtyScanCoordinator(specialtyRepository) }
    val assemblyScanCoordinator = remember(basePath) { AssemblyScanCoordinator(File(basePath), jobRepository) }
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore, liveIndexEngine) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            liveEngine = liveIndexEngine
        )
    }
    val sheetRipProgressStore = remember(basePath) {
        SheetRipProgressStore(File(basePath))
    }
    val deliveryScheduleRepository = remember(basePath) {
        DeliveryScheduleRepository(File(basePath))
    }
    val tabletSpecialtyItemsStore = remember(basePath, tabletId) {
        TabletSpecialtyItemsStore(File(basePath), tabletId)
    }
    val specialtyStateStore = remember(specialtyScanCoordinator, specialtyProgressStore, hardwoodsProgressStore, sheetRipProgressStore, tabletSpecialtyItemsStore, basePath) {
        SpecialtyStateStore(
            specialtyScanCoordinator = specialtyScanCoordinator,
            specialtyProgressStore = specialtyProgressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            sheetRipProgressStore = sheetRipProgressStore,
            tabletItemsStore = tabletSpecialtyItemsStore,
            baseDir = File(basePath)
        )
    }

    val dashboardNavController = rememberNavController()
    val jobsNavController = rememberNavController()
    val searchNavController = rememberNavController()
    val settingsNavController = rememberNavController()
    val hoursNavController = rememberNavController()
    val timecardNavController = rememberNavController()
    val supplyNavController = rememberNavController()
    val standardsNavController = rememberNavController()
    val homeTab = homeTopLevelTabForWorkMode(workMode)
    var selectedTab by remember(workMode) { mutableStateOf(homeTab) }
    var pendingClockIn by remember { mutableStateOf<PendingClockIn?>(null) }
    var pendingClockOut by remember { mutableStateOf<PendingClockOut?>(null) }
    var showHoursLoginDialog by remember { mutableStateOf(false) }
    val visibleDestinations = remember(workMode) {
        // SETTINGS is reached via the top bar's Settings icon (LocalOnOpenSettings), not the
        // bottom nav bar — filtered out of both branches here.
        if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) {
            listOf(NavDestination.JOBS, NavDestination.HOURS, NavDestination.TIMECARD, NavDestination.SUPPLY, NavDestination.STANDARDS)
        } else {
            NavDestination.entries.filter {
                it != NavDestination.SEARCH && it != NavDestination.SETTINGS
            }
        }
    }

    val jobsBackStack by jobsNavController.currentBackStackEntryAsState()
    val jobsCurrentRoute = jobsBackStack?.destination?.route
    val isInViewer = selectedTab == TopLevelTab.JOBS &&
        (
            jobsCurrentRoute?.startsWith("viewer/") == true ||
                jobsCurrentRoute?.startsWith("referenceViewer/") == true ||
                jobsCurrentRoute?.startsWith("hardwoods/workspace/") == true ||
                jobsCurrentRoute?.startsWith("assembly/viewer/") == true ||
                jobsCurrentRoute?.startsWith("specialty/job/") == true
            )
    // Tracks whether the viewer screen's overlay UI is visible (for bottom nav hide/show).
    var viewerUiVisible by remember { mutableStateOf(true) }
    // Reset UI visibility whenever we leave viewer routes.
    androidx.compose.runtime.LaunchedEffect(isInViewer) { if (!isInViewer) viewerUiVisible = true }
    val navBarAlpha by animateFloatAsState(
        if (!isInViewer || viewerUiVisible) 1f else 0f,
        tween(286), label = "navBarAlpha"
    )
    val hazeState = remember { HazeState() }
    val navBarDeco = remember { NavBarDecorationState() }

    // Safety net for leaving a viewer route (e.g. the hardwoods workspace's Classic cut
    // list, which hosts its pen toolbar via extendedControls): that screen's own
    // DisposableEffect clears extendedControls when it leaves composition, but that's
    // driven by NavHost's teardown timing, not by this route classification. If the two
    // land a frame apart, the nav bar briefly renders "not extended, still minimized" —
    // the plain icon-only pill — before `minimized` catches up and it grows back out to
    // the full labeled bar, showing up as a jerky two-step collapse instead of one smooth
    // shrink. Clearing it here too, off the exact same isInViewer signal that drives
    // `minimized`, keeps both changes on the same trigger.
    LaunchedEffect(isInViewer) {
        if (!isInViewer) navBarDeco.extendedControls = null
    }

    androidx.compose.runtime.LaunchedEffect(selectedTab, jobsBackStack) {
        val route = jobsBackStack?.destination?.route ?: ""
        val folderName = if (selectedTab == TopLevelTab.JOBS) {
            val isJobRoute = route.startsWith("job/") ||
                route.startsWith("hardwoods/job/") ||
                route.startsWith("hardwoods/workspace/") ||
                route.startsWith("assembly/job/") ||
                route.startsWith("specialty/job/") ||
                route.startsWith("viewer/")
            if (isJobRoute) jobsBackStack?.arguments?.getString("folderName") else null
        } else null
        activeJobFolderName.value = folderName
        appStateStore.notifyJobFocus(folderName)
        CrashReporter.updateNavigationContext(
            currentTab = selectedTab.route,
            currentRoute = if (selectedTab == TopLevelTab.JOBS) route.ifBlank { selectedTab.route } else selectedTab.route,
            activeJobFolderName = folderName
        )
    }

    val coordinator = remember(
        dashboardNavController,
        jobsNavController,
        searchNavController,
        settingsNavController,
        hoursNavController,
        timecardNavController,
        supplyNavController,
        standardsNavController,
    ) {
        NavigationCoordinator(
            dashboardNavController = dashboardNavController,
            jobsNavController = jobsNavController,
            searchNavController = searchNavController,
            hoursNavController = hoursNavController,
            timecardNavController = timecardNavController,
            settingsNavController = settingsNavController,
            supplyNavController = supplyNavController,
            standardsNavController = standardsNavController,
            getHomeTab = { homeTab },
            getSelectedTab = { selectedTab },
            setSelectedTab = { selectedTab = it }
        )
    }

    BackHandler(enabled = activity != null) {
        activity?.let { coordinator.onBackPressed(it) }
    }

    val onClockInNow: (jobNumber: String, jobName: String, folderName: String, tabType: String, employee: String) -> Unit =
        { jobNumber, jobName, folderName, tabType, employee ->
            clockInState.clockIn(jobNumber, formattedClockInJobName(jobName, employee), folderName, tabType)
            ClockInNotificationContract.startOrUpdateService(context)
        }
    val onClockIn: (jobNumber: String, jobName: String, folderName: String, tabType: String) -> Unit =
        { jobNumber, jobName, folderName, tabType ->
            when (val gate = resolveClockInGate(employeeName, jobNumber, jobName, folderName, tabType)) {
                is ClockInGateResult.NeedsLogin -> pendingClockIn = gate.pending
                is ClockInGateResult.Ready -> onClockInNow(gate.jobNumber, gate.jobName, gate.folderName, gate.tabType, gate.employee)
            }
        }
    val onClockOut: () -> Unit = {
        val snap = clockInState.snapshot
        val stopTimeMs = System.currentTimeMillis()
        val elapsedMs = clockInState.clockOut()
        ClockInNotificationContract.stopService(context)
        val elapsedHours = (Math.round(elapsedMs / 3600000.0 * 4) / 4.0).coerceAtLeast(0.25)
        pendingClockOut = PendingClockOut(snap.jobName, snap.jobNumber, elapsedHours, snap.startTimeMs, stopTimeMs, elapsedMs)
    }
    val onReturnToJob: () -> Unit = {
        val snap = clockInState.snapshot
        when (snap.tabType) {
            "hardwoods" -> coordinator.openHardwoodsJobInJobs(snap.folderName)
            "assembly" -> coordinator.openAssemblyViewerInJobs(snap.folderName, 1, 1)
            else -> coordinator.openJobDetailInJobs(snap.folderName)
        }
    }

    androidx.compose.runtime.LaunchedEffect(workMode, basePath) {
        hardwoodsRepository.updateBaseDir(File(basePath))
        assemblyScanCoordinator.updateBasePath(basePath)
        specialtyRepository.updateBaseDir(File(basePath))
        specialtyScanCoordinator.updateBasePath(basePath)
        when (workMode) {
            WorkMode.CNC -> {
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.HARDWOODS -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.ASSEMBLY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.SPECIALTY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(watcherRefreshEpoch, basePath) {
        if (watcherRefreshEpoch <= 0L) return@LaunchedEffect
        supplySubscriptionManager.scanForUpdates()
        when (workMode) {
            WorkMode.CNC -> {
                scanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                assemblyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
            WorkMode.HARDWOODS -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
            WorkMode.ASSEMBLY -> {
                assemblyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
            WorkMode.SPECIALTY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
        }
    }

    val onIdleReset = LocalIdleReset.current
    CompositionLocalProvider(LocalOnOpenSettings provides remember(coordinator) { { coordinator.navigateTopLevel(TopLevelTab.SETTINGS) } }) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onIdleReset) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onIdleReset()
                    }
                }
            }
    ) {
        CompositionLocalProvider(LocalNavBarDecoration provides navBarDeco) {
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            // hazeSource fills full screen (no bottomBar slot) — blur sees content behind the pill
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .hazeSource(hazeState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = paddingValues.calculateTopPadding())
                ) {
                TabLayer(visible = selectedTab == TopLevelTab.DASHBOARD) {
                    DashboardTabHost(
                        navController = dashboardNavController,
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = appStateFlags,
                        workMode = workMode,
                        hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        liveEngine = liveIndexEngine,
                        onNavigateToJobs = {
                            coordinator.navigateTopLevel(TopLevelTab.JOBS)
                        },
                        onOpenJobInJobs = { folderName ->
                            coordinator.openJobDetailInJobs(folderName)
                        },
                        onOpenSpecialtyJobInJobs = { folderName ->
                            coordinator.openSpecialtyJobInJobs(folderName)
                        },
                        onOpenHardwoodsJobInJobs = { folderName ->
                            coordinator.openHardwoodsJobInJobs(folderName)
                        },
                        onOpenSheet = { folderName, pdfFilename, page ->
                            coordinator.openSheetInJobs(folderName, pdfFilename, page)
                        }
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.JOBS) {
                    JobsTabHost(
                        navController = jobsNavController,
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = appStateFlags,
                        isDarkTheme = preferDarkMode,
                        cncSheetIsDarkTheme = isDarkTheme,
                        useStandardSheets = useStandardSheets,
                        continuousScrollDefault = continuousScrollDefault,
                        workMode = workMode,
                        hardwoodsRepository = hardwoodsRepository,
                        hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        sheetRipProgressStore = sheetRipProgressStore,
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        specialtyScanCoordinator = specialtyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        basePath = basePath,
                        tabletId = tabletId,
                        isDebugBuild = isDebugBuild,
                        isViewOnlyMode = isViewOnlyMode,
                        clockInState = clockInState,
                        deliveryScheduleRepository = deliveryScheduleRepository,
                        assemblyViewerDefaultsStore = assemblyViewerDefaultsStore,
                        specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                        pinnedJobsStore = pinnedJobsStore,
                        hazeState = hazeState,
                        onClockIn = onClockIn,
                        onSearchClick = { coordinator.navigateTopLevel(TopLevelTab.SEARCH) },
                        onSettingsClick = { coordinator.navigateTopLevel(TopLevelTab.SETTINGS) },
                        onUiVisibilityChanged = { viewerUiVisible = it },
                        active = selectedTab == TopLevelTab.JOBS,
                        unifiedEngine = liveIndexEngine
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.SEARCH) {
                    SearchTabHost(
                        navController = searchNavController,
                        scanCoordinator = scanCoordinator,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        workMode = workMode,
                        hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        onCncResultClick = { folderName, pdfFilename, page ->
                            coordinator.openSheetInJobs(folderName, pdfFilename, page)
                        },
                        onHardwoodsResultClick = { payload ->
                            val parts = payload.split('|')
                            if (parts.size >= 3) {
                                val folderName = parts[0]
                                val docType = runCatching { HardwoodDocType.valueOf(parts[1]) }
                                    .getOrDefault(HardwoodDocType.FACE_FRAME_CUT_LIST)
                                val rowId = parts[2]
                                coordinator.openHardwoodsRouteInJobs(hardwoodsWorkspaceRoute(folderName, docType, rowId))
                            } else {
                                coordinator.openHardwoodsJobInJobs(payload)
                            }
                        },
                        onAssemblyResultClick = { result ->
                            coordinator.openAssemblyViewerInJobs(
                                jobFolderName = result.jobFolderName,
                                assemblyPage = result.assemblyPage ?: 1,
                                plansPage = result.plansPage ?: 1
                            )
                        },
                        onBack = {
                            coordinator.navigateTopLevel(homeTab)
                        }
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.HOURS) {
                    HoursTabHost(
                        navController = hoursNavController,
                        employeeName = employeeName,
                        isTabSelected = selectedTab == TopLevelTab.HOURS
                    )
                }

                androidx.compose.runtime.LaunchedEffect(selectedTab) {
                    if (selectedTab == TopLevelTab.TIMECARD) {
                        val pin = EmployeeDirectory.records.firstOrNull { it.name == employeeName }?.pin
                        if (pin != null) timecardStore.autoFill(pin)
                    } else {
                        timecardStore.reset()
                    }
                }
                TabLayer(visible = selectedTab == TopLevelTab.TIMECARD) {
                    TimecardScreen(store = timecardStore)
                }

                TabLayer(visible = selectedTab == TopLevelTab.SETTINGS) {
                    SettingsTabHost(
                        navController = settingsNavController,
                        tabletId = tabletId,
                        basePath = basePath,
                        isDebugBuild = isDebugBuild,
                        isDarkTheme = isDarkTheme,
                        followSystemTheme = followSystemTheme,
                        darkThemeOverride = darkThemeOverride,
                        useStandardSheets = useStandardSheets,
                        continuousScrollDefault = continuousScrollDefault,
                        onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                        onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                        workMode = workMode,
                        employeeName = employeeName,
                        onEmployeeNameChanged = onEmployeeNameChanged,
                        onThemeChanged = onThemeChanged,
                        onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                        onWorkModeChanged = onWorkModeChanged,
                        onReinstallLatest = onReinstallLatest,
                        onTabletIdChanged = onTabletIdChanged,
                        onBasePathChanged = onBasePathChanged,
                        syncthingApiKey = syncthingApiKey,
                        syncthingStatus = syncthingStatus,
                        onSyncthingApiKeySave = onSyncthingApiKeySave,
                        onSyncthingCheckNow = onSyncthingCheckNow,
                        onSyncthingStartNow = onSyncthingStartNow,
                        onBack = {
                            coordinator.navigateTopLevel(homeTab)
                        },
                        timecardConfig = timecardConfig,
                        adminSyncConfig = adminSyncConfig,
                        assemblyViewerDefaultsStore = assemblyViewerDefaultsStore,
                        specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                        themeCatalog = themeCatalog,
                        onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                        onThemeOverrideChanged = onThemeOverrideChanged,
                        onThemeCatalogReload = onThemeCatalogReload
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.STANDARDS) {
                    StandardsTabHost(
                        navController = standardsNavController,
                        basePath = basePath,
                        tabletId = tabletId,
                        isDebugBuild = isDebugBuild,
                        workMode = workMode,
                        appStateFlags = appStateFlags,
                        continuousScrollDefault = continuousScrollDefault,
                        specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                        onBack = {
                            coordinator.navigateTopLevel(homeTab)
                        },
                        safetyNotificationCount = safetyNotificationCount,
                        isDarkTheme = isDarkTheme,
                        useStandardSheets = useStandardSheets
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.SUPPLY) {
                    SupplyTabHost(
                        navController = supplyNavController,
                        basePath = basePath,
                        tabletId = tabletId,
                        employeeName = employeeName,
                        subscriptionManager = supplySubscriptionManager,
                        active = selectedTab == TopLevelTab.SUPPLY
                    )
                }

                if (showHoursLoginDialog) {
                    HoursLoginDialog(
                        initialInput = employeeName,
                        suggestions = EmployeeDirectory.suggestions(employeeName).map { "${it.name} (${it.pin})" },
                        onLogin = { name ->
                            showHoursLoginDialog = false
                            launchTimecardApp(context, EmployeeDirectory.resolveNameOrPin(name))
                        },
                        onDismiss = { showHoursLoginDialog = false }
                    )
                }
                pendingClockIn?.let { pending ->
                    val selected = employeeName.takeIf { it.isNotBlank() }.orEmpty()
                    HoursLoginDialog(
                        initialInput = selected,
                        suggestions = EmployeeDirectory.suggestions(selected).map { "${it.name} (${it.pin})" },
                        onLogin = { raw ->
                            val resolved = EmployeeDirectory.resolveNameOrPin(raw)
                            onEmployeeNameChanged(resolved)
                            pendingClockIn = null
                            onClockInNow(pending.jobNumber, pending.jobName, pending.folderName, pending.tabType, resolved)
                        },
                        onDismiss = { pendingClockIn = null }
                    )
                }
                pendingClockOut?.let { pending ->
                    ClockOutEditDialog(
                        jobName = pending.jobName,
                        initialHours = pending.hours,
                        startTimeMs = pending.startTimeMs,
                        stopTimeMs = pending.stopTimeMs,
                        actualElapsedMs = pending.actualElapsedMs,
                        onConfirm = { hours ->
                            pendingClockOut = null
                            launchTimecardApp(context, employeeName.ifBlank { null }, pending.jobNumber, hours.toString())
                        },
                        onDismiss = { pendingClockOut = null }
                    )
                }
                } // inner content Box
            } // hazeSource Box
        }
        } // CompositionLocalProvider

        // Nav bar as true overlay — hazeSource extends behind it so frosted glass works correctly
        Box(modifier = Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            AppBottomNavBar(
                hazeState = hazeState,
                modifier = Modifier
                    .graphicsLayer { alpha = navBarAlpha },
                currentDestination = TopLevelTab.toDestination(selectedTab),
                minimized = isInViewer || (selectedTab == TopLevelTab.SUPPLY && navBarDeco.searchDecoration != null && !navBarDeco.keepSearchDeco),
                destinations = visibleDestinations,
                isCalculatorOpen = calculatorState.snapshot.isOpen,
                onCalculatorClick = { calculatorState.toggleOpen() },
                supplyNotificationCount = supplyNotificationCount,
                safetyNotificationCount = safetyNotificationCount,
                searchDecoration = navBarDeco.searchDecoration,
                cncDecoration = navBarDeco.cncDecoration,
                specialtyDecoration = navBarDeco.specialtyDecoration,
                penDecoration = navBarDeco.penDecoration,
                extendedControls = navBarDeco.extendedControls,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() })
                    } else {
                        val targetTab = TopLevelTab.fromDestination(dest)
                        if ((selectedTab == TopLevelTab.JOBS || selectedTab == TopLevelTab.SUPPLY) &&
                            (targetTab == TopLevelTab.JOBS || targetTab == TopLevelTab.SUPPLY)) {
                            navBarDeco.keepSearchDeco = true
                        }
                        coordinator.navigateTopLevel(targetTab)
                    }
                }
            )
        }

        CalculatorOverlayHost(
            state = calculatorState,
            compactWidth = compactWidth,
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize()
        )
        val isCurrentPageActiveClockIn = remember(selectedTab, jobsCurrentRoute, jobsBackStack?.arguments, clockInState.snapshot) {
            val snap = clockInState.snapshot
            if (!snap.isActive) false
            else if (selectedTab != TopLevelTab.JOBS) false
            else {
                val folder = jobsBackStack?.arguments?.getString("folderName")
                folder != null && folder == snap.folderName
            }
        }
        ClockInOverlay(
            clockInState = clockInState,
            onClockOut = onClockOut,
            onReturnToJob = onReturnToJob,
            isCurrentPageActiveClockIn = isCurrentPageActiveClockIn,
            edgePrefs = remember { context.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) },
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize()
        )
    }
    } // LocalOnOpenSettings CompositionLocalProvider
}

@Composable
private fun TabLayer(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .alpha(if (visible) 1f else 0f)
    ) {
        content()
    }
}

@Composable
private fun DashboardTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    workMode: WorkMode,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    hardwoodsProgressStore: HardwoodsProgressStore,
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    liveEngine: UnifiedMetadataEngine,
    onNavigateToJobs: () -> Unit,
    onOpenJobInJobs: (String) -> Unit,
    onOpenSpecialtyJobInJobs: (String) -> Unit,
    onOpenHardwoodsJobInJobs: (String) -> Unit,
    onOpenSheet: (String, String, Int) -> Unit
) {
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("dashboard") {
            when (workMode) {
                WorkMode.CNC -> {
                    UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Cnc(
                            scanCoordinator = scanCoordinator,
                            appStateStore = appStateStore,
                            jobRepository = jobRepository,
                            progressStore = progressStore,
                            appStateFlags = appStateFlags,
                            onNavigateToJobs = onNavigateToJobs,
                            onOpenSheet = onOpenSheet
                        )
                    )
                }
                WorkMode.HARDWOODS -> {
                    UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Hardwoods(
                            scanCoordinator = hardwoodsScanCoordinator,
                            progressStore = hardwoodsProgressStore,
                            liveEngine = liveEngine,
                            onOpenJob = { job ->
                                onOpenHardwoodsJobInJobs(job.folderName)
                            }
                        )
                    )
                }
                WorkMode.ASSEMBLY -> {
                    UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Assembly(
                            scanCoordinator = assemblyScanCoordinator,
                            assemblyStateStore = assemblyStateStore,
                            cncProgressStore = progressStore,
                            hardwoodsProgressStore = hardwoodsProgressStore,
                            specialtyStateStore = specialtyStateStore,
                            onOpenJob = { folderName ->
                                navController.navigate("assembly/job/${URLEncoder.encode(folderName, "UTF-8")}") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    )
                }
                WorkMode.SPECIALTY -> {
                    UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Specialty(
                            specialtyStateStore = specialtyStateStore,
                            onNavigateToJobs = onNavigateToJobs,
                            onOpenJob = { folderName ->
                                onOpenSpecialtyJobInJobs(folderName)
                            }
                        )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun JobsTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    isDarkTheme: Boolean,
    cncSheetIsDarkTheme: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean = false,
    workMode: WorkMode,
    hardwoodsRepository: HardwoodsRepository,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    hardwoodsProgressStore: HardwoodsProgressStore,
    sheetRipProgressStore: SheetRipProgressStore,
    assemblyScanCoordinator: AssemblyScanCoordinator,
    specialtyScanCoordinator: SpecialtyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    isViewOnlyMode: Boolean,
    clockInState: ClockInState,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    assemblyViewerDefaultsStore: AssemblyViewerDefaultsStore,
    specialtyViewerDefaultsStore: SpecialtyViewerDefaultsStore,
    pinnedJobsStore: PinnedJobsStore,
    hazeState: HazeState,
    onClockIn: (jobNumber: String, jobName: String, folderName: String, tabType: String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUiVisibilityChanged: (Boolean) -> Unit = {},
    active: Boolean = true,
    unifiedEngine: UnifiedMetadataEngine
) {
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val pinnedFolderNames by pinnedJobsStore.pinnedFolderNames.collectAsState(initial = emptyList())
    val jobsBackStack by navController.currentBackStackEntryAsState()
    val jobsListActive = active && isJobsListRoute(jobsBackStack?.destination?.route)
    val onTogglePin: (String, Boolean) -> Unit = { folder, pinned ->
        coroutineScope.launch { pinnedJobsStore.toggle(folder, pinned) }
    }
    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "jobs",
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(200))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(200))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {
        composable("jobs") {
            val spec = when (workMode) {
                WorkMode.CNC -> com.kkc.sheettracker.ui.jobs.rememberCncJobsSpec(
                    scanCoordinator = scanCoordinator,
                    appStateStore = appStateStore,
                    progressStore = progressStore,
                    jobRepository = jobRepository,
                    hardwoodsRepository = hardwoodsRepository,
                    engine = unifiedEngine,
                    coroutineScope = coroutineScope,
                    onJobClick = { jobFolder ->
                        navController.navigate("job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                    },
                    onView3D = { jobFolder ->
                        val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                        navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                    },
                    onViewCoverSheet = { jobFolder ->
                        navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                    }
                )
                WorkMode.HARDWOODS -> com.kkc.sheettracker.ui.jobs.rememberHardwoodsJobsSpec(
                    scanCoordinator = hardwoodsScanCoordinator,
                    hardwoodsRepository = hardwoodsRepository,
                    progressStore = hardwoodsProgressStore,
                    jobRepository = jobRepository,
                    engine = unifiedEngine,
                    coroutineScope = coroutineScope,
                    onJobClick = { jobFolder ->
                        navController.navigate("hardwoods/job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                    },
                    onView3D = { jobFolder ->
                        val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                        navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                    },
                    onViewCoverSheet = { jobFolder ->
                        navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                    }
                )
                WorkMode.ASSEMBLY -> com.kkc.sheettracker.ui.jobs.rememberAssemblyJobsSpec(
                    assemblyScanCoordinator = assemblyScanCoordinator,
                    assemblyStateStore = assemblyStateStore,
                    jobRepository = jobRepository,
                    engine = unifiedEngine,
                    progressStore = progressStore,
                    hardwoodsProgressStore = hardwoodsProgressStore,
                    coroutineScope = coroutineScope,
                    onJobClick = { jobFolder ->
                        coroutineScope.launch {
                            val d = assemblyViewerDefaultsStore.current()
                            navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, layout = d.layout, firstPane = d.firstPane, secondPane = d.secondPane, hideUiOnOpen = d.hideUiOnOpen)) { launchSingleTop = true }
                        }
                    },
                    onView3D = { jobFolder -> }, // not used
                    onViewCoverSheet = { jobFolder ->
                        navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                    }
                )
                WorkMode.SPECIALTY -> com.kkc.sheettracker.ui.jobs.rememberSpecialtyJobsSpec(
                    specialtyScanCoordinator = specialtyScanCoordinator,
                    specialtyStateStore = specialtyStateStore,
                    jobRepository = jobRepository,
                    engine = unifiedEngine,
                    coroutineScope = coroutineScope,
                    onJobClick = { jobFolder ->
                        navController.navigate(specialtyJobRoute(jobFolder)) { launchSingleTop = true }
                    },
                    onView3D = { jobFolder ->
                        val room = resolveSpecialtyThreeDRoom(File(basePath), jobFolder)
                        if (room != null) {
                            navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, source = "3d", room = room)) { launchSingleTop = true }
                        }
                    },
                    onViewCoverSheet = { jobFolder ->
                        navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                    }
                )
            }

            com.kkc.sheettracker.ui.jobs.UnifiedJobsScreen(
                spec = spec,
                jobRepository = jobRepository,
                deliveryScheduleRepository = deliveryScheduleRepository,
                basePath = basePath,
                tabletId = tabletId,
                isDebugBuild = isDebugBuild,
                pinnedFolderNames = pinnedFolderNames,
                onTogglePin = onTogglePin,
                onJobClick = { model -> model.onCardClick() },
                onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                    navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                        launchSingleTop = true
                    }
                },
                onSearchClick = onSearchClick,
                onSettingsClick = onSettingsClick,
                active = jobsListActive
            )
        }

        composable(
            "assembly/job/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            AssemblyJobDetailScreen(
                jobFolderName = folderName,
                assemblyStateStore = assemblyStateStore,
                specialtyStateStore = specialtyStateStore,
                jobRepository = jobRepository,
                onOpenSplitView = {
                    navController.navigate(assemblyViewerRoute(folderName, 1, 1)) {
                        launchSingleTop = true
                    }
                },
                onJumpToCabinet = { cab ->
                    navController.navigate(assemblyViewerRoute(folderName, 1, 1, cabinet = cab)) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "job/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            // Phase 2: verify cache freshness for this job in the background
            LaunchedEffect(folderName) { scanCoordinator.refreshJobOnOpen(folderName) }
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName
            JobDetailScreen(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                specialtyStateStore = specialtyStateStore,
                appStateFlags = appStateFlags,
                jobFolderName = folderName,
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "cnc") },
                clockInState = clockInState,
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedVisibilityScope = this,
                onLeaveWhileClockedIn = {
                    if (isClockedInHere) {
                        val dest = navController.currentDestination?.route ?: ""
                        val isCncSubScreen = dest.startsWith("viewer/") || dest.startsWith("referenceViewer/") || dest.startsWith("assembly/viewer/")
                        if (!isCncSubScreen) clockInState.triggerPrompt()
                    }
                },
                onMaterialClick = { material, startPage ->
                    navController.navigate(
                        "viewer/${URLEncoder.encode(folderName, "UTF-8")}/${URLEncoder.encode(material.pdfFilename, "UTF-8")}/$startPage"
                    ) {
                        launchSingleTop = true
                    }
                },
                onOpenReferenceDocument = { docType, startPage ->
                    navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                        launchSingleTop = true
                    }
                },
                onOpenThreeD = {
                    val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, folderName)
                    navController.navigate(
                        assemblyViewerRoute(
                            jobFolderName = folderName,
                            assemblyPage = target.assemblyPage,
                            plansPage = target.plansPage,
                            source = "3d",
                            room = target.room
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onSubmitPendingBadParts = { material ->
                    progressStore.submitPendingBadParts(
                        jobFolderName = folderName,
                        pdfFilename = material.pdfFilename,
                        fileFingerprint = material.fileFingerprint
                    )
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "specialty/job/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val availability by produceState(SpecialtyAvailability(), folderName) {
                value = withContext(Dispatchers.IO) {
                    loadSpecialtyAvailability(jobRepository, folderName)
                }
            }
            SpecialtyJobDetailScreen(
                jobFolderName = folderName,
                specialtyStateStore = specialtyStateStore,
                specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                jobRepository = jobRepository,
                hasAssemblySheet = availability.hasAssemblySheet,
                hasPlansElevations = availability.hasPlansElevations,
                hasDeliverySheet = availability.hasDeliverySheet,
                hasThreeDAssets = availability.hasThreeDAssets,
                hasClosetRods = availability.hasClosetRods,
                onOpenReferenceDocument = { docType, startPage ->
                    navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                        launchSingleTop = true
                    }
                },
                onOpenThreeD = {
                    val room = resolveSpecialtyThreeDRoom(File(basePath), folderName)
                    if (room != null) {
                        navController.navigate(
                            assemblyViewerRoute(
                                jobFolderName = folderName,
                                assemblyPage = 1,
                                plansPage = 1,
                                source = "3d",
                                room = room
                            )
                        ) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenDoorPanels = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.DOOR_CUT_LIST,
                            HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onOpenSawRipList = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.DOOR_CUT_LIST,
                            HARDWOODS_SAW_RIP_LIST_ROW_ID
                        )
                    ) { launchSingleTop = true }
                },
                onOpenClosetRods = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.CLOSET_ROD_CUT_LIST,
                            null
                        )
                    ) { launchSingleTop = true }
                },
                onOpenSplitView = {
                    navController.navigate(assemblyViewerRoute(folderName, 1, 1)) {
                        launchSingleTop = true
                    }
                },
                onJumpToCabinet = { cab ->
                    navController.navigate(assemblyViewerRoute(folderName, 1, 1, cabinet = cab)) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            SPECIALTY_DOOR_PANELS_ROUTE_PATTERN,
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            androidx.compose.runtime.LaunchedEffect(folderName) {
                navController.navigate(
                    hardwoodsWorkspaceRoute(
                        folderName,
                        HardwoodDocType.DOOR_CUT_LIST,
                        HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
                    )
                ) {
                    launchSingleTop = true
                }
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        composable(
            "viewer/{folderName}/{pdfFilename}/{startPage}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("pdfFilename") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.IntType }
            )
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val pdfFilename = URLDecoder.decode(backStack.arguments?.getString("pdfFilename") ?: "", "UTF-8")
            val startPage = backStack.arguments?.getInt("startPage") ?: 1
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName &&
                clockInState.snapshot.tabType == "cnc"
            SheetViewerScreen(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                appStateFlags = appStateFlags,
                jobFolderName = folderName,
                pdfFilename = pdfFilename,
                startPage = startPage,
                isDarkTheme = cncSheetIsDarkTheme,
                useStandardSheets = useStandardSheets,
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "cnc") },
                clockInState = clockInState,
                pdfMarkupReadOnly = isViewOnlyMode,
                onOpenReferenceDocument = { docType, startAt ->
                    navController.navigate(referenceViewerRoute(folderName, docType, startAt)) {
                        launchSingleTop = true
                    }
                },
                onOpenThreeDTarget = { cabinet, assemblyPage, plansPage, room ->
                    navController.navigate(
                        assemblyViewerRoute(
                            jobFolderName = folderName,
                            assemblyPage = assemblyPage ?: 1,
                            plansPage = plansPage ?: 1,
                            source = "3d",
                            cabinet = cabinet,
                            room = room
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onMaterialUnavailable = {
                    navController.navigate("job/${URLEncoder.encode(folderName, "UTF-8")}") {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                onUiVisibilityChanged = onUiVisibilityChanged
            )
        }

        composable(
            "referenceViewer/{folderName}/{docType}/{startPage}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("docType") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.IntType }
            )
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val rawDocType = URLDecoder.decode(backStack.arguments?.getString("docType") ?: "", "UTF-8")
            val docType = runCatching { ReferenceDocType.valueOf(rawDocType) }.getOrDefault(ReferenceDocType.ASSEMBLY)
            val startPage = backStack.arguments?.getInt("startPage") ?: 1
            val refreshGeneration = scanCoordinator.state.collectAsState().value.snapshot.generation
            ReferencePdfViewerScreen(
                jobRepository = jobRepository,
                jobFolderName = folderName,
                docType = docType,
                startPage = startPage,
                refreshGeneration = refreshGeneration,
                continuousScrollDefault = continuousScrollDefault,
                pdfMarkupReadOnly = isViewOnlyMode,
                isDarkTheme = isDarkTheme,
                onBack = { navController.popBackStack() },
                onUiVisibilityChanged = onUiVisibilityChanged
            )
        }

        composable(
            "hardwoods/job/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            // Phase 2: verify cache freshness for this job in the background
            LaunchedEffect(folderName) { hardwoodsScanCoordinator.refreshJobOnOpen(folderName) }
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName
            HardwoodsJobDetailScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                progressStore = hardwoodsProgressStore,
                jobRepository = jobRepository,
                specialtyStateStore = specialtyStateStore,
                jobFolderName = folderName,
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
                clockInState = clockInState,
                onLeaveWhileClockedIn = {
                    if (isClockedInHere) {
                        val dest = navController.currentDestination?.route ?: ""
                        if (!dest.startsWith("hardwoods/workspace/")) clockInState.triggerPrompt()
                    }
                },
                onOpenWorkspace = { docType ->
                    navController.navigate(hardwoodsWorkspaceRoute(folderName, docType, null)) {
                        launchSingleTop = true
                    }
                },
                onOpenRipCutList = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.FACE_FRAME_CUT_LIST,
                            HARDWOODS_RIP_CUT_LIST_ROW_ID
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onOpenReferenceDocument = { docType, startPage ->
                    navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                        launchSingleTop = true
                    }
                },
                onOpenThreeD = {
                    val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, folderName)
                    navController.navigate(
                        assemblyViewerRoute(
                            jobFolderName = folderName,
                            assemblyPage = target.assemblyPage,
                            plansPage = target.plansPage,
                            source = "3d",
                            room = target.room
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "hardwoods/workspace/{folderName}/{docType}/{startPage}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("docType") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.StringType }
            )
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val rawDocType = URLDecoder.decode(backStack.arguments?.getString("docType") ?: "", "UTF-8")
            val docType = runCatching { HardwoodDocType.valueOf(rawDocType) }.getOrDefault(HardwoodDocType.FACE_FRAME_CUT_LIST)
            val rowIdArg = URLDecoder.decode(backStack.arguments?.getString("startPage") ?: "", "UTF-8")
            val rowId = rowIdArg.takeIf { it.isNotBlank() && it != "_" }
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName &&
                clockInState.snapshot.tabType == "hardwoods"
            HardwoodsWorkspaceScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                hardwoodsRepository = hardwoodsRepository,
                hardwoodsProgressStore = hardwoodsProgressStore,
                sheetRipProgressStore = sheetRipProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
                initialDocType = docType,
                initialRowId = rowId,
                continuousScrollDefault = continuousScrollDefault,
                isDarkTheme = isDarkTheme,
                isClockedInHere = isClockedInHere,
                pdfMarkupReadOnly = isViewOnlyMode,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
                clockInState = clockInState,
                onOpenThreeDTarget = { cabinet, assemblyPage, plansPage, room ->
                    navController.navigate(
                        assemblyViewerRoute(
                            jobFolderName = folderName,
                            assemblyPage = assemblyPage ?: 1,
                            plansPage = plansPage ?: 1,
                            source = "3d",
                            cabinet = cabinet,
                            room = room
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}?source={source}&cab={cab}&room={room}&layout={layout}&first={first}&second={second}&hideUi={hideUi}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("startPageAssembly") { type = NavType.IntType },
                navArgument("startPagePlans") { type = NavType.IntType },
                navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("cab") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("room") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("layout") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("first") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("second") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("hideUi") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStack ->
            val jobFolderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            // Phase 2: verify cache freshness for this job in the background
            LaunchedEffect(jobFolderName) { assemblyScanCoordinator.refreshJobOnOpen(jobFolderName) }
            val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
            val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
            val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialLayout = backStack.arguments?.getString("layout")
                ?.let { runCatching { AssemblyViewLayout.valueOf(it) }.getOrNull() }
            val initialFirstPane = backStack.arguments?.getString("first")
                ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
            val initialSecondPane = backStack.arguments?.getString("second")
                ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
            val initialHideUi = backStack.arguments?.getString("hideUi") == "1"
            val refreshGeneration = assemblyScanCoordinator.state.collectAsState().value.snapshot.generation
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == jobFolderName
            AssemblyViewerScreen(
                jobRepository = jobRepository,
                assemblyStateStore = assemblyStateStore,
                specialtyStateStore = specialtyStateStore,
                jobFolderName = jobFolderName,
                basePath = basePath,
                startPageAssembly = startPageAssembly,
                startPagePlans = startPagePlans,
                initialSource = initialSource,
                initialCabinet = initialCabinet,
                initialRoom = initialRoom,
                initialLayout = initialLayout,
                initialFirstPane = initialFirstPane,
                initialSecondPane = initialSecondPane,
                initialHideUi = initialHideUi,
                refreshGeneration = refreshGeneration,
                continuousScrollDefault = continuousScrollDefault,
                isDarkTheme = isDarkTheme,
                isClockedInHere = isClockedInHere,
                pdfMarkupReadOnly = isViewOnlyMode,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, jobFolderName, "assembly") },
                onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
                onBack = { navController.popBackStack() },
                clockInState = clockInState,
                onUiVisibilityChanged = onUiVisibilityChanged
            )
        }
    }
}
}

@Composable
private fun SearchTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    workMode: WorkMode,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    onCncResultClick: (String, String, Int) -> Unit,
    onHardwoodsResultClick: (String) -> Unit,
    onAssemblyResultClick: (AssemblySearchEntry) -> Unit,
    onBack: () -> Unit
) {
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    NavHost(
        navController = navController,
        startDestination = "search",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("search") {
            when (workMode) {
                WorkMode.CNC -> {
                    SearchScreen(
                        scanCoordinator = scanCoordinator,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        onResultClick = onCncResultClick,
                        onBack = onBack
                    )
                }
                WorkMode.HARDWOODS -> {
                    HardwoodsSearchScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        onResultClick = { folderName, docType, rowId ->
                            onHardwoodsResultClick("${folderName}|${docType.name}|${rowId}")
                        },
                        onBack = onBack
                    )
                }
                WorkMode.ASSEMBLY -> {
                    AssemblySearchScreen(
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        specialtyProgressVersionHint = specialtyProgressVersion,
                        onResultClick = onAssemblyResultClick,
                        onBack = onBack
                    )
                }
                WorkMode.SPECIALTY -> {
                    SearchScreen(
                        scanCoordinator = scanCoordinator,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        onResultClick = onCncResultClick,
                        onBack = onBack
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabHost(
    navController: NavHostController,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
    onBasePathChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    onBack: () -> Unit,
    timecardConfig: TimecardServerConfig,
    adminSyncConfig: AdminSyncConfig,
    assemblyViewerDefaultsStore: AssemblyViewerDefaultsStore,
    specialtyViewerDefaultsStore: SpecialtyViewerDefaultsStore,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = "settings",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("settings") {
            SettingsScreen(
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                followSystemTheme = followSystemTheme,
                darkThemeOverride = darkThemeOverride,
                useStandardSheets = useStandardSheets,
                continuousScrollDefault = continuousScrollDefault,
                onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                workMode = workMode,
                onThemeChanged = onThemeChanged,
                onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onTabletIdChanged = onTabletIdChanged,
                onBasePathChanged = onBasePathChanged,
                syncthingApiKey = syncthingApiKey,
                syncthingStatus = syncthingStatus,
                onSyncthingApiKeySave = onSyncthingApiKeySave,
                onSyncthingCheckNow = onSyncthingCheckNow,
                onSyncthingStartNow = onSyncthingStartNow,
                onBack = onBack,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                timecardConfig = timecardConfig,
                adminSyncConfig = adminSyncConfig,
                themeCatalog = themeCatalog,
                onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                onThemeOverrideChanged = onThemeOverrideChanged,
                onThemeCatalogReload = onThemeCatalogReload,
                onOpenAssemblyViewerDefaults = {
                    navController.navigate("settings/assemblyViewerDefaults") {
                        launchSingleTop = true
                    }
                },
onOpenSpecialtyViewerDefaults = {
                        navController.navigate("settings/specialtyViewerDefaults") {
                            launchSingleTop = true
                        }
                    },
                    uiPreferencesStore = UiPreferencesStore(LocalContext.current),
                    idlePowerSaveStore = IdlePowerSaveStore(LocalContext.current),
                )
        }
        composable("settings/assemblyViewerDefaults") {
            AssemblyViewerDefaultsScreen(
                store = assemblyViewerDefaultsStore,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings/specialtyViewerDefaults") {
            SpecialtyViewerDefaultsScreen(
                store = specialtyViewerDefaultsStore,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun StandardsTabHost(
    navController: NavHostController,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    workMode: WorkMode,
    appStateFlags: AppStateFeatureFlags,
    continuousScrollDefault: Boolean,
    specialtyViewerDefaultsStore: com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore,
    onBack: () -> Unit,
    safetyNotificationCount: Int = 0,
    isDarkTheme: Boolean = false,
    useStandardSheets: Boolean = false
) {
    NavHost(
        navController = navController,
        startDestination = "standards",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("standards") {
            com.kkc.sheettracker.ui.standards.StandardsHubScreen(
                onBack = onBack,
                onOpenMolding = { navController.navigate("standards/molding") { launchSingleTop = true } },
                onOpenSafety = { navController.navigate("standards/safety") { launchSingleTop = true } },
                onOpenArchive = { navController.navigate("standards/archive") { launchSingleTop = true } },
                safetyNotificationCount = safetyNotificationCount
            )
        }
        composable("standards/molding") {
            val repository = remember(basePath) {
                com.kkc.sheettracker.data.MoldingLibraryRepository(File(basePath))
            }
            com.kkc.sheettracker.ui.standards.MoldingListScreen(
                repository = repository,
                onBack = { navController.popBackStack() },
                isDarkTheme = isDarkTheme,
                useStandardSheets = useStandardSheets
            )
        }
        composable("standards/safety") {
            com.kkc.sheettracker.ui.standards.SafetyDocumentsScreen(
                basePath = basePath,
                onBack = { navController.popBackStack() }
            )
        }
        composable("standards/archive") {
            ArchiveLibraryHost(
                tabletId = tabletId,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                useStandardSheets = useStandardSheets,
                continuousScrollDefault = continuousScrollDefault,
                specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                workMode = workMode,
                appStateFlags = appStateFlags,
                onExitArchive = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun SupplyTabHost(
    navController: NavHostController,
    basePath: String,
    tabletId: String,
    employeeName: String,
    subscriptionManager: SupplySubscriptionManager,
    active: Boolean = true
) {
    NavHost(
        navController = navController,
        startDestination = "supply",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("supply") {
            SupplyDashboardScreen(
                basePath = basePath,
                tabletId = tabletId,
                employeeName = employeeName,
                subscriptionManager = subscriptionManager,
                active = active
            )
        }
    }
}

@Composable
private fun ArchiveLibraryHost
(
    tabletId: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    specialtyViewerDefaultsStore: com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore,
    workMode: WorkMode,
    appStateFlags: AppStateFeatureFlags,
    onExitArchive: () -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = "archive",
        modifier = Modifier.fillMaxSize(),
    ) {
        composable("archive") {
            com.kkc.sheettracker.ui.archive.ArchiveLibraryScreen(
                tabletId = tabletId,
                isDebugBuild = isDebugBuild,
                onOpenArchiveJob = { archiveJobId, folderName, contentVersion ->
                    navController.navigate(
                        "archive/job/${URLEncoder.encode(archiveJobId, "UTF-8")}/${URLEncoder.encode(folderName, "UTF-8")}/${URLEncoder.encode(contentVersion, "UTF-8")}"
                    ) { launchSingleTop = true }
                },
            )
        }
        composable(
            "archive/job/{archiveJobId}/{folderName}/{contentVersion}",
            arguments = listOf(
                navArgument("archiveJobId") { type = NavType.StringType },
                navArgument("folderName") { type = NavType.StringType },
                navArgument("contentVersion") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val archiveJobId = URLDecoder.decode(backStackEntry.arguments?.getString("archiveJobId").orEmpty(), "UTF-8")
            val folderName = URLDecoder.decode(backStackEntry.arguments?.getString("folderName").orEmpty(), "UTF-8")
            val contentVersion = URLDecoder.decode(backStackEntry.arguments?.getString("contentVersion").orEmpty(), "UTF-8")
            ArchiveJobDetailHost(
                archiveJobId = archiveJobId,
                folderName = folderName,
                contentVersion = contentVersion,
                cacheJobParentDir = File(context.cacheDir, "archive-cache/$archiveJobId"),
                tabletId = tabletId,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                useStandardSheets = useStandardSheets,
                continuousScrollDefault = continuousScrollDefault,
                specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                workMode = workMode,
                appStateFlags = appStateFlags,
                onExitArchive = onExitArchive,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LegacySingleStackNavigation(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyProgressStore: SpecialtyProgressStore,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isViewOnlyMode: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    watcherRefreshEpoch: Long,
    supplySubscriptionManager: SupplySubscriptionManager,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    unifiedEngine: UnifiedMetadataEngine
) {
    val preferDarkMode = isDarkTheme && !useStandardSheets
    val supplyNotificationCount by supplySubscriptionManager.notificationCount.collectAsState()
    val legacySafetyRepository = remember(basePath) { SafetyRepository(basePath) }
    val legacySafetySubscriptionManager = remember(legacySafetyRepository) { SafetySubscriptionManager(legacySafetyRepository) }
    val safetyNotificationCount by legacySafetySubscriptionManager.notificationCount.collectAsState()
    val calculatorState = rememberCalculatorOverlayState()
    val compactWidth = rememberCompactWidthClass()
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
    val specialtyRepository = remember(basePath, specialtyProgressStore) {
        SpecialtyRepository(File(basePath), specialtyProgressStore)
    }
    val specialtyScanCoordinator = remember(specialtyRepository) { SpecialtyScanCoordinator(specialtyRepository) }
    val assemblyScanCoordinator = remember(basePath) { AssemblyScanCoordinator(File(basePath), jobRepository) }
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore, unifiedEngine) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            liveEngine = unifiedEngine
        )
    }
    val sheetRipProgressStore = remember(basePath) {
        SheetRipProgressStore(File(basePath))
    }
    val deliveryScheduleRepository = remember(basePath) {
        DeliveryScheduleRepository(File(basePath))
    }
    val tabletSpecialtyItemsStore = remember(basePath, tabletId) {
        TabletSpecialtyItemsStore(File(basePath), tabletId)
    }
    val specialtyStateStore = remember(specialtyScanCoordinator, specialtyProgressStore, hardwoodsProgressStore, sheetRipProgressStore, tabletSpecialtyItemsStore, basePath) {
        SpecialtyStateStore(
            specialtyScanCoordinator = specialtyScanCoordinator,
            specialtyProgressStore = specialtyProgressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            sheetRipProgressStore = sheetRipProgressStore,
            tabletItemsStore = tabletSpecialtyItemsStore,
            baseDir = File(basePath)
        )
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val startRoute = if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) "jobs" else "dashboard"
    var pendingClockOut by remember { mutableStateOf<PendingClockOut?>(null) }
    var pendingClockIn by remember { mutableStateOf<PendingClockIn?>(null) }
    var showHoursLoginDialog by remember { mutableStateOf(false) }
    val visibleDestinations = remember(workMode) {
        // SETTINGS is reached via the top bar's Settings icon (LocalOnOpenSettings), not the
        // bottom nav bar — filtered out of both branches here.
        if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) {
            listOf(NavDestination.JOBS, NavDestination.HOURS, NavDestination.TIMECARD, NavDestination.SUPPLY, NavDestination.STANDARDS)
        } else {
            NavDestination.entries.filter {
                it != NavDestination.SEARCH && it != NavDestination.SETTINGS
            }
        }
    }
    fun openSheetLegacy(jobFolderName: String, pdfFilename: String, page: Int) {
        if (isCurrentViewerTarget(backStackEntry, jobFolderName, pdfFilename, page)) return
        navController.navigate(viewerRoute(jobFolderName, pdfFilename, page)) {
            launchSingleTop = true
        }
    }

    val legacyContext = LocalContext.current
    val legacyTimecardConfig = remember { TimecardServerConfig.create(legacyContext) }
    val legacyAdminSyncConfig = remember { AdminSyncConfig.create(legacyContext) }
    val legacyAssemblyViewerDefaultsStore = remember { AssemblyViewerDefaultsStore.create(legacyContext) }
    val legacySpecialtyViewerDefaultsStore = remember { SpecialtyViewerDefaultsStore.create(legacyContext) }
    val legacyCoroutineScope = rememberCoroutineScope()
    val legacyPinnedJobsStore = remember { PinnedJobsStore.create(legacyContext) }
    val pinnedFolderNames by legacyPinnedJobsStore.pinnedFolderNames.collectAsState(initial = emptyList())
    val onTogglePin: (String, Boolean) -> Unit = { folder, pinned ->
        legacyCoroutineScope.launch { legacyPinnedJobsStore.toggle(folder, pinned) }
    }
    val legacyTimecardDiscovery = remember { TimecardDiscovery(legacyContext) }
    val legacyTimeclockMessagesRepo = remember { TimeclockMessagesRepository(File(basePath)) }
    val legacyTimecardStore = remember { TimecardStore(legacyTimecardConfig, legacyTimecardDiscovery, legacyTimeclockMessagesRepo, File(basePath)) }
    DisposableEffect(legacyTimecardStore) { onDispose { legacyTimecardStore.cancel() } }
    LaunchedEffect(basePath) { EmployeeDirectory.refresh(File(basePath)) }
    val onClockInNow: (jobNumber: String, jobName: String, folderName: String, tabType: String, employee: String) -> Unit =
        { jobNumber, jobName, folderName, tabType, employee ->
            clockInState.clockIn(jobNumber, formattedClockInJobName(jobName, employee), folderName, tabType)
            ClockInNotificationContract.startOrUpdateService(legacyContext)
        }
    val onClockIn: (jobNumber: String, jobName: String, folderName: String, tabType: String) -> Unit =
        { jobNumber, jobName, folderName, tabType ->
            when (val gate = resolveClockInGate(employeeName, jobNumber, jobName, folderName, tabType)) {
                is ClockInGateResult.NeedsLogin -> pendingClockIn = gate.pending
                is ClockInGateResult.Ready -> onClockInNow(gate.jobNumber, gate.jobName, gate.folderName, gate.tabType, gate.employee)
            }
        }
    val onClockOut: () -> Unit = {
        val snap = clockInState.snapshot
        val stopTimeMs = System.currentTimeMillis()
        val elapsedMs = clockInState.clockOut()
        ClockInNotificationContract.stopService(legacyContext)
        val elapsedHours = (Math.round(elapsedMs / 3600000.0 * 4) / 4.0).coerceAtLeast(0.25)
        pendingClockOut = PendingClockOut(snap.jobName, snap.jobNumber, elapsedHours, snap.startTimeMs, stopTimeMs, elapsedMs)
    }
    val onReturnToJob: () -> Unit = {
        val snap = clockInState.snapshot
        when (snap.tabType) {
            "hardwoods" -> navController.navigate("hardwoods/job/${java.net.URLEncoder.encode(snap.folderName, "UTF-8")}") { launchSingleTop = true }
            "assembly" -> navController.navigate(assemblyViewerRoute(snap.folderName, 1, 1)) { launchSingleTop = true }
            else -> navController.navigate("job/${java.net.URLEncoder.encode(snap.folderName, "UTF-8")}") { launchSingleTop = true }
        }
    }

    androidx.compose.runtime.LaunchedEffect(workMode, basePath) {
        hardwoodsRepository.updateBaseDir(File(basePath))
        assemblyScanCoordinator.updateBasePath(basePath)
        specialtyRepository.updateBaseDir(File(basePath))
        specialtyScanCoordinator.updateBasePath(basePath)
        when (workMode) {
            WorkMode.CNC -> {
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.HARDWOODS -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.ASSEMBLY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.SPECIALTY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(watcherRefreshEpoch, basePath) {
        if (watcherRefreshEpoch <= 0L) return@LaunchedEffect
        supplySubscriptionManager.scanForUpdates()
        when (workMode) {
            WorkMode.CNC -> {
                scanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                assemblyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
            WorkMode.HARDWOODS -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
            WorkMode.ASSEMBLY -> {
                assemblyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
            WorkMode.SPECIALTY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                specialtyScanCoordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
            }
        }
    }

    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()

    val currentNavDest = remember(currentRoute) {
        when {
            currentRoute == "dashboard" && workMode != WorkMode.ASSEMBLY && workMode != WorkMode.SPECIALTY -> NavDestination.DASHBOARD
            currentRoute?.startsWith("jobs") == true ||
            currentRoute?.startsWith("job/") == true ||
                currentRoute?.startsWith("specialty/job/") == true ||
                currentRoute?.startsWith("assembly/job/") == true ||
                currentRoute?.startsWith("hardwoods/job/") == true ||
                currentRoute?.startsWith("hardwoods/workspace/") == true ||
                currentRoute?.startsWith("assembly/viewer/") == true ||
                currentRoute?.startsWith("viewer/") == true ||
                currentRoute?.startsWith("referenceViewer/") == true -> NavDestination.JOBS
            currentRoute == "search" -> NavDestination.SEARCH
            currentRoute == "hours" -> NavDestination.HOURS
            currentRoute == "timecard" -> NavDestination.TIMECARD
            currentRoute?.startsWith("supply") == true -> NavDestination.SUPPLY
            currentRoute == "settings" || currentRoute?.startsWith("settings/") == true -> NavDestination.SETTINGS
            currentRoute == "standards" || currentRoute?.startsWith("standards/") == true -> NavDestination.STANDARDS
            else -> if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) NavDestination.JOBS else NavDestination.DASHBOARD
        }
    }

    val isInViewer = currentRoute?.startsWith("viewer/") == true ||
        currentRoute?.startsWith("referenceViewer/") == true ||
        currentRoute?.startsWith("hardwoods/workspace/") == true ||
        currentRoute?.startsWith("assembly/viewer/") == true ||
        currentRoute?.startsWith("specialty/job/") == true
    var viewerUiVisible by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(isInViewer) { if (!isInViewer) viewerUiVisible = true }
    val navBarAlpha by animateFloatAsState(
        if (!isInViewer || viewerUiVisible) 1f else 0f,
        tween(286), label = "navBarAlpha"
    )
    val hazeState = remember { HazeState() }
    val navBarDeco = remember { NavBarDecorationState() }

    // Safety net for leaving a viewer route (e.g. the hardwoods workspace's Classic cut
    // list, which hosts its pen toolbar via extendedControls): that screen's own
    // DisposableEffect clears extendedControls when it leaves composition, but that's
    // driven by NavHost's teardown timing, not by this route classification. If the two
    // land a frame apart, the nav bar briefly renders "not extended, still minimized" —
    // the plain icon-only pill — before `minimized` catches up and it grows back out to
    // the full labeled bar, showing up as a jerky two-step collapse instead of one smooth
    // shrink. Clearing it here too, off the exact same isInViewer signal that drives
    // `minimized`, keeps both changes on the same trigger.
    LaunchedEffect(isInViewer) {
        if (!isInViewer) navBarDeco.extendedControls = null
    }

    val onIdleReset = LocalIdleReset.current
    CompositionLocalProvider(LocalOnOpenSettings provides remember(navController) { { navController.navigate("settings") { launchSingleTop = true } } }) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onIdleReset) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onIdleReset()
                    }
                }
            }
    ) {
        CompositionLocalProvider(LocalNavBarDecoration provides navBarDeco) {
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .hazeSource(hazeState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = paddingValues.calculateTopPadding())
                ) {
                SharedTransitionLayout {
                    NavHost(
                        navController = navController,
                        startDestination = startRoute,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(200))
                        },
                        exitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { -it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(200))
                        },
                        popEnterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(200))
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(200))
                        }
                    ) {
                    composable("dashboard") {
                        when (workMode) {
                            WorkMode.CNC -> {
                                UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Cnc(
                                        scanCoordinator = scanCoordinator,
                                        appStateStore = appStateStore,
                                        jobRepository = jobRepository,
                                        progressStore = progressStore,
                                        appStateFlags = appStateFlags,
                                        onNavigateToJobs = {
                                            navController.navigate("jobs") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenSheet = { folderName, pdfFilename, page ->
                                            openSheetLegacy(folderName, pdfFilename, page)
                                        }
                                    )
                                )
                            }
                            WorkMode.HARDWOODS -> {
                                UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Hardwoods(
                                        scanCoordinator = hardwoodsScanCoordinator,
                                        progressStore = hardwoodsProgressStore,
                                        liveEngine = unifiedEngine,
                                        onOpenJob = { job ->
                                            navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                )
                            }
                            WorkMode.ASSEMBLY -> {
                                UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Assembly(
                                        scanCoordinator = assemblyScanCoordinator,
                                        assemblyStateStore = assemblyStateStore,
                                        cncProgressStore = progressStore,
                                        hardwoodsProgressStore = hardwoodsProgressStore,
                                        specialtyStateStore = specialtyStateStore,
                                        onOpenJob = { folderName ->
                                            navController.navigate("assembly/job/${URLEncoder.encode(folderName, "UTF-8")}") {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                )
                            }
                            WorkMode.SPECIALTY -> {
                                UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Specialty(
                                        specialtyStateStore = specialtyStateStore,
                                        onNavigateToJobs = {
                                            navController.navigate("jobs") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenJob = { folderName ->
                                            navController.navigate(specialtyJobRoute(folderName)) {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    }

                    composable("jobs") {
                        val spec = when (workMode) {
                            WorkMode.CNC -> com.kkc.sheettracker.ui.jobs.rememberCncJobsSpec(
                                scanCoordinator = scanCoordinator,
                                appStateStore = appStateStore,
                                progressStore = progressStore,
                                jobRepository = jobRepository,
                                hardwoodsRepository = hardwoodsRepository,
                                engine = unifiedEngine,
                                coroutineScope = legacyCoroutineScope,
                                onJobClick = { jobFolder ->
                                    navController.navigate("job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                                },
                                onView3D = { jobFolder ->
                                    val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                                    navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                                },
                                onViewCoverSheet = { jobFolder ->
                                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                                }
                            )
                            WorkMode.HARDWOODS -> com.kkc.sheettracker.ui.jobs.rememberHardwoodsJobsSpec(
                                scanCoordinator = hardwoodsScanCoordinator,
                                hardwoodsRepository = hardwoodsRepository,
                                progressStore = hardwoodsProgressStore,
                                jobRepository = jobRepository,
                                engine = unifiedEngine,
                                coroutineScope = legacyCoroutineScope,
                                onJobClick = { jobFolder ->
                                    navController.navigate("hardwoods/job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                                },
                                onView3D = { jobFolder ->
                                    val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                                    navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                                },
                                onViewCoverSheet = { jobFolder ->
                                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                                }
                            )
                            WorkMode.ASSEMBLY -> com.kkc.sheettracker.ui.jobs.rememberAssemblyJobsSpec(
                                assemblyScanCoordinator = assemblyScanCoordinator,
                                assemblyStateStore = assemblyStateStore,
                                jobRepository = jobRepository,
                                engine = unifiedEngine,
                                progressStore = progressStore,
                                hardwoodsProgressStore = hardwoodsProgressStore,
                                coroutineScope = legacyCoroutineScope,
                                onJobClick = { jobFolder ->
                                    legacyCoroutineScope.launch {
                                        val d = legacyAssemblyViewerDefaultsStore.current()
                                        navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, layout = d.layout, firstPane = d.firstPane, secondPane = d.secondPane, hideUiOnOpen = d.hideUiOnOpen)) { launchSingleTop = true }
                                    }
                                },
                                onView3D = { jobFolder -> }, // not used
                                onViewCoverSheet = { jobFolder ->
                                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                                }
                            )
                            WorkMode.SPECIALTY -> com.kkc.sheettracker.ui.jobs.rememberSpecialtyJobsSpec(
                                specialtyScanCoordinator = specialtyScanCoordinator,
                                specialtyStateStore = specialtyStateStore,
                                jobRepository = jobRepository,
                                engine = unifiedEngine,
                                coroutineScope = legacyCoroutineScope,
                                onJobClick = { jobFolder ->
                                    navController.navigate(specialtyJobRoute(jobFolder)) { launchSingleTop = true }
                                },
                                onView3D = { jobFolder ->
                                    val room = resolveSpecialtyThreeDRoom(File(basePath), jobFolder)
                                    if (room != null) {
                                        navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, source = "3d", room = room)) { launchSingleTop = true }
                                    }
                                },
                                onViewCoverSheet = { jobFolder ->
                                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                                }
                            )
                        }

                        com.kkc.sheettracker.ui.jobs.UnifiedJobsScreen(
                            spec = spec,
                            jobRepository = jobRepository,
                            deliveryScheduleRepository = deliveryScheduleRepository,
                            basePath = basePath,
                            tabletId = tabletId,
                            isDebugBuild = isDebugBuild,
                            pinnedFolderNames = pinnedFolderNames,
                            onTogglePin = onTogglePin,
                            onJobClick = { model -> model.onCardClick() },
                            onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                                navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                                    launchSingleTop = true
                                }
                            },
                            onSearchClick = { navController.navigate("search") { launchSingleTop = true } },
                            onSettingsClick = { navController.navigate("settings") { launchSingleTop = true } },
                            active = currentNavDest == NavDestination.JOBS && isJobsListRoute(currentRoute)
                        )
                    }

                composable(
                    "job/{folderName}",
                    arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    // Phase 2: verify cache freshness for this job in the background
                    LaunchedEffect(folderName) { scanCoordinator.refreshJobOnOpen(folderName) }
                    val isClockedInHere = clockInState.snapshot.isActive &&
                        clockInState.snapshot.folderName == folderName
                    JobDetailScreen(
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        specialtyStateStore = specialtyStateStore,
                        appStateFlags = appStateFlags,
                        jobFolderName = folderName,
                        isClockedInHere = isClockedInHere,
                        onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "cnc") },
                        clockInState = clockInState,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                        onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
                        onMaterialClick = { material, startPage ->
                            openSheetLegacy(folderName, material.pdfFilename, startPage)
                        },
                        onOpenReferenceDocument = { docType, startPage ->
                            navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenThreeD = {
                            val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, folderName)
                            navController.navigate(
                                assemblyViewerRoute(
                                    jobFolderName = folderName,
                                    assemblyPage = target.assemblyPage,
                                    plansPage = target.plansPage,
                                    source = "3d",
                                    room = target.room
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onSubmitPendingBadParts = { material ->
                            progressStore.submitPendingBadParts(
                                jobFolderName = folderName,
                                pdfFilename = material.pdfFilename,
                                fileFingerprint = material.fileFingerprint
                            )
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    "specialty/job/{folderName}",
                    arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    val availability by produceState(SpecialtyAvailability(), folderName) {
                        value = withContext(Dispatchers.IO) {
                            loadSpecialtyAvailability(jobRepository, folderName)
                        }
                    }
                    SpecialtyJobDetailScreen(
                        jobFolderName = folderName,
                        specialtyStateStore = specialtyStateStore,
                        specialtyViewerDefaultsStore = legacySpecialtyViewerDefaultsStore,
                        jobRepository = jobRepository,
                        hasAssemblySheet = availability.hasAssemblySheet,
                        hasPlansElevations = availability.hasPlansElevations,
                        hasDeliverySheet = availability.hasDeliverySheet,
                        hasThreeDAssets = availability.hasThreeDAssets,
                        hasClosetRods = availability.hasClosetRods,
                        onOpenReferenceDocument = { docType, startPage ->
                            navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenThreeD = {
                            val room = resolveSpecialtyThreeDRoom(File(basePath), folderName)
                            if (room != null) {
                                navController.navigate(
                                    assemblyViewerRoute(
                                        jobFolderName = folderName,
                                        assemblyPage = 1,
                                        plansPage = 1,
                                        source = "3d",
                                        room = room
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onOpenDoorPanels = {
                            navController.navigate(
                                hardwoodsWorkspaceRoute(
                                    folderName,
                                    HardwoodDocType.DOOR_CUT_LIST,
                                    HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onOpenSawRipList = {
                            navController.navigate(
                                hardwoodsWorkspaceRoute(
                                    folderName,
                                    HardwoodDocType.DOOR_CUT_LIST,
                                    HARDWOODS_SAW_RIP_LIST_ROW_ID
                                )
                            ) { launchSingleTop = true }
                        },
                        onOpenClosetRods = {
                            navController.navigate(
                                hardwoodsWorkspaceRoute(
                                    folderName,
                                    HardwoodDocType.CLOSET_ROD_CUT_LIST,
                                    null
                                )
                            ) { launchSingleTop = true }
                        },
                        onOpenSplitView = {
                            navController.navigate(assemblyViewerRoute(folderName, 1, 1)) {
                                launchSingleTop = true
                            }
                        },
                        onJumpToCabinet = { cab ->
                            navController.navigate(assemblyViewerRoute(folderName, 1, 1, cabinet = cab)) {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    "assembly/job/{folderName}",
                    arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    // Phase 2: verify cache freshness for this job in the background
                    LaunchedEffect(folderName) { assemblyScanCoordinator.refreshJobOnOpen(folderName) }
                    AssemblyJobDetailScreen(
                        jobFolderName = folderName,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        jobRepository = jobRepository,
                        onOpenSplitView = {
                            navController.navigate(assemblyViewerRoute(folderName, 1, 1)) {
                                launchSingleTop = true
                            }
                        },
                        onJumpToCabinet = { cab ->
                            navController.navigate(assemblyViewerRoute(folderName, 1, 1, cabinet = cab)) {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    SPECIALTY_DOOR_PANELS_ROUTE_PATTERN,
                    arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    androidx.compose.runtime.LaunchedEffect(folderName) {
                        navController.navigate(
                            hardwoodsWorkspaceRoute(
                                folderName,
                                HardwoodDocType.DOOR_CUT_LIST,
                                HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
                            )
                        ) {
                            launchSingleTop = true
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                composable(
                    "viewer/{folderName}/{pdfFilename}/{startPage}",
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("pdfFilename") { type = NavType.StringType },
                        navArgument("startPage") { type = NavType.IntType }
                    )
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    val pdfFilename = URLDecoder.decode(backStack.arguments?.getString("pdfFilename") ?: "", "UTF-8")
                    val startPage = backStack.arguments?.getInt("startPage") ?: 1
                    val isClockedInHere = clockInState.snapshot.isActive &&
                        clockInState.snapshot.folderName == folderName &&
                        clockInState.snapshot.tabType == "cnc"
                    SheetViewerScreen(
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = appStateFlags,
                        jobFolderName = folderName,
                        pdfFilename = pdfFilename,
                        startPage = startPage,
                        isDarkTheme = isDarkTheme,
                        useStandardSheets = useStandardSheets,
                        isClockedInHere = isClockedInHere,
                        onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "cnc") },
                        clockInState = clockInState,
                        pdfMarkupReadOnly = isViewOnlyMode,
                        onOpenReferenceDocument = { docType, startAt ->
                            navController.navigate(referenceViewerRoute(folderName, docType, startAt)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenThreeDTarget = { cabinet, assemblyPage, plansPage, room ->
                            navController.navigate(
                                assemblyViewerRoute(
                                    jobFolderName = folderName,
                                    assemblyPage = assemblyPage ?: 1,
                                    plansPage = plansPage ?: 1,
                                    source = "3d",
                                    cabinet = cabinet,
                                    room = room
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onMaterialUnavailable = {
                            navController.navigate("job/${URLEncoder.encode(folderName, "UTF-8")}") {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() },
                        onUiVisibilityChanged = { viewerUiVisible = it }
                    )
                }

                composable(
                    "referenceViewer/{folderName}/{docType}/{startPage}",
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("docType") { type = NavType.StringType },
                        navArgument("startPage") { type = NavType.IntType }
                    )
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    val rawDocType = URLDecoder.decode(backStack.arguments?.getString("docType") ?: "", "UTF-8")
                    val docType = runCatching { ReferenceDocType.valueOf(rawDocType) }.getOrDefault(ReferenceDocType.ASSEMBLY)
                    val startPage = backStack.arguments?.getInt("startPage") ?: 1
                    val refreshGeneration = scanCoordinator.state.collectAsState().value.snapshot.generation
                    ReferencePdfViewerScreen(
                        jobRepository = jobRepository,
                        jobFolderName = folderName,
                        docType = docType,
                        startPage = startPage,
                        refreshGeneration = refreshGeneration,
                        continuousScrollDefault = continuousScrollDefault,
                        pdfMarkupReadOnly = isViewOnlyMode,
                        isDarkTheme = preferDarkMode,
                        onBack = { navController.popBackStack() },
                        onUiVisibilityChanged = { viewerUiVisible = it }
                    )
                }

                composable(
                    "hardwoods/job/{folderName}",
                    arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    // Phase 2: verify cache freshness for this job in the background
                    LaunchedEffect(folderName) { hardwoodsScanCoordinator.refreshJobOnOpen(folderName) }
                    val isClockedInHere = clockInState.snapshot.isActive &&
                        clockInState.snapshot.folderName == folderName
                    HardwoodsJobDetailScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        progressStore = hardwoodsProgressStore,
                        jobRepository = jobRepository,
                        specialtyStateStore = specialtyStateStore,
                        jobFolderName = folderName,
                        isClockedInHere = isClockedInHere,
                        onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
                        clockInState = clockInState,
                        onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
                        onOpenWorkspace = { docType ->
                            navController.navigate(hardwoodsWorkspaceRoute(folderName, docType, null)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenRipCutList = {
                            navController.navigate(
                                hardwoodsWorkspaceRoute(
                                    folderName,
                                    HardwoodDocType.FACE_FRAME_CUT_LIST,
                                    HARDWOODS_RIP_CUT_LIST_ROW_ID
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onOpenReferenceDocument = { docType, startPage ->
                            navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenThreeD = {
                            val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, folderName)
                            navController.navigate(
                                assemblyViewerRoute(
                                    jobFolderName = folderName,
                                    assemblyPage = target.assemblyPage,
                                    plansPage = target.plansPage,
                                    source = "3d",
                                    room = target.room
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    "hardwoods/workspace/{folderName}/{docType}/{startPage}",
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("docType") { type = NavType.StringType },
                        navArgument("startPage") { type = NavType.StringType }
                    )
                ) { backStack ->
                    val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    val rawDocType = URLDecoder.decode(backStack.arguments?.getString("docType") ?: "", "UTF-8")
                    val docType = runCatching { HardwoodDocType.valueOf(rawDocType) }.getOrDefault(HardwoodDocType.FACE_FRAME_CUT_LIST)
                    val rowIdArg = URLDecoder.decode(backStack.arguments?.getString("startPage") ?: "", "UTF-8")
                    val rowId = rowIdArg.takeIf { it.isNotBlank() && it != "_" }
                    val isClockedInHere = clockInState.snapshot.isActive &&
                        clockInState.snapshot.folderName == folderName &&
                        clockInState.snapshot.tabType == "hardwoods"
                    HardwoodsWorkspaceScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsRepository = hardwoodsRepository,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        sheetRipProgressStore = sheetRipProgressStore,
                        jobRepository = jobRepository,
                        jobFolderName = folderName,
                        initialDocType = docType,
                        initialRowId = rowId,
                        continuousScrollDefault = continuousScrollDefault,
                        isDarkTheme = preferDarkMode,
                        isClockedInHere = isClockedInHere,
                        pdfMarkupReadOnly = isViewOnlyMode,
                        onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
                        clockInState = clockInState,
                        onOpenThreeDTarget = { cabinet, assemblyPage, plansPage, room ->
                            navController.navigate(
                                assemblyViewerRoute(
                                    jobFolderName = folderName,
                                    assemblyPage = assemblyPage ?: 1,
                                    plansPage = plansPage ?: 1,
                                    source = "3d",
                                    cabinet = cabinet,
                                    room = room
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    "assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}?source={source}&cab={cab}&room={room}&layout={layout}&first={first}&second={second}&hideUi={hideUi}",
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("startPageAssembly") { type = NavType.IntType },
                        navArgument("startPagePlans") { type = NavType.IntType },
                        navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("cab") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("room") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("layout") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("first") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("second") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("hideUi") { type = NavType.StringType; nullable = true; defaultValue = null }
                    )
                ) { backStack ->
                    val jobFolderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                    // Phase 2: verify cache freshness for this job in the background
                    LaunchedEffect(jobFolderName) { assemblyScanCoordinator.refreshJobOnOpen(jobFolderName) }
                    val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
                    val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
                    val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
                    val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
                    val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
                    val initialLayout = backStack.arguments?.getString("layout")
                        ?.let { runCatching { AssemblyViewLayout.valueOf(it) }.getOrNull() }
                    val initialFirstPane = backStack.arguments?.getString("first")
                        ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
                    val initialSecondPane = backStack.arguments?.getString("second")
                        ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
                    val initialHideUi = backStack.arguments?.getString("hideUi") == "1"
                    val refreshGeneration = assemblyScanCoordinator.state.collectAsState().value.snapshot.generation
                    val isClockedInHere = clockInState.snapshot.isActive &&
                        clockInState.snapshot.folderName == jobFolderName
                    AssemblyViewerScreen(
                        jobRepository = jobRepository,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        jobFolderName = jobFolderName,
                        basePath = basePath,
                        initialSource = initialSource,
                        initialCabinet = initialCabinet,
                        initialRoom = initialRoom,
                        initialLayout = initialLayout,
                        initialFirstPane = initialFirstPane,
                        initialSecondPane = initialSecondPane,
                        initialHideUi = initialHideUi,
                        startPageAssembly = startPageAssembly,
                        startPagePlans = startPagePlans,
                        refreshGeneration = refreshGeneration,
                        continuousScrollDefault = continuousScrollDefault,
                        isDarkTheme = preferDarkMode,
                        isClockedInHere = isClockedInHere,
                        pdfMarkupReadOnly = isViewOnlyMode,
                        onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, jobFolderName, "assembly") },
                        onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
                        onBack = { navController.popBackStack() },
                        clockInState = clockInState,
                        onUiVisibilityChanged = { viewerUiVisible = it }
                    )
                }

                composable("search") {
                    when (workMode) {
                        WorkMode.CNC -> {
                            SearchScreen(
                                scanCoordinator = scanCoordinator,
                                jobRepository = jobRepository,
                                progressStore = progressStore,
                                onResultClick = { folderName, pdfFilename, page ->
                                    openSheetLegacy(folderName, pdfFilename, page)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        WorkMode.HARDWOODS -> {
                            HardwoodsSearchScreen(
                                scanCoordinator = hardwoodsScanCoordinator,
                                onResultClick = { folderName, docType, rowId ->
                                    navController.navigate(hardwoodsWorkspaceRoute(folderName, docType, rowId)) {
                                        launchSingleTop = true
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        WorkMode.ASSEMBLY -> {
                            AssemblySearchScreen(
                                assemblyScanCoordinator = assemblyScanCoordinator,
                                assemblyStateStore = assemblyStateStore,
                                specialtyProgressVersionHint = specialtyProgressVersion,
                                onResultClick = { result ->
                                    navController.navigate(
                                        assemblyViewerRoute(
                                            jobFolderName = result.jobFolderName,
                                            assemblyPage = result.assemblyPage ?: 1,
                                            plansPage = result.plansPage ?: 1
                                        )
                                    ) {
                                        launchSingleTop = true
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        WorkMode.SPECIALTY -> {
                            SearchScreen(
                                scanCoordinator = scanCoordinator,
                                jobRepository = jobRepository,
                                progressStore = progressStore,
                                onResultClick = { folderName, pdfFilename, page ->
                                    openSheetLegacy(folderName, pdfFilename, page)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                composable("hours") {
                    val context = LocalContext.current
                    var legacySessionName by remember { mutableStateOf(employeeName.ifBlank { null }) }
                    var legacyShowDialog by remember { mutableStateOf(false) }

                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (legacySessionName != null) {
                            launchTimecardApp(context, legacySessionName)
                        } else {
                            legacyShowDialog = true
                        }
                    }

                    if (legacyShowDialog) {
                        HoursLoginDialog(
                            onLogin = { name ->
                                legacySessionName = name
                                legacyShowDialog = false
                                launchTimecardApp(context, name)
                            },
                            onDismiss = { legacyShowDialog = false }
                        )
                    }

                    Box(modifier = Modifier.fillMaxSize())
                }

                composable("timecard") {
                    val autoFillPin = remember(employeeName) {
                        EmployeeDirectory.records.firstOrNull { it.name == employeeName }?.pin
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (autoFillPin != null) legacyTimecardStore.autoFill(autoFillPin)
                    }
                    DisposableEffect(Unit) {
                        onDispose { legacyTimecardStore.reset() }
                    }
                    TimecardScreen(store = legacyTimecardStore)
                }

                composable("supply") {
                    SupplyTabHost(
                        navController = rememberNavController(),
                        basePath = basePath,
                        tabletId = tabletId,
                        employeeName = employeeName,
                        subscriptionManager = supplySubscriptionManager,
                        active = (currentNavDest == NavDestination.SUPPLY)
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        tabletId = tabletId,
                        basePath = basePath,
                        isDebugBuild = isDebugBuild,
                        isDarkTheme = isDarkTheme,
                        followSystemTheme = followSystemTheme,
                        darkThemeOverride = darkThemeOverride,
                        useStandardSheets = useStandardSheets,
                        continuousScrollDefault = continuousScrollDefault,
                        onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                        onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                        workMode = workMode,
                        onThemeChanged = onThemeChanged,
                        onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                        onWorkModeChanged = onWorkModeChanged,
                        onReinstallLatest = onReinstallLatest,
                        onTabletIdChanged = onTabletIdChanged,
                        onBasePathChanged = onBasePathChanged,
                        syncthingApiKey = syncthingApiKey,
                        syncthingStatus = syncthingStatus,
                        onSyncthingApiKeySave = onSyncthingApiKeySave,
                        onSyncthingCheckNow = onSyncthingCheckNow,
                        onSyncthingStartNow = onSyncthingStartNow,
                        onBack = { navController.popBackStack() },
                        employeeName = employeeName,
                        onEmployeeNameChanged = onEmployeeNameChanged,
                        timecardConfig = legacyTimecardConfig,
                        adminSyncConfig = legacyAdminSyncConfig,
                        themeCatalog = themeCatalog,
                        onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                        onThemeOverrideChanged = onThemeOverrideChanged,
                        onThemeCatalogReload = onThemeCatalogReload,
                        onOpenAssemblyViewerDefaults = {
                            navController.navigate("settings/assemblyViewerDefaults") {
                                launchSingleTop = true
                            }
                        },
                        onOpenSpecialtyViewerDefaults = {
                            navController.navigate("settings/specialtyViewerDefaults") {
                                launchSingleTop = true
                            }
                        },
                        uiPreferencesStore = UiPreferencesStore(LocalContext.current),
                        idlePowerSaveStore = IdlePowerSaveStore(LocalContext.current),
                    )
                }

                composable("settings/assemblyViewerDefaults") {
                    AssemblyViewerDefaultsScreen(
                        store = legacyAssemblyViewerDefaultsStore,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings/specialtyViewerDefaults") {
                    SpecialtyViewerDefaultsScreen(
                        store = legacySpecialtyViewerDefaultsStore,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable("standards") {
                    com.kkc.sheettracker.ui.standards.StandardsHubScreen(
                        onBack = { navController.popBackStack() },
                        onOpenMolding = { navController.navigate("standards/molding") { launchSingleTop = true } },
                        onOpenSafety = { navController.navigate("standards/safety") { launchSingleTop = true } },
                        onOpenArchive = { navController.navigate("standards/archive") { launchSingleTop = true } },
                        safetyNotificationCount = safetyNotificationCount
                    )
                }
                composable("standards/molding") {
                    val repository = remember(basePath) {
                        com.kkc.sheettracker.data.MoldingLibraryRepository(File(basePath))
                    }
                    com.kkc.sheettracker.ui.standards.MoldingListScreen(
                        repository = repository,
                        onBack = { navController.popBackStack() },
                        isDarkTheme = isDarkTheme,
                        useStandardSheets = useStandardSheets
                    )
                }
                composable("standards/safety") {
                    com.kkc.sheettracker.ui.standards.SafetyDocumentsScreen(
                        basePath = basePath,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("standards/archive") {
                    ArchiveLibraryHost(
                        tabletId = tabletId,
                        isDebugBuild = isDebugBuild,
                        isDarkTheme = isDarkTheme,
                        useStandardSheets = useStandardSheets,
                        continuousScrollDefault = continuousScrollDefault,
                        specialtyViewerDefaultsStore = legacySpecialtyViewerDefaultsStore,
                        workMode = workMode,
                        appStateFlags = appStateFlags,
                        onExitArchive = { navController.popBackStack() },
                    )
                }

                }
                }

                if (showHoursLoginDialog) {
                    HoursLoginDialog(
                        initialInput = employeeName,
                        suggestions = EmployeeDirectory.suggestions(employeeName).map { "${it.name} (${it.pin})" },
                        onLogin = { name ->
                            showHoursLoginDialog = false
                            launchTimecardApp(legacyContext, EmployeeDirectory.resolveNameOrPin(name))
                        },
                        onDismiss = { showHoursLoginDialog = false }
                    )
                }
                pendingClockIn?.let { pending ->
                    val selected = employeeName.takeIf { it.isNotBlank() }.orEmpty()
                    HoursLoginDialog(
                        initialInput = selected,
                        suggestions = EmployeeDirectory.suggestions(selected).map { "${it.name} (${it.pin})" },
                        onLogin = { raw ->
                            val resolved = EmployeeDirectory.resolveNameOrPin(raw)
                            onEmployeeNameChanged(resolved)
                            pendingClockIn = null
                            onClockInNow(pending.jobNumber, pending.jobName, pending.folderName, pending.tabType, resolved)
                        },
                        onDismiss = { pendingClockIn = null }
                    )
                }
                pendingClockOut?.let { pending ->
                    ClockOutEditDialog(
                        jobName = pending.jobName,
                        initialHours = pending.hours,
                        startTimeMs = pending.startTimeMs,
                        stopTimeMs = pending.stopTimeMs,
                        actualElapsedMs = pending.actualElapsedMs,
                        onConfirm = { hours ->
                            pendingClockOut = null
                            launchTimecardApp(legacyContext, employeeName.ifBlank { null }, pending.jobNumber, hours.toString())
                        },
                        onDismiss = { pendingClockOut = null }
                    )
                }
                } // inner content Box
            } // hazeSource Box
        }
        } // CompositionLocalProvider

        // Nav bar as true overlay — hazeSource extends behind it so frosted glass works correctly
        Box(modifier = Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            AppBottomNavBar(
                hazeState = hazeState,
                modifier = Modifier
                    .graphicsLayer { alpha = navBarAlpha },
                currentDestination = currentNavDest,
                minimized = isInViewer || (currentNavDest == NavDestination.SUPPLY && navBarDeco.searchDecoration != null && !navBarDeco.keepSearchDeco),
                destinations = visibleDestinations,
                isCalculatorOpen = calculatorState.snapshot.isOpen,
                onCalculatorClick = { calculatorState.toggleOpen() },
                supplyNotificationCount = supplyNotificationCount,
                safetyNotificationCount = safetyNotificationCount,
                searchDecoration = navBarDeco.searchDecoration,
                cncDecoration = navBarDeco.cncDecoration,
                specialtyDecoration = navBarDeco.specialtyDecoration,
                penDecoration = navBarDeco.penDecoration,
                extendedControls = navBarDeco.extendedControls,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        launchTimecardApp(legacyContext, employeeName.takeIf { it.isNotBlank() })
                        return@AppBottomNavBar
                    }
                    if (currentRoute == dest.route) return@AppBottomNavBar
                    check(dest.route in visibleDestinations.map { it.route }) {
                        "Invalid top-level destination route: ${dest.route}"
                    }
                    if ((currentNavDest == NavDestination.JOBS || currentNavDest == NavDestination.SUPPLY) &&
                        (dest == NavDestination.JOBS || dest == NavDestination.SUPPLY)) {
                        navBarDeco.keepSearchDeco = true
                    }
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = false
                        }
                        launchSingleTop = true
                        restoreState = false
                    }
                }
            )
        }

        CalculatorOverlayHost(
            state = calculatorState,
            compactWidth = compactWidth,
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize()
        )
        val legacyBackStack by navController.currentBackStackEntryAsState()
        val legacyCurrentRoute = legacyBackStack?.destination?.route
        val isLegacyCurrentPageActiveClockIn = remember(legacyCurrentRoute, legacyBackStack?.arguments, clockInState.snapshot) {
            val snap = clockInState.snapshot
            if (!snap.isActive) false
            else {
                val folder = legacyBackStack?.arguments?.getString("folderName")
                folder != null && folder == snap.folderName
            }
        }
        ClockInOverlay(
            clockInState = clockInState,
            onClockOut = onClockOut,
            onReturnToJob = onReturnToJob,
            isCurrentPageActiveClockIn = isLegacyCurrentPageActiveClockIn,
            edgePrefs = remember { legacyContext.getSharedPreferences("kkc_ui_prefs", android.content.Context.MODE_PRIVATE) },
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize()
        )
    }
    } // LocalOnOpenSettings CompositionLocalProvider
}

internal fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int): String {
    return "viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(pdfFilename, "UTF-8")}/$page"
}

internal fun homeTopLevelTabForWorkMode(workMode: WorkMode): TopLevelTab {
    return if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) {
        TopLevelTab.JOBS
    } else {
        TopLevelTab.DASHBOARD
    }
}

internal fun specialtyJobRoute(jobFolderName: String): String {
    return "specialty/job/${URLEncoder.encode(jobFolderName, "UTF-8")}"
}

/** The Jobs list owns the scaffold search decoration only on its list route. */
internal fun isJobsListRoute(route: String?): Boolean = route == "jobs"

internal fun assemblyJobRoute(jobFolderName: String): String {
    return "assembly/job/${URLEncoder.encode(jobFolderName, "UTF-8")}"
}

internal fun specialtyDoorPanelsRoute(jobFolderName: String): String {
    return "$SPECIALTY_DOOR_PANELS_ROUTE_BASE/${URLEncoder.encode(jobFolderName, "UTF-8")}"
}

internal fun specialtySplitViewRoute(
    jobFolderName: String,
    assemblyPage: Int,
    plansPage: Int,
    room: String? = null
): String {
    return assemblyViewerRoute(
        jobFolderName = jobFolderName,
        assemblyPage = assemblyPage,
        plansPage = plansPage
    )
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun rememberCompactWidthClass(): Boolean {
    val activity = LocalActivity.current ?: return false
    val windowSizeClass = calculateWindowSizeClass(activity)
    return windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
}

internal fun referenceViewerRoute(jobFolderName: String, docType: ReferenceDocType, page: Int): String {
    return "referenceViewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(docType.name, "UTF-8")}/$page"
}

internal fun assemblyViewerRoute(
    jobFolderName: String,
    assemblyPage: Int,
    plansPage: Int,
    source: String? = null,
    cabinet: String? = null,
    room: String? = null,
    layout: AssemblyViewLayout? = null,
    firstPane: AssemblyPaneView? = null,
    secondPane: AssemblyPaneView? = null,
    hideUiOnOpen: Boolean? = null,
): String {
    val base = "assembly/viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/$assemblyPage/$plansPage"
    val query = buildList {
        if (!source.isNullOrBlank()) add("source=${URLEncoder.encode(source, "UTF-8")}")
        if (!cabinet.isNullOrBlank()) add("cab=${URLEncoder.encode(cabinet, "UTF-8")}")
        if (!room.isNullOrBlank()) add("room=${URLEncoder.encode(room, "UTF-8")}")
        if (layout != null) add("layout=${layout.name}")
        if (firstPane != null) add("first=${firstPane.name}")
        if (secondPane != null) add("second=${secondPane.name}")
        if (hideUiOnOpen != null) add("hideUi=${if (hideUiOnOpen) 1 else 0}")
    }
    return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
}

internal fun hardwoodsWorkspaceRoute(jobFolderName: String, docType: HardwoodDocType, rowId: String?): String {
    val encodedRowId = URLEncoder.encode(rowId ?: "_", "UTF-8")
    return "hardwoods/workspace/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(docType.name, "UTF-8")}/$encodedRowId"
}

private fun isCurrentViewerTarget(
    backStackEntry: androidx.navigation.NavBackStackEntry?,
    jobFolderName: String,
    pdfFilename: String,
    page: Int
): Boolean {
    val route = backStackEntry?.destination?.route ?: return false
    if (!route.startsWith("viewer/")) return false
    val args = backStackEntry.arguments ?: return false
    val currentFolder = args.getString("folderName") ?: return false
    val currentPdf = args.getString("pdfFilename") ?: return false
    val currentPage = args.getInt("startPage")
    return currentFolder == jobFolderName && currentPdf == pdfFilename && currentPage == page
}

private data class PendingClockOut(
    val jobName: String,
    val jobNumber: String,
    val hours: Double,
    val startTimeMs: Long,
    val stopTimeMs: Long,
    val actualElapsedMs: Long
)

internal data class PendingClockIn(
    val jobNumber: String,
    val jobName: String,
    val folderName: String,
    val tabType: String
)

/**
 * Result of [resolveClockInGate]: either the punch is ready to persist immediately (employee is
 * known), or it must wait behind the employee-login prompt (queued as a [PendingClockIn]).
 */
internal sealed interface ClockInGateResult {
    data class Ready(
        val jobNumber: String,
        val jobName: String,
        val folderName: String,
        val tabType: String,
        val employee: String
    ) : ClockInGateResult

    data class NeedsLogin(val pending: PendingClockIn) : ClockInGateResult
}

/**
 * Single source of truth for whether a clock-in can be persisted immediately or must be gated
 * behind the employee-login prompt first. Both MultiBackStackNavigation and
 * LegacySingleStackNavigation route their onClockIn handler through this so the two nav hosts
 * cannot silently diverge again -- legacy previously persisted clock-ins with a blank employee
 * name instead of prompting for login, unlike the multi-back-stack host.
 */
internal fun resolveClockInGate(
    employeeName: String,
    jobNumber: String,
    jobName: String,
    folderName: String,
    tabType: String
): ClockInGateResult =
    if (employeeName.isBlank()) {
        ClockInGateResult.NeedsLogin(PendingClockIn(jobNumber, jobName, folderName, tabType))
    } else {
        ClockInGateResult.Ready(jobNumber, jobName, folderName, tabType, employeeName)
    }

/** Formats the persisted job name for an active clock-in punch: "<jobName> (<employee>)". */
internal fun formattedClockInJobName(jobName: String, employee: String): String = "$jobName ($employee)"

@Composable
private fun ClockOutEditDialog(
    jobName: String,
    initialHours: Double,
    startTimeMs: Long,
    stopTimeMs: Long,
    actualElapsedMs: Long,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var hours by remember { mutableStateOf(initialHours) }
    val timeFmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM) }
    val startLabel = remember(startTimeMs) { timeFmt.format(java.util.Date(startTimeMs)) }
    val stopLabel = remember(stopTimeMs) { timeFmt.format(java.util.Date(stopTimeMs)) }
    val actualMins = (actualElapsedMs / 60000).toInt()
    val durationLabel = if (actualMins >= 60) "%dh %dm".format(actualMins / 60, actualMins % 60) else "${actualMins}m"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clock Out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(jobName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Start: $startLabel   Stop: $stopLabel   ($durationLabel actual)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (hours > 0.25) hours = Math.round((hours - 0.25) * 4) / 4.0 },
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "%.2f hrs".format(hours),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    OutlinedButton(
                        onClick = { hours = Math.round((hours + 0.25) * 4) / 4.0 },
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hours) }) { Text("Apply") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Discard") }
            }
        }
    )
}

@Composable
private fun HoursTabHost(
    navController: NavHostController,
    employeeName: String,
    isTabSelected: Boolean
) {
    val context = LocalContext.current

    LaunchedEffect(isTabSelected) {
        if (isTabSelected) {
            launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() })
        }
    }

    NavHost(navController = navController, startDestination = "hours", modifier = Modifier.fillMaxSize()) {
        composable("hours") { Box(modifier = Modifier.fillMaxSize()) }
    }
}

private fun launchTimecardApp(
    context: android.content.Context,
    autoLoginInput: String?,
    jobNumber: String? = null,
    hours: String? = null
) {
    val intent = android.content.Intent().apply {
        setClassName("com.example.timecard", "com.example.timecard.MainActivity")
        putExtra("extra_launched_by_kkc", true)
        if (autoLoginInput != null) putExtra("extra_auto_login", autoLoginInput)
        if (jobNumber != null) putExtra("extra_job_number", jobNumber)
        if (hours != null) putExtra("extra_hours", hours)
    }
    context.startActivity(intent)
}
