package com.kkc.sheettracker.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.SheetRipProgressStore
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.SpecialtyRepository
import com.kkc.sheettracker.data.SpecialtyScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.TabletSpecialtyItemsStore
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.TrackerChangeMonitor
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.assembly.AssemblyDashboardScreen
import com.kkc.sheettracker.ui.hours.HoursLoginDialog
import com.kkc.sheettracker.ui.assembly.AssemblyJobDetailScreen
import com.kkc.sheettracker.ui.assembly.AssemblyJobsScreen
import com.kkc.sheettracker.ui.assembly.AssemblySearchScreen
import com.kkc.sheettracker.ui.assembly.AssemblyViewerScreen
import com.kkc.sheettracker.ui.browser.JobBrowserScreen
import com.kkc.sheettracker.ui.components.AppBottomNavBar
import com.kkc.sheettracker.ui.components.CalculatorOverlayHost
import com.kkc.sheettracker.ui.components.ClockInOverlay
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.components.rememberCalculatorOverlayState
import com.kkc.sheettracker.ui.dashboard.DashboardScreen
import com.kkc.sheettracker.ui.detail.JobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsDashboardScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobsScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsSearchScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsWorkspaceScreen
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_RIP_CUT_LIST_ROW_ID
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_SAW_RIP_LIST_ROW_ID
import com.kkc.sheettracker.ui.search.SearchScreen
import com.kkc.sheettracker.ui.settings.SettingsScreen
import com.kkc.sheettracker.ui.supply.SupplyDashboardScreen
import com.kkc.sheettracker.ui.supply.SupplyItemDetailScreen
import com.kkc.sheettracker.ui.supply.SupplyItemEditScreen
import com.kkc.sheettracker.ui.specialty.SpecialtyDoorPanelsScreen
import com.kkc.sheettracker.ui.specialty.SpecialtyDashboardScreen
import com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreen
import com.kkc.sheettracker.ui.specialty.SpecialtyJobsScreen
import com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreen
import com.kkc.sheettracker.ui.viewer.SheetViewerScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
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
    val trackerChangeMonitor = remember(
        basePath,
        progressStore,
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
            onWatcherRefreshRequested = {
                watcherRefreshSignal.value = System.currentTimeMillis()
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
                isDarkTheme = isDarkTheme,
                workMode = workMode,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                clockInState = clockInState,
                onThemeChanged = onThemeChanged,
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
                supplySubscriptionManager = supplySubscriptionManager
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
                isDarkTheme = isDarkTheme,
                workMode = workMode,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                clockInState = clockInState,
                onThemeChanged = onThemeChanged,
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
                supplySubscriptionManager = supplySubscriptionManager
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
    isDarkTheme: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
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
    supplySubscriptionManager: SupplySubscriptionManager
) {
    val supplyNotificationCount by supplySubscriptionManager.notificationCount.collectAsState()
    val activity = LocalContext.current as? Activity
    val calculatorState = rememberCalculatorOverlayState()
    val compactWidth = rememberCompactWidthClass()
    val context = LocalContext.current
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
    val specialtyRepository = remember(basePath, specialtyProgressStore) {
        SpecialtyRepository(File(basePath), specialtyProgressStore)
    }
    val specialtyScanCoordinator = remember(specialtyRepository) { SpecialtyScanCoordinator(specialtyRepository) }
    val assemblyScanCoordinator = remember(basePath) { AssemblyScanCoordinator(File(basePath), jobRepository) }
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore
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
    val specialtyStateStore = remember(specialtyScanCoordinator, specialtyProgressStore, sheetRipProgressStore, tabletSpecialtyItemsStore) {
        SpecialtyStateStore(
            specialtyScanCoordinator = specialtyScanCoordinator,
            specialtyProgressStore = specialtyProgressStore,
            sheetRipProgressStore = sheetRipProgressStore,
            tabletItemsStore = tabletSpecialtyItemsStore
        )
    }

    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(progressStore, hardwoodsRepository, hardwoodsProgressStore, jobRepository) {
        val listener = { jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String, isComplete: Boolean ->
            coroutineScope.launch(Dispatchers.IO) {
                com.kkc.sheettracker.data.syncCncToHardwoods(
                    jobFolderName = jobFolderName,
                    jobRepository = jobRepository,
                    progressStore = progressStore,
                    hardwoodsRepository = hardwoodsRepository,
                    hardwoodsProgressStore = hardwoodsProgressStore
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
    val dashboardNavController = rememberNavController()
    val jobsNavController = rememberNavController()
    val searchNavController = rememberNavController()
    val settingsNavController = rememberNavController()
    val hoursNavController = rememberNavController()
    val supplyNavController = rememberNavController()
    val homeTab = homeTopLevelTabForWorkMode(workMode)
    var selectedTab by remember(workMode) { mutableStateOf(homeTab) }
    var showHoursLoginDialog by remember { mutableStateOf(false) }
    var pendingClockIn by remember { mutableStateOf<PendingClockIn?>(null) }
    var pendingClockOut by remember { mutableStateOf<PendingClockOut?>(null) }
    val visibleDestinations = remember(workMode) {
        if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) {
            listOf(NavDestination.JOBS, NavDestination.SEARCH, NavDestination.HOURS, NavDestination.SUPPLY, NavDestination.SETTINGS)
        } else {
            NavDestination.entries
        }
    }

    val jobsBackStack by jobsNavController.currentBackStackEntryAsState()
    val jobsCurrentRoute = jobsBackStack?.destination?.route
    val isInViewer = selectedTab == TopLevelTab.JOBS &&
        (
            jobsCurrentRoute?.startsWith("viewer/") == true ||
                jobsCurrentRoute?.startsWith("referenceViewer/") == true ||
                jobsCurrentRoute?.startsWith("hardwoods/workspace/") == true ||
                jobsCurrentRoute?.startsWith("assembly/viewer/") == true
            )
    // Tracks whether the viewer screen's overlay UI is visible (for bottom nav hide/show).
    var viewerUiVisible by remember { mutableStateOf(true) }
    // Reset UI visibility whenever we leave viewer routes.
    androidx.compose.runtime.LaunchedEffect(isInViewer) { if (!isInViewer) viewerUiVisible = true }
    val navBarAlpha by animateFloatAsState(
        if (!isInViewer || viewerUiVisible) 1f else 0f,
        tween(220), label = "navBarAlpha"
    )

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
    }

    val coordinator = remember(
        dashboardNavController,
        jobsNavController,
        searchNavController,
        settingsNavController,
        hoursNavController,
        supplyNavController
    ) {
        NavigationCoordinator(
            dashboardNavController = dashboardNavController,
            jobsNavController = jobsNavController,
            searchNavController = searchNavController,
            hoursNavController = hoursNavController,
            settingsNavController = settingsNavController,
            supplyNavController = supplyNavController,
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
            clockInState.clockIn(jobNumber, "$jobName ($employee)", folderName, tabType)
            ClockInNotificationContract.startOrUpdateService(context)
        }
    val onClockIn: (jobNumber: String, jobName: String, folderName: String, tabType: String) -> Unit =
        { jobNumber, jobName, folderName, tabType ->
            if (employeeName.isBlank()) {
                pendingClockIn = PendingClockIn(jobNumber, jobName, folderName, tabType)
            } else {
                onClockInNow(jobNumber, jobName, folderName, tabType, employeeName)
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
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
                        isDarkTheme = isDarkTheme,
                        workMode = workMode,
                        hardwoodsRepository = hardwoodsRepository,
                        hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        specialtyScanCoordinator = specialtyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        basePath = basePath,
                        clockInState = clockInState,
                        deliveryScheduleRepository = deliveryScheduleRepository,
                        onClockIn = onClockIn,
                        onSearchClick = { coordinator.navigateTopLevel(TopLevelTab.SEARCH) },
                        onSettingsClick = { coordinator.navigateTopLevel(TopLevelTab.SETTINGS) },
                        onUiVisibilityChanged = { viewerUiVisible = it }
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

                TabLayer(visible = selectedTab == TopLevelTab.SETTINGS) {
                    SettingsTabHost(
                        navController = settingsNavController,
                        tabletId = tabletId,
                        basePath = basePath,
                        isDebugBuild = isDebugBuild,
                        isDarkTheme = isDarkTheme,
                        workMode = workMode,
                        employeeName = employeeName,
                        onEmployeeNameChanged = onEmployeeNameChanged,
                        onThemeChanged = onThemeChanged,
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
                        }
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.SUPPLY) {
                    SupplyTabHost(
                        navController = supplyNavController,
                        basePath = basePath,
                        tabletId = tabletId,
                        employeeName = employeeName,
                        subscriptionManager = supplySubscriptionManager
                    )
                }
            }

            // graphicsLayer alpha — layout never shifts, no PDF re-renders during animation.
            AppBottomNavBar(
                modifier = Modifier.graphicsLayer { alpha = navBarAlpha },
                currentDestination = TopLevelTab.toDestination(selectedTab),
                minimized = isInViewer,
                destinations = visibleDestinations,
                isCalculatorOpen = calculatorState.snapshot.isOpen,
                onCalculatorClick = { calculatorState.toggleOpen() },
                supplyNotificationCount = supplyNotificationCount,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        if (employeeName.isNotBlank()) {
                            launchTimecardApp(context, employeeName)
                        } else {
                            showHoursLoginDialog = true
                        }
                    } else {
                        coordinator.navigateTopLevel(TopLevelTab.fromDestination(dest))
                    }
                }
            )

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
        }

        CalculatorOverlayHost(
            state = calculatorState,
            compactWidth = compactWidth,
            modifier = Modifier.fillMaxSize()
        )
        ClockInOverlay(
            clockInState = clockInState,
            onClockOut = onClockOut,
            onReturnToJob = onReturnToJob,
            modifier = Modifier.fillMaxSize()
        )
    }
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
                    DashboardScreen(
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = appStateFlags,
                        onNavigateToJobs = onNavigateToJobs,
                        onOpenSheet = onOpenSheet,
                        onOpenJob = { folderName ->
                            onOpenJobInJobs(folderName)
                        }
                    )
                }
                WorkMode.HARDWOODS -> {
                    HardwoodsDashboardScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        progressStore = hardwoodsProgressStore,
                        onNavigateToJobs = onNavigateToJobs,
                        onOpenJob = { job ->
                            onOpenHardwoodsJobInJobs(job.folderName)
                        }
                    )
                }
                WorkMode.ASSEMBLY -> {
                    AssemblyDashboardScreen(
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        progressStore = progressStore,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        specialtyStateStore = specialtyStateStore,
                        specialtyProgressVersionHint = specialtyProgressVersion,
                        onNavigateToJobs = onNavigateToJobs
                    )
                }
                WorkMode.SPECIALTY -> {
                    SpecialtyDashboardScreen(
                        specialtyStateStore = specialtyStateStore,
                        onNavigateToJobs = onNavigateToJobs,
                        onOpenJob = { folderName ->
                            onOpenSpecialtyJobInJobs(folderName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun JobsTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    hardwoodsRepository: HardwoodsRepository,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    hardwoodsProgressStore: HardwoodsProgressStore,
    assemblyScanCoordinator: AssemblyScanCoordinator,
    specialtyScanCoordinator: SpecialtyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    basePath: String,
    clockInState: ClockInState,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    onClockIn: (jobNumber: String, jobName: String, folderName: String, tabType: String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUiVisibilityChanged: (Boolean) -> Unit = {}
) {
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    NavHost(
        navController = navController,
        startDestination = "jobs",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("jobs") {
            when (workMode) {
                WorkMode.CNC -> {
                    JobBrowserScreen(
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        hardwoodsRepository = hardwoodsRepository,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        deliveryScheduleRepository = deliveryScheduleRepository,
                        appStateFlags = appStateFlags,
                        onJobClick = { job ->
                            navController.navigate("job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                launchSingleTop = true
                            }
                        },
                        onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                            navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                                launchSingleTop = true
                            }
                        },
                        onViewCoverSheet = { job ->
                            navController.navigate(
                                referenceViewerRoute(job.folderName, ReferenceDocType.DELIVERY_SHEETS, 1)
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onView3D = { job ->
                            val target = resolveDefaultThreeDTarget(jobRepository, job.folderName)
                            navController.navigate(
                                assemblyViewerRoute(
                                    jobFolderName = job.folderName,
                                    assemblyPage = target.assemblyPage,
                                    plansPage = target.plansPage,
                                    source = "3d",
                                    room = target.room
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onSearchClick = onSearchClick,
                        onSettingsClick = onSettingsClick
                    )
                }
                WorkMode.HARDWOODS -> {
                    HardwoodsJobsScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsRepository = hardwoodsRepository,
                        progressStore = hardwoodsProgressStore,
                        jobRepository = jobRepository,
                        deliveryScheduleRepository = deliveryScheduleRepository,
                        onJobClick = { job ->
                            navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                launchSingleTop = true
                            }
                        },
                        onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                            navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                                launchSingleTop = true
                            }
                        },
                        onViewCoverSheet = { job ->
                            navController.navigate(
                                referenceViewerRoute(job.folderName, ReferenceDocType.DELIVERY_SHEETS, 1)
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onView3D = { job ->
                            val target = resolveDefaultThreeDTarget(jobRepository, job.folderName)
                            navController.navigate(
                                assemblyViewerRoute(
                                    jobFolderName = job.folderName,
                                    assemblyPage = target.assemblyPage,
                                    plansPage = target.plansPage,
                                    source = "3d",
                                    room = target.room
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onSearchClick = onSearchClick,
                        onSettingsClick = onSettingsClick
                    )
                }
                WorkMode.ASSEMBLY -> {
                    AssemblyJobsScreen(
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        hardwoodsRepository = hardwoodsRepository,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        specialtyStateStore = specialtyStateStore,
                        deliveryScheduleRepository = deliveryScheduleRepository,
                        specialtyProgressVersionHint = specialtyProgressVersion,
                        onJobClick = { card ->
                            navController.navigate(assemblyViewerRoute(card.folderName, 1, 1)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                            navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                                launchSingleTop = true
                            }
                        },
                        onViewCoverSheet = { card ->
                            navController.navigate(
                                referenceViewerRoute(card.folderName, ReferenceDocType.DELIVERY_SHEETS, 1)
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onSearchClick = onSearchClick,
                        onSettingsClick = onSettingsClick
                    )
                }
                WorkMode.SPECIALTY -> {
                    SpecialtyJobsScreen(
                        specialtyScanCoordinator = specialtyScanCoordinator,
                        specialtyStateStore = specialtyStateStore,
                        jobRepository = jobRepository,
                        deliveryScheduleRepository = deliveryScheduleRepository,
                        onJobClick = { card ->
                            navController.navigate(specialtyJobRoute(card.folderName)) {
                                launchSingleTop = true
                            }
                        },
                        onSearchClick = onSearchClick,
                        onSettingsClick = onSettingsClick
                    )
                }
            }
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
                    val target = resolveDefaultThreeDTarget(jobRepository, folderName)
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
                        fileFingerprint = material.fileFingerprint ?: ""
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
            val hasDeliverySheet = remember(folderName) {
                jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
            }
            val hasAssemblySheet = remember(folderName) {
                jobRepository.hasReferenceDocument(folderName, ReferenceDocType.ASSEMBLY)
            }
            val hasPlansElevations = remember(folderName) {
                jobRepository.hasReferenceDocument(folderName, ReferenceDocType.PLANS_ELEVATIONS)
            }
            val hasThreeDAssets = remember(folderName) {
                jobRepository.hasThreeDAssets(folderName)
            }
            SpecialtyJobDetailScreen(
                jobFolderName = folderName,
                specialtyStateStore = specialtyStateStore,
                hasAssemblySheet = hasAssemblySheet,
                hasPlansElevations = hasPlansElevations,
                hasDeliverySheet = hasDeliverySheet,
                hasThreeDAssets = hasThreeDAssets,
                onOpenReferenceDocument = { docType, startPage ->
                    navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                        launchSingleTop = true
                    }
                },
                onOpenThreeD = {
                    val target = resolveDefaultThreeDTarget(jobRepository, folderName)
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
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "cnc") },
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
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName
            HardwoodsJobDetailScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                progressStore = hardwoodsProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
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
                    val target = resolveDefaultThreeDTarget(jobRepository, folderName)
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
                jobRepository = jobRepository,
                jobFolderName = folderName,
                initialDocType = docType,
                initialRowId = rowId,
                isDarkTheme = isDarkTheme,
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
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
            "assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}?source={source}&cab={cab}&room={room}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("startPageAssembly") { type = NavType.IntType },
                navArgument("startPagePlans") { type = NavType.IntType },
                navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("cab") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("room") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStack ->
            val jobFolderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
            val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
            val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
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
                refreshGeneration = refreshGeneration,
                isDarkTheme = isDarkTheme,
                isClockedInHere = isClockedInHere,
                onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, jobFolderName, "assembly") },
                onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
                onBack = { navController.popBackStack() },
                onUiVisibilityChanged = onUiVisibilityChanged
            )
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
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
    onBasePathChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    onBack: () -> Unit
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
                workMode = workMode,
                onThemeChanged = onThemeChanged,
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
                onEmployeeNameChanged = onEmployeeNameChanged
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
    subscriptionManager: SupplySubscriptionManager
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
                navController = navController,
                subscriptionManager = subscriptionManager
            )
        }
        composable(
            "supply/item/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId") ?: return@composable
            SupplyItemDetailScreen(
                itemId = itemId,
                basePath = basePath,
                tabletId = tabletId,
                employeeName = employeeName,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("supply/item/$itemId/edit") },
                subscriptionManager = subscriptionManager
            )
        }
        composable(
            "supply/item/{itemId}/edit",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId") ?: return@composable
            SupplyItemEditScreen(
                itemId = itemId,
                initialCategoryId = null,
                basePath = basePath,
                tabletId = tabletId,
                employeeName = employeeName,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            "supply/new/{categoryId}",
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType })
        ) { entry ->
            val categoryId = entry.arguments?.getString("categoryId") ?: return@composable
            SupplyItemEditScreen(
                itemId = null,
                initialCategoryId = categoryId,
                basePath = basePath,
                tabletId = tabletId,
                employeeName = employeeName,
                onBack = { navController.popBackStack() },
                onSaved = { newItemId ->
                    navController.navigate("supply/item/$newItemId") {
                        popUpTo("supply") { inclusive = false }
                    }
                }
            )
        }
    }
}

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
    isDarkTheme: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
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
    supplySubscriptionManager: SupplySubscriptionManager
) {
    val supplyNotificationCount by supplySubscriptionManager.notificationCount.collectAsState()
    val calculatorState = rememberCalculatorOverlayState()
    val compactWidth = rememberCompactWidthClass()
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
    val specialtyRepository = remember(basePath, specialtyProgressStore) {
        SpecialtyRepository(File(basePath), specialtyProgressStore)
    }
    val specialtyScanCoordinator = remember(specialtyRepository) { SpecialtyScanCoordinator(specialtyRepository) }
    val assemblyScanCoordinator = remember(basePath) { AssemblyScanCoordinator(File(basePath), jobRepository) }
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore
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
    val specialtyStateStore = remember(specialtyScanCoordinator, specialtyProgressStore, sheetRipProgressStore, tabletSpecialtyItemsStore) {
        SpecialtyStateStore(
            specialtyScanCoordinator = specialtyScanCoordinator,
            specialtyProgressStore = specialtyProgressStore,
            sheetRipProgressStore = sheetRipProgressStore,
            tabletItemsStore = tabletSpecialtyItemsStore
        )
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val startRoute = if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) "jobs" else "dashboard"
    var showHoursLoginDialog by remember { mutableStateOf(false) }
    var pendingClockOut by remember { mutableStateOf<PendingClockOut?>(null) }
    val visibleDestinations = remember(workMode) {
        if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) {
            listOf(NavDestination.JOBS, NavDestination.SEARCH, NavDestination.HOURS, NavDestination.SUPPLY, NavDestination.SETTINGS)
        } else {
            NavDestination.entries
        }
    }
    fun openSheetLegacy(jobFolderName: String, pdfFilename: String, page: Int) {
        if (isCurrentViewerTarget(backStackEntry, jobFolderName, pdfFilename, page)) return
        navController.navigate(viewerRoute(jobFolderName, pdfFilename, page)) {
            launchSingleTop = true
        }
    }

    val legacyContext = LocalContext.current
    val onClockIn: (jobNumber: String, jobName: String, folderName: String, tabType: String) -> Unit =
        { jobNumber, jobName, folderName, tabType ->
            clockInState.clockIn(jobNumber, jobName, folderName, tabType)
            ClockInNotificationContract.startOrUpdateService(legacyContext)
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
            currentRoute?.startsWith("supply") == true -> NavDestination.SUPPLY
            currentRoute == "settings" -> NavDestination.SETTINGS
            else -> if (workMode == WorkMode.ASSEMBLY || workMode == WorkMode.SPECIALTY) NavDestination.JOBS else NavDestination.DASHBOARD
        }
    }

    val isInViewer = currentRoute?.startsWith("viewer/") == true ||
        currentRoute?.startsWith("referenceViewer/") == true ||
        currentRoute?.startsWith("hardwoods/workspace/") == true ||
        currentRoute?.startsWith("assembly/viewer/") == true
    var viewerUiVisible by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(isInViewer) { if (!isInViewer) viewerUiVisible = true }
    val navBarAlpha by animateFloatAsState(
        if (!isInViewer || viewerUiVisible) 1f else 0f,
        tween(220), label = "navBarAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startRoute,
                modifier = Modifier.weight(1f)
            ) {
            composable("dashboard") {
                when (workMode) {
                    WorkMode.CNC -> {
                        DashboardScreen(
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
                            },
                            onOpenJob = { folderName ->
                                navController.navigate("job/${URLEncoder.encode(folderName, "UTF-8")}") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    WorkMode.HARDWOODS -> {
                        HardwoodsDashboardScreen(
                            scanCoordinator = hardwoodsScanCoordinator,
                            progressStore = hardwoodsProgressStore,
                            onNavigateToJobs = {
                                navController.navigate("jobs") {
                                    launchSingleTop = true
                                }
                            },
                            onOpenJob = { job ->
                                navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    WorkMode.ASSEMBLY -> {
                        AssemblyDashboardScreen(
                            assemblyScanCoordinator = assemblyScanCoordinator,
                            assemblyStateStore = assemblyStateStore,
                            progressStore = progressStore,
                            hardwoodsProgressStore = hardwoodsProgressStore,
                            specialtyStateStore = specialtyStateStore,
                            specialtyProgressVersionHint = specialtyProgressVersion,
                            onNavigateToJobs = {
                                navController.navigate("jobs") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    WorkMode.SPECIALTY -> {
                        SpecialtyDashboardScreen(
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
                    }
                }
            }

            composable("jobs") {
                when (workMode) {
                    WorkMode.CNC -> {
                        JobBrowserScreen(
                            scanCoordinator = scanCoordinator,
                            appStateStore = appStateStore,
                            hardwoodsRepository = hardwoodsRepository,
                            jobRepository = jobRepository,
                            progressStore = progressStore,
                            deliveryScheduleRepository = deliveryScheduleRepository,
                            appStateFlags = appStateFlags,
                            onJobClick = { job ->
                                navController.navigate("job/${URLEncoder.encode(job.folderName, "UTF-8")}")
                            },
                            onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                                navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId))
                            },
                            onViewCoverSheet = { job ->
                                navController.navigate(
                                    referenceViewerRoute(job.folderName, ReferenceDocType.DELIVERY_SHEETS, 1)
                                ) {
                                    launchSingleTop = true
                                }
                            },
                            onView3D = { job ->
                                val target = resolveDefaultThreeDTarget(jobRepository, job.folderName)
                                navController.navigate(
                                    assemblyViewerRoute(
                                        jobFolderName = job.folderName,
                                        assemblyPage = target.assemblyPage,
                                        plansPage = target.plansPage,
                                        source = "3d",
                                        room = target.room
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            },
                            onSearchClick = {
                                navController.navigate("search") {
                                    launchSingleTop = true
                                }
                            },
                            onSettingsClick = {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    WorkMode.HARDWOODS -> {
                        HardwoodsJobsScreen(
                            scanCoordinator = hardwoodsScanCoordinator,
                            hardwoodsRepository = hardwoodsRepository,
                            progressStore = hardwoodsProgressStore,
                            jobRepository = jobRepository,
                            deliveryScheduleRepository = deliveryScheduleRepository,
                            onJobClick = { job ->
                                navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}")
                            },
                            onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                                navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId))
                            },
                            onViewCoverSheet = { job ->
                                navController.navigate(
                                    referenceViewerRoute(job.folderName, ReferenceDocType.DELIVERY_SHEETS, 1)
                                ) {
                                    launchSingleTop = true
                                }
                            },
                            onView3D = { job ->
                                val target = resolveDefaultThreeDTarget(jobRepository, job.folderName)
                                navController.navigate(
                                    assemblyViewerRoute(
                                        jobFolderName = job.folderName,
                                        assemblyPage = target.assemblyPage,
                                        plansPage = target.plansPage,
                                        source = "3d",
                                        room = target.room
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            },
                            onSearchClick = {
                                navController.navigate("search") {
                                    launchSingleTop = true
                                }
                            },
                            onSettingsClick = {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    WorkMode.ASSEMBLY -> {
                        AssemblyJobsScreen(
                            assemblyScanCoordinator = assemblyScanCoordinator,
                            assemblyStateStore = assemblyStateStore,
                            hardwoodsRepository = hardwoodsRepository,
                            jobRepository = jobRepository,
                            progressStore = progressStore,
                            hardwoodsProgressStore = hardwoodsProgressStore,
                            specialtyStateStore = specialtyStateStore,
                            deliveryScheduleRepository = deliveryScheduleRepository,
                            specialtyProgressVersionHint = specialtyProgressVersion,
                            onJobClick = { card ->
                                navController.navigate(assemblyViewerRoute(card.folderName, 1, 1)) {
                                    launchSingleTop = true
                                }
                            },
                            onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                                navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                                    launchSingleTop = true
                                }
                            },
                            onViewCoverSheet = { card ->
                                navController.navigate(
                                    referenceViewerRoute(card.folderName, ReferenceDocType.DELIVERY_SHEETS, 1)
                                ) {
                                    launchSingleTop = true
                                }
                            },
                            onSearchClick = {
                                navController.navigate("search") {
                                    launchSingleTop = true
                                }
                            },
                            onSettingsClick = {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    WorkMode.SPECIALTY -> {
                        SpecialtyJobsScreen(
                            specialtyScanCoordinator = specialtyScanCoordinator,
                            specialtyStateStore = specialtyStateStore,
                            jobRepository = jobRepository,
                            deliveryScheduleRepository = deliveryScheduleRepository,
                            onJobClick = { card ->
                                navController.navigate(specialtyJobRoute(card.folderName)) {
                                    launchSingleTop = true
                                }
                            },
                            onSearchClick = {
                                navController.navigate("search") {
                                    launchSingleTop = true
                                }
                            },
                            onSettingsClick = {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }

            composable(
                "job/{folderName}",
                arguments = listOf(navArgument("folderName") { type = NavType.StringType })
            ) { backStack ->
                val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
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
                        val target = resolveDefaultThreeDTarget(jobRepository, folderName)
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
                            fileFingerprint = material.fileFingerprint ?: ""
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
                val hasDeliverySheet = remember(folderName) {
                    jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
                }
                val hasAssemblySheet = remember(folderName) {
                    jobRepository.hasReferenceDocument(folderName, ReferenceDocType.ASSEMBLY)
                }
                val hasPlansElevations = remember(folderName) {
                    jobRepository.hasReferenceDocument(folderName, ReferenceDocType.PLANS_ELEVATIONS)
                }
                val hasThreeDAssets = remember(folderName) {
                    jobRepository.hasThreeDAssets(folderName)
                }
                SpecialtyJobDetailScreen(
                    jobFolderName = folderName,
                    specialtyStateStore = specialtyStateStore,
                    hasAssemblySheet = hasAssemblySheet,
                    hasPlansElevations = hasPlansElevations,
                    hasDeliverySheet = hasDeliverySheet,
                    hasThreeDAssets = hasThreeDAssets,
                    onOpenReferenceDocument = { docType, startPage ->
                        navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenThreeD = {
                        val target = resolveDefaultThreeDTarget(jobRepository, folderName)
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
                    isDarkTheme = isDarkTheme,
                    onBack = { navController.popBackStack() },
                    onUiVisibilityChanged = { viewerUiVisible = it }
                )
            }

            composable(
                "hardwoods/job/{folderName}",
                arguments = listOf(navArgument("folderName") { type = NavType.StringType })
            ) { backStack ->
                val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                val isClockedInHere = clockInState.snapshot.isActive &&
                    clockInState.snapshot.folderName == folderName
                HardwoodsJobDetailScreen(
                    scanCoordinator = hardwoodsScanCoordinator,
                    progressStore = hardwoodsProgressStore,
                    jobRepository = jobRepository,
                    jobFolderName = folderName,
                    isClockedInHere = isClockedInHere,
                    onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, folderName, "hardwoods") },
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
                        val target = resolveDefaultThreeDTarget(jobRepository, folderName)
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
                HardwoodsWorkspaceScreen(
                    scanCoordinator = hardwoodsScanCoordinator,
                    hardwoodsRepository = hardwoodsRepository,
                    hardwoodsProgressStore = hardwoodsProgressStore,
                    jobRepository = jobRepository,
                    jobFolderName = folderName,
                    initialDocType = docType,
                    initialRowId = rowId,
                    isDarkTheme = isDarkTheme,
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
                "assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}?source={source}&cab={cab}&room={room}",
                arguments = listOf(
                    navArgument("folderName") { type = NavType.StringType },
                    navArgument("startPageAssembly") { type = NavType.IntType },
                    navArgument("startPagePlans") { type = NavType.IntType },
                    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("cab") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("room") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStack ->
                val jobFolderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
                val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
                val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
                val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
                val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
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
                    startPageAssembly = startPageAssembly,
                    startPagePlans = startPagePlans,
                    refreshGeneration = refreshGeneration,
                    isDarkTheme = isDarkTheme,
                    isClockedInHere = isClockedInHere,
                    onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, jobFolderName, "assembly") },
                    onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
                    onBack = { navController.popBackStack() },
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

            composable("supply") {
                SupplyTabHost(
                    navController = rememberNavController(),
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    subscriptionManager = supplySubscriptionManager
                )
            }

            composable("settings") {
                SettingsScreen(
                    tabletId = tabletId,
                    basePath = basePath,
                    isDebugBuild = isDebugBuild,
                    isDarkTheme = isDarkTheme,
                    workMode = workMode,
                    onThemeChanged = onThemeChanged,
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
                )
            }
            }

            // graphicsLayer alpha — layout never shifts, no PDF re-renders during animation.
            AppBottomNavBar(
                modifier = Modifier.graphicsLayer { alpha = navBarAlpha },
                currentDestination = currentNavDest,
                minimized = isInViewer,
                destinations = visibleDestinations,
                isCalculatorOpen = calculatorState.snapshot.isOpen,
                onCalculatorClick = { calculatorState.toggleOpen() },
                supplyNotificationCount = supplyNotificationCount,
                onNavigate = { dest ->
                    if (dest == NavDestination.HOURS) {
                        if (employeeName.isNotBlank()) {
                            launchTimecardApp(legacyContext, employeeName)
                        } else {
                            showHoursLoginDialog = true
                        }
                        return@AppBottomNavBar
                    }
                    if (currentRoute == dest.route) return@AppBottomNavBar
                    check(dest.route in visibleDestinations.map { it.route }) {
                        "Invalid top-level destination route: ${dest.route}"
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
        }

        CalculatorOverlayHost(
            state = calculatorState,
            compactWidth = compactWidth,
            modifier = Modifier.fillMaxSize()
        )
        ClockInOverlay(
            clockInState = clockInState,
            onClockOut = onClockOut,
            onReturnToJob = onReturnToJob,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int): String {
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
    val activity = LocalContext.current as? Activity ?: return false
    val windowSizeClass = calculateWindowSizeClass(activity)
    return windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
}

private fun referenceViewerRoute(jobFolderName: String, docType: ReferenceDocType, page: Int): String {
    return "referenceViewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(docType.name, "UTF-8")}/$page"
}

private data class ThreeDRouteTarget(
    val assemblyPage: Int,
    val plansPage: Int,
    val room: String?
)

private fun resolveDefaultThreeDTarget(
    jobRepository: JobRepository,
    jobFolderName: String
): ThreeDRouteTarget {
    val sheetIndex = jobRepository.getCabinetSheetIndex(jobFolderName)
    val assemblyDoc = sheetIndex?.documents?.assembly
    val plansDoc = sheetIndex?.documents?.plansElevations
    val assemblyPageDetails = assemblyDoc?.virtualCombined?.pageDetails
        ?.takeIf { it.isNotEmpty() }
        ?: assemblyDoc?.pageDetails.orEmpty()
    val assemblyCabinetToPages = assemblyDoc?.virtualCombined?.cabinetToPages
        ?.takeIf { it.isNotEmpty() }
        ?: assemblyDoc?.cabinetToPages.orEmpty()

    val assemblyRooms = assemblyPageDetails
        .mapNotNull { (pageKey, detail) ->
            val page = pageKey.toIntOrNull() ?: return@mapNotNull null
            val room = normalizeRoomFolderName(detail.room) ?: return@mapNotNull null
            room to page
        }
    val firstRoom = assemblyRooms
        .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
        .firstOrNull()

    val firstAssemblyPage = firstRoom?.second
        ?: assemblyCabinetToPages.values.flatten().minOrNull()
        ?: 1
    val firstPlansPage = plansDoc?.cabinetToPages?.values?.flatten()?.minOrNull() ?: 1

    return ThreeDRouteTarget(
        assemblyPage = firstAssemblyPage,
        plansPage = firstPlansPage,
        room = firstRoom?.first
    )
}

private fun normalizeRoomFolderName(roomText: String?): String? {
    val raw = roomText?.let {
        Regex("""\(([^)]+)\)""").find(it)?.groupValues?.get(1)?.uppercase()
            ?: it.uppercase().takeIf { s -> s.isNotBlank() }
    } ?: return null
    return raw.replace(Regex("""[/\\:*?"<>|]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun assemblyViewerRoute(
    jobFolderName: String,
    assemblyPage: Int,
    plansPage: Int,
    source: String? = null,
    cabinet: String? = null,
    room: String? = null
): String {
    val base = "assembly/viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/$assemblyPage/$plansPage"
    val query = buildList {
        if (!source.isNullOrBlank()) add("source=${URLEncoder.encode(source, "UTF-8")}")
        if (!cabinet.isNullOrBlank()) add("cab=${URLEncoder.encode(cabinet, "UTF-8")}")
        if (!room.isNullOrBlank()) add("room=${URLEncoder.encode(room, "UTF-8")}")
    }
    return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
}

private fun hardwoodsWorkspaceRoute(jobFolderName: String, docType: HardwoodDocType, rowId: String?): String {
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

@Composable
private fun HoursTabHost(
    navController: NavHostController,
    employeeName: String,
    isTabSelected: Boolean
) {
    val context = LocalContext.current
    var sessionName by remember { mutableStateOf(employeeName.ifBlank { null }) }
    var showLoginDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(isTabSelected) {
        if (isTabSelected) {
            if (sessionName != null) {
                launchTimecardApp(context, sessionName)
            } else {
                showLoginDialog = true
            }
        }
    }

    if (showLoginDialog) {
        HoursLoginDialog(
            onLogin = { name ->
                sessionName = name
                showLoginDialog = false
                launchTimecardApp(context, name)
            },
            onDismiss = { showLoginDialog = false }
        )
    }

    NavHost(
        navController = navController,
        startDestination = "hours",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("hours") {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

private data class PendingClockOut(
    val jobName: String,
    val jobNumber: String,
    val hours: Double,
    val startTimeMs: Long,
    val stopTimeMs: Long,
    val actualElapsedMs: Long
)

private data class PendingClockIn(
    val jobNumber: String,
    val jobName: String,
    val folderName: String,
    val tabType: String
)

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

private fun launchTimecardApp(
    context: android.content.Context,
    autoLoginInput: String?,
    jobNumber: String? = null,
    hours: String? = null
) {
    val intent = android.content.Intent().apply {
        setClassName("com.example.timecard", "com.example.timecard.MainActivity")
        if (autoLoginInput != null) putExtra("extra_auto_login", autoLoginInput)
        if (jobNumber != null) putExtra("extra_job_number", jobNumber)
        if (hours != null) putExtra("extra_hours", hours)
    }
    context.startActivity(intent)
}
