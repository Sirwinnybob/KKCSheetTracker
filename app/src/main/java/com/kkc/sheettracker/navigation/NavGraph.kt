package com.kkc.sheettracker.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.browser.JobBrowserScreen
import com.kkc.sheettracker.ui.components.AppBottomNavBar
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.dashboard.DashboardScreen
import com.kkc.sheettracker.ui.detail.JobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsDashboardScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobsScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsSearchScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsWorkspaceScreen
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
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit
) {
    val flags = remember(appStateFlags) { appStateFlags.snapshot() }
    key(workMode) {
        if (flags.navMultiStackEnabled) {
            MultiBackStackNavigation(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                appStateFlags = appStateFlags,
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                workMode = workMode,
                onThemeChanged = onThemeChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onBasePathChanged = onBasePathChanged,
                onTabletIdChanged = onTabletIdChanged
            )
        } else {
            LegacySingleStackNavigation(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                jobRepository = jobRepository,
                progressStore = progressStore,
                appStateFlags = appStateFlags,
                tabletId = tabletId,
                basePath = basePath,
                isDebugBuild = isDebugBuild,
                isDarkTheme = isDarkTheme,
                workMode = workMode,
                onThemeChanged = onThemeChanged,
                onWorkModeChanged = onWorkModeChanged,
                onReinstallLatest = onReinstallLatest,
                onBasePathChanged = onBasePathChanged,
                onTabletIdChanged = onTabletIdChanged
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
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit
) {
    val activity = LocalContext.current as? Activity
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsProgressStore = remember(basePath, tabletId) { HardwoodsProgressStore(File(basePath), tabletId) }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
    val dashboardNavController = rememberNavController()
    val jobsNavController = rememberNavController()
    val searchNavController = rememberNavController()
    val settingsNavController = rememberNavController()
    var selectedTab by remember { mutableStateOf(TopLevelTab.DASHBOARD) }

    val jobsBackStack by jobsNavController.currentBackStackEntryAsState()
    val jobsCurrentRoute = jobsBackStack?.destination?.route
    val isInViewer = selectedTab == TopLevelTab.JOBS &&
        (
            jobsCurrentRoute?.startsWith("viewer/") == true ||
                jobsCurrentRoute?.startsWith("referenceViewer/") == true ||
                jobsCurrentRoute?.startsWith("hardwoods/workspace/") == true
            )

    val coordinator = remember(
        dashboardNavController,
        jobsNavController,
        searchNavController,
        settingsNavController
    ) {
        NavigationCoordinator(
            dashboardNavController = dashboardNavController,
            jobsNavController = jobsNavController,
            searchNavController = searchNavController,
            settingsNavController = settingsNavController,
            getSelectedTab = { selectedTab },
            setSelectedTab = { selectedTab = it }
        )
    }

    BackHandler(enabled = activity != null) {
        activity?.let { coordinator.onBackPressed(it) }
    }

    androidx.compose.runtime.LaunchedEffect(workMode, basePath) {
        hardwoodsRepository.updateBaseDir(File(basePath))
        if (workMode == WorkMode.HARDWOODS) {
            hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
        }
    }

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
                    onNavigateToJobs = {
                        coordinator.navigateTopLevel(TopLevelTab.JOBS)
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
                    onBack = {
                        coordinator.navigateTopLevel(TopLevelTab.DASHBOARD)
                    }
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
                    onThemeChanged = onThemeChanged,
                    onWorkModeChanged = onWorkModeChanged,
                    onReinstallLatest = onReinstallLatest,
                    onTabletIdChanged = onTabletIdChanged,
                    onBasePathChanged = onBasePathChanged,
                    onBack = {
                        coordinator.navigateTopLevel(TopLevelTab.DASHBOARD)
                    }
                )
            }
        }

        AppBottomNavBar(
            currentDestination = TopLevelTab.toDestination(selectedTab),
            minimized = isInViewer,
            onNavigate = { dest ->
                coordinator.navigateTopLevel(TopLevelTab.fromDestination(dest))
            }
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
    onNavigateToJobs: () -> Unit,
    onOpenSheet: (String, String, Int) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("dashboard") {
            if (workMode == WorkMode.CNC) {
                DashboardScreen(
                    scanCoordinator = scanCoordinator,
                    appStateStore = appStateStore,
                    jobRepository = jobRepository,
                    progressStore = progressStore,
                    appStateFlags = appStateFlags,
                    onNavigateToJobs = onNavigateToJobs,
                    onOpenSheet = onOpenSheet
                )
            } else {
                HardwoodsDashboardScreen(
                    scanCoordinator = hardwoodsScanCoordinator,
                    progressStore = hardwoodsProgressStore,
                    onNavigateToJobs = onNavigateToJobs,
                    onOpenJob = { job ->
                        navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                            launchSingleTop = true
                        }
                    }
                )
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
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = "jobs",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("jobs") {
            if (workMode == WorkMode.CNC) {
                JobBrowserScreen(
                    scanCoordinator = scanCoordinator,
                    appStateStore = appStateStore,
                    jobRepository = jobRepository,
                    progressStore = progressStore,
                    appStateFlags = appStateFlags,
                    onJobClick = { job ->
                        navController.navigate("job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                            launchSingleTop = true
                        }
                    },
                    onSearchClick = onSearchClick,
                    onSettingsClick = onSettingsClick
                )
            } else {
                HardwoodsJobsScreen(
                    scanCoordinator = hardwoodsScanCoordinator,
                    progressStore = hardwoodsProgressStore,
                    onJobClick = { job ->
                        navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                            launchSingleTop = true
                        }
                    },
                    onSearchClick = onSearchClick,
                    onSettingsClick = onSettingsClick
                )
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
                jobFolderName = folderName,
                onOpenWorkspace = { docType ->
                    navController.navigate(hardwoodsWorkspaceRoute(folderName, docType, null)) {
                        launchSingleTop = true
                    }
                },
                onOpenReferenceDocument = { docType, startPage ->
                    navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
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
                hardwoodsProgressStore = hardwoodsProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
                initialDocType = docType,
                initialRowId = rowId,
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
    onCncResultClick: (String, String, Int) -> Unit,
    onHardwoodsResultClick: (String) -> Unit,
    onBack: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = "search",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("search") {
            if (workMode == WorkMode.CNC) {
                SearchScreen(
                    scanCoordinator = scanCoordinator,
                    jobRepository = jobRepository,
                    progressStore = progressStore,
                    onResultClick = onCncResultClick,
                    onBack = onBack
                )
            } else {
                HardwoodsSearchScreen(
                    scanCoordinator = hardwoodsScanCoordinator,
                    onResultClick = { folderName, docType, rowId ->
                        onHardwoodsResultClick("${folderName}|${docType.name}|${rowId}")
                    },
                    onBack = onBack
                )
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
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
    onBasePathChanged: (String) -> Unit,
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
                onBack = onBack
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
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit
) {
    val hardwoodsRepository = remember(basePath) { HardwoodsRepository(File(basePath)) }
    val hardwoodsProgressStore = remember(basePath, tabletId) { HardwoodsProgressStore(File(basePath), tabletId) }
    val hardwoodsScanCoordinator = remember(hardwoodsRepository) { HardwoodsScanCoordinator(hardwoodsRepository) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    fun openSheetLegacy(jobFolderName: String, pdfFilename: String, page: Int) {
        if (isCurrentViewerTarget(backStackEntry, jobFolderName, pdfFilename, page)) return
        navController.navigate(viewerRoute(jobFolderName, pdfFilename, page)) {
            launchSingleTop = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(workMode, basePath) {
        hardwoodsRepository.updateBaseDir(File(basePath))
        if (workMode == WorkMode.HARDWOODS) {
            hardwoodsScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = true)
        }
    }

    val currentNavDest = remember(currentRoute) {
        when {
            currentRoute == "dashboard" -> NavDestination.DASHBOARD
            currentRoute?.startsWith("jobs") == true ||
                currentRoute?.startsWith("job/") == true ||
                currentRoute?.startsWith("hardwoods/job/") == true ||
                currentRoute?.startsWith("hardwoods/workspace/") == true ||
                currentRoute?.startsWith("viewer/") == true ||
                currentRoute?.startsWith("referenceViewer/") == true -> NavDestination.JOBS
            currentRoute == "search" -> NavDestination.SEARCH
            currentRoute == "settings" -> NavDestination.SETTINGS
            else -> NavDestination.DASHBOARD
        }
    }

    val isInViewer = currentRoute?.startsWith("viewer/") == true ||
        currentRoute?.startsWith("referenceViewer/") == true ||
        currentRoute?.startsWith("hardwoods/workspace/") == true

    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.weight(1f)
        ) {
            composable("dashboard") {
                if (workMode == WorkMode.CNC) {
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
                        }
                    )
                } else {
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
            }

            composable("jobs") {
                if (workMode == WorkMode.CNC) {
                    JobBrowserScreen(
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = appStateFlags,
                        onJobClick = { job ->
                            navController.navigate("job/${URLEncoder.encode(job.folderName, "UTF-8")}")
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
                } else {
                    HardwoodsJobsScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        progressStore = hardwoodsProgressStore,
                        onJobClick = { job ->
                            navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}")
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
                    jobFolderName = folderName,
                    onOpenWorkspace = { docType ->
                        navController.navigate(hardwoodsWorkspaceRoute(folderName, docType, null)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenReferenceDocument = { docType, startPage ->
                        navController.navigate(referenceViewerRoute(folderName, docType, startPage)) {
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
                    hardwoodsProgressStore = hardwoodsProgressStore,
                    jobRepository = jobRepository,
                    jobFolderName = folderName,
                    initialDocType = docType,
                    initialRowId = rowId,
                    isDarkTheme = isDarkTheme,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("search") {
                if (workMode == WorkMode.CNC) {
                    SearchScreen(
                        scanCoordinator = scanCoordinator,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        onResultClick = { folderName, pdfFilename, page ->
                            openSheetLegacy(folderName, pdfFilename, page)
                        },
                        onBack = { navController.popBackStack() }
                    )
                } else {
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
                    onBack = { navController.popBackStack() }
                )
            }
        }

        AppBottomNavBar(
            currentDestination = currentNavDest,
            minimized = isInViewer,
            onNavigate = { dest ->
                if (currentRoute == dest.route) return@AppBottomNavBar
                check(dest.route in NavDestination.entries.map { it.route }) {
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
}

private fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int): String {
    return "viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(pdfFilename, "UTF-8")}/$page"
}

private fun referenceViewerRoute(jobFolderName: String, docType: ReferenceDocType, page: Int): String {
    return "referenceViewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(docType.name, "UTF-8")}/$page"
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
