package com.kkc.sheettracker.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.HoursStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.assembly.AssemblyDashboardScreen
import com.kkc.sheettracker.ui.hours.HoursLoginDialog
import com.kkc.sheettracker.ui.hours.HoursTrackerScreen
import com.kkc.sheettracker.ui.assembly.AssemblyJobsScreen
import com.kkc.sheettracker.ui.assembly.AssemblySearchScreen
import com.kkc.sheettracker.ui.assembly.AssemblyViewerScreen
import com.kkc.sheettracker.ui.browser.JobBrowserScreen
import com.kkc.sheettracker.ui.components.AppBottomNavBar
import com.kkc.sheettracker.ui.components.CalculatorOverlayHost
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.components.rememberCalculatorOverlayState
import com.kkc.sheettracker.ui.dashboard.DashboardScreen
import com.kkc.sheettracker.ui.detail.JobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsDashboardScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobsScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsSearchScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsWorkspaceScreen
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_RIP_CUT_LIST_ROW_ID
import com.kkc.sheettracker.ui.search.SearchScreen
import com.kkc.sheettracker.ui.settings.SettingsScreen
import com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreen
import com.kkc.sheettracker.ui.viewer.SheetViewerScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

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
    hoursStore: HoursStore,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit
) {
    val flags = remember(appStateFlags) { appStateFlags.snapshot() }
    key(workMode) {
        if (flags.navMultiStackEnabled) {
            MultiBackStackNavigation(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                isViewOnlyMode = isViewOnlyMode,
                appStateFlags = appStateFlags,
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                workMode = workMode,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                hoursStore = hoursStore,
                onThemeChanged = onThemeChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onBasePathChanged = onBasePathChanged,
                onTabletIdChanged = onTabletIdChanged,
                syncthingApiKey = syncthingApiKey,
                syncthingStatus = syncthingStatus,
                onSyncthingApiKeySave = onSyncthingApiKeySave,
                onSyncthingCheckNow = onSyncthingCheckNow,
                onSyncthingStartNow = onSyncthingStartNow
            )
        } else {
            LegacySingleStackNavigation(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                isViewOnlyMode = isViewOnlyMode,
                appStateFlags = appStateFlags,
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                workMode = workMode,
                employeeName = employeeName,
                onEmployeeNameChanged = onEmployeeNameChanged,
                hoursStore = hoursStore,
                onThemeChanged = onThemeChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onBasePathChanged = onBasePathChanged,
                onTabletIdChanged = onTabletIdChanged,
                syncthingApiKey = syncthingApiKey,
                syncthingStatus = syncthingStatus,
                onSyncthingApiKeySave = onSyncthingApiKeySave,
                onSyncthingCheckNow = onSyncthingCheckNow,
                onSyncthingStartNow = onSyncthingStartNow
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
    isViewOnlyMode: Boolean,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    hoursStore: HoursStore,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit
) {
    val activity = LocalContext.current as? Activity
    val calculatorState = rememberCalculatorOverlayState()
    val compactWidth = rememberCompactWidthClass()
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsProgressStore = remember(basePath, tabletId, isViewOnlyMode) {
        HardwoodsProgressStore(File(basePath), tabletId, readOnly = isViewOnlyMode)
    }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
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
    val dashboardNavController = rememberNavController()
    val jobsNavController = rememberNavController()
    val searchNavController = rememberNavController()
    val settingsNavController = rememberNavController()
    val hoursNavController = rememberNavController()
    val homeTab = if (workMode == WorkMode.ASSEMBLY) TopLevelTab.JOBS else TopLevelTab.DASHBOARD
    var selectedTab by remember(workMode) { mutableStateOf(homeTab) }
    val visibleDestinations = remember(workMode) {
        if (workMode == WorkMode.ASSEMBLY) {
            listOf(NavDestination.JOBS, NavDestination.SEARCH, NavDestination.HOURS, NavDestination.SETTINGS)
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

    val coordinator = remember(
        dashboardNavController,
        jobsNavController,
        searchNavController,
        settingsNavController,
        hoursNavController
    ) {
        NavigationCoordinator(
            dashboardNavController = dashboardNavController,
            jobsNavController = jobsNavController,
            searchNavController = searchNavController,
            hoursNavController = hoursNavController,
            settingsNavController = settingsNavController,
            getHomeTab = { homeTab },
            getSelectedTab = { selectedTab },
            setSelectedTab = { selectedTab = it }
        )
    }

    BackHandler(enabled = activity != null) {
        activity?.let { coordinator.onBackPressed(it) }
    }

    androidx.compose.runtime.LaunchedEffect(workMode, basePath) {
        hardwoodsRepository.updateBaseDir(File(basePath))
        assemblyScanCoordinator.updateBasePath(basePath)
        when (workMode) {
            WorkMode.HARDWOODS -> hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            WorkMode.ASSEMBLY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.CNC -> Unit
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
                        onNavigateToJobs = {
                            coordinator.navigateTopLevel(TopLevelTab.JOBS)
                        },
                        onOpenJobInJobs = { folderName ->
                            coordinator.openJobDetailInJobs(folderName)
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
                        assemblyStateStore = assemblyStateStore,
                        basePath = basePath,
                        onSearchClick = { coordinator.navigateTopLevel(TopLevelTab.SEARCH) },
                        onSettingsClick = { coordinator.navigateTopLevel(TopLevelTab.SETTINGS) }
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
                            coordinator.navigateTopLevel(TopLevelTab.DASHBOARD)
                        }
                    )
                }

                TabLayer(visible = selectedTab == TopLevelTab.HOURS) {
                    HoursTabHost(
                        navController = hoursNavController,
                        hoursStore = hoursStore,
                        employeeName = employeeName
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
                            coordinator.navigateTopLevel(TopLevelTab.DASHBOARD)
                        }
                    )
                }
            }

            AppBottomNavBar(
                currentDestination = TopLevelTab.toDestination(selectedTab),
                minimized = isInViewer,
                destinations = visibleDestinations,
                isCalculatorOpen = calculatorState.snapshot.isOpen,
                onCalculatorClick = { calculatorState.toggleOpen() },
                onNavigate = { dest ->
                    coordinator.navigateTopLevel(TopLevelTab.fromDestination(dest))
                }
            )
        }

        CalculatorOverlayHost(
            state = calculatorState,
            compactWidth = compactWidth,
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
    onNavigateToJobs: () -> Unit,
    onOpenJobInJobs: (String) -> Unit,
    onOpenHardwoodsJobInJobs: (String) -> Unit,
    onOpenSheet: (String, String, Int) -> Unit
) {
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
                        onNavigateToJobs = onNavigateToJobs
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
    assemblyStateStore: AssemblyStateStore,
    basePath: String,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
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
            }
        }

        composable(
            "job/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            JobDetailScreen(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                appStateFlags = appStateFlags,
                jobFolderName = folderName,
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
                onBack = { navController.popBackStack() }
            )
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
                onBack = { navController.popBackStack() }
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
            ReferencePdfViewerScreen(
                jobRepository = jobRepository,
                jobFolderName = folderName,
                docType = docType,
                startPage = startPage,
                isDarkTheme = isDarkTheme,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "hardwoods/job/{folderName}",
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            HardwoodsJobDetailScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                progressStore = hardwoodsProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
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
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
            val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
            val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
            val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
            AssemblyViewerScreen(
                jobRepository = jobRepository,
                assemblyStateStore = assemblyStateStore,
                jobFolderName = folderName,
                basePath = basePath,
                startPageAssembly = startPageAssembly,
                startPagePlans = startPagePlans,
                initialSource = initialSource,
                initialCabinet = initialCabinet,
                initialRoom = initialRoom,
                isDarkTheme = isDarkTheme,
                onBack = { navController.popBackStack() }
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
    onCncResultClick: (String, String, Int) -> Unit,
    onHardwoodsResultClick: (String) -> Unit,
    onAssemblyResultClick: (AssemblySearchEntry) -> Unit,
    onBack: () -> Unit
) {
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
                        onResultClick = onAssemblyResultClick,
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
                onEmployeeNameChanged = onEmployeeNameChanged,
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
    isViewOnlyMode: Boolean,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    hoursStore: HoursStore,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit
) {
    val calculatorState = rememberCalculatorOverlayState()
    val compactWidth = rememberCompactWidthClass()
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsProgressStore = remember(basePath, tabletId, isViewOnlyMode) {
        HardwoodsProgressStore(File(basePath), tabletId, readOnly = isViewOnlyMode)
    }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
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
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val startRoute = if (workMode == WorkMode.ASSEMBLY) "jobs" else "dashboard"
    val visibleDestinations = remember(workMode) {
        if (workMode == WorkMode.ASSEMBLY) {
            listOf(NavDestination.JOBS, NavDestination.SEARCH, NavDestination.HOURS, NavDestination.SETTINGS)
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

    androidx.compose.runtime.LaunchedEffect(workMode, basePath) {
        hardwoodsRepository.updateBaseDir(File(basePath))
        assemblyScanCoordinator.updateBasePath(basePath)
        when (workMode) {
            WorkMode.HARDWOODS -> hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            WorkMode.ASSEMBLY -> {
                hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
                assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
            }
            WorkMode.CNC -> Unit
        }
    }

    val currentNavDest = remember(currentRoute) {
        when {
            currentRoute == "dashboard" && workMode != WorkMode.ASSEMBLY -> NavDestination.DASHBOARD
            currentRoute?.startsWith("jobs") == true ||
            currentRoute?.startsWith("job/") == true ||
                currentRoute?.startsWith("hardwoods/job/") == true ||
                currentRoute?.startsWith("hardwoods/workspace/") == true ||
                currentRoute?.startsWith("assembly/viewer/") == true ||
                currentRoute?.startsWith("viewer/") == true ||
                currentRoute?.startsWith("referenceViewer/") == true -> NavDestination.JOBS
            currentRoute == "search" -> NavDestination.SEARCH
            currentRoute == "hours" -> NavDestination.HOURS
            currentRoute == "settings" -> NavDestination.SETTINGS
            else -> if (workMode == WorkMode.ASSEMBLY) NavDestination.JOBS else NavDestination.DASHBOARD
        }
    }

    val isInViewer = currentRoute?.startsWith("viewer/") == true ||
        currentRoute?.startsWith("referenceViewer/") == true ||
        currentRoute?.startsWith("hardwoods/workspace/") == true ||
        currentRoute?.startsWith("assembly/viewer/") == true

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
                            onNavigateToJobs = {
                                navController.navigate("jobs") {
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
                }
            }

            composable(
                "job/{folderName}",
                arguments = listOf(navArgument("folderName") { type = NavType.StringType })
            ) { backStack ->
                val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                JobDetailScreen(
                    scanCoordinator = scanCoordinator,
                    appStateStore = appStateStore,
                    jobRepository = jobRepository,
                    progressStore = progressStore,
                    appStateFlags = appStateFlags,
                    jobFolderName = folderName,
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
                    onBack = { navController.popBackStack() }
                )
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
                    onBack = { navController.popBackStack() }
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
                ReferencePdfViewerScreen(
                    jobRepository = jobRepository,
                    jobFolderName = folderName,
                    docType = docType,
                    startPage = startPage,
                    isDarkTheme = isDarkTheme,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "hardwoods/job/{folderName}",
                arguments = listOf(navArgument("folderName") { type = NavType.StringType })
            ) { backStack ->
                val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                HardwoodsJobDetailScreen(
                    scanCoordinator = hardwoodsScanCoordinator,
                    progressStore = hardwoodsProgressStore,
                    jobRepository = jobRepository,
                    jobFolderName = folderName,
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
                val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
                val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
                val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
                val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
                val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
                val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
                AssemblyViewerScreen(
                    jobRepository = jobRepository,
                    assemblyStateStore = assemblyStateStore,
                    jobFolderName = folderName,
                    basePath = basePath,
                    initialSource = initialSource,
                    initialCabinet = initialCabinet,
                    initialRoom = initialRoom,
                    startPageAssembly = startPageAssembly,
                    startPagePlans = startPagePlans,
                    isDarkTheme = isDarkTheme,
                    onBack = { navController.popBackStack() }
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
                }
            }

            composable("hours") {
                var sessionName by remember { mutableStateOf(employeeName.ifBlank { null }) }
                var showLoginDialog by remember { mutableStateOf(sessionName == null) }

                if (showLoginDialog || sessionName == null) {
                    HoursLoginDialog(
                        onLogin = { name ->
                            sessionName = name
                            showLoginDialog = false
                        },
                        onDismiss = { showLoginDialog = false }
                    )
                }
                sessionName?.let { name ->
                    HoursTrackerScreen(
                        hoursStore = hoursStore,
                        employeeName = name
                    )
                }
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

            AppBottomNavBar(
                currentDestination = currentNavDest,
                minimized = isInViewer,
                destinations = visibleDestinations,
                isCalculatorOpen = calculatorState.snapshot.isOpen,
                onCalculatorClick = { calculatorState.toggleOpen() },
                onNavigate = { dest ->
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
        }

        CalculatorOverlayHost(
            state = calculatorState,
            compactWidth = compactWidth,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int): String {
    return "viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(pdfFilename, "UTF-8")}/$page"
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
    hoursStore: HoursStore,
    employeeName: String
) {
    var sessionName by remember { mutableStateOf(employeeName.ifBlank { null }) }
    var showLoginDialog by remember { mutableStateOf(sessionName == null) }

    NavHost(
        navController = navController,
        startDestination = "hours",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("hours") {
            if (showLoginDialog || sessionName == null) {
                HoursLoginDialog(
                    onLogin = { name ->
                        sessionName = name
                        showLoginDialog = false
                    },
                    onDismiss = { showLoginDialog = false }
                )
            }
            sessionName?.let { name ->
                HoursTrackerScreen(
                    hoursStore = hoursStore,
                    employeeName = name
                )
            }
        }
    }
}
