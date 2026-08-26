package com.kkc.sheettracker.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.ArchiveSession
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyViewLayout
import com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.ui.assembly.AssemblyJobDetailScreen
import com.kkc.sheettracker.ui.assembly.AssemblyViewerScreen
import com.kkc.sheettracker.ui.detail.JobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsJobDetailScreen
import com.kkc.sheettracker.ui.hardwoods.HardwoodsWorkspaceScreen
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_RIP_CUT_LIST_ROW_ID
import com.kkc.sheettracker.ui.hardwoods.HARDWOODS_SAW_RIP_LIST_ROW_ID
import com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreen
import com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreen
import com.kkc.sheettracker.ui.viewer.SheetViewerScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared nested archive navigation. The session is scoped to this host, so every detail and
 * viewer route below reads from the restored cache and gets the same read-only stores.
 */
@Composable
internal fun ArchiveJobDetailHost(
    archiveJobId: String,
    folderName: String,
    contentVersion: String,
    cacheJobParentDir: File,
    tabletId: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    specialtyViewerDefaultsStore: SpecialtyViewerDefaultsStore,
    workMode: WorkMode,
    appStateFlags: AppStateFeatureFlags,
    onExitArchive: () -> Unit,
) {
    val session = remember(archiveJobId, folderName, contentVersion, cacheJobParentDir, tabletId, isDebugBuild) {
        ArchiveSession.create(
            archiveJobId = archiveJobId,
            contentVersion = contentVersion,
            cacheJobParentDir = cacheJobParentDir,
            folderName = folderName,
            tabletId = tabletId,
            isDebugBuild = isDebugBuild,
        )
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session, workMode) {
        when (workMode) {
            WorkMode.CNC -> session.scanCoordinator.refresh(RefreshReason.APP_START, force = true)
            WorkMode.HARDWOODS -> session.hardwoodsScanCoordinator.refresh(RefreshReason.APP_START, force = true)
            WorkMode.ASSEMBLY -> session.assemblyScanCoordinator.refresh(RefreshReason.APP_START, force = true)
            WorkMode.SPECIALTY -> session.specialtyScanCoordinator.refresh(RefreshReason.APP_START, force = true)
        }
    }

    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "detail",
        modifier = Modifier.fillMaxSize(),
    ) {
        composable("detail") {
            when (workMode) {
                WorkMode.CNC -> ArchiveCncDetail(
                    session = session,
                    appStateFlags = appStateFlags,
                    navController = navController,
                    onExitArchive = onExitArchive,
                )
                WorkMode.HARDWOODS -> ArchiveHardwoodsDetail(
                    session = session,
                    navController = navController,
                    onExitArchive = onExitArchive,
                )
                WorkMode.ASSEMBLY -> AssemblyJobDetailScreen(
                    jobFolderName = session.folderName,
                    assemblyStateStore = session.assemblyStateStore,
                    specialtyStateStore = session.specialtyStateStore,
                    jobRepository = session.jobRepository,
                    onOpenSplitView = {
                        navController.navigate(assemblyViewerRoute(session.folderName, 1, 1))
                    },
                    onJumpToCabinet = { cabinet ->
                        navController.navigate(assemblyViewerRoute(session.folderName, 1, 1, cabinet = cabinet))
                    },
                    onBack = onExitArchive,
                )
                WorkMode.SPECIALTY -> ArchiveSpecialtyDetail(
                    session = session,
                    specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
                    navController = navController,
                    onExitArchive = onExitArchive,
                )
            }
        }

        composable(
            "viewer/{pdfFilename}/{startPage}",
            arguments = listOf(
                navArgument("pdfFilename") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val pdfFilename = URLDecoder.decode(backStackEntry.arguments?.getString("pdfFilename").orEmpty(), "UTF-8")
            val startPage = backStackEntry.arguments?.getInt("startPage") ?: 1
            SheetViewerScreen(
                scanCoordinator = session.scanCoordinator,
                appStateStore = session.appStateStore,
                jobRepository = session.jobRepository,
                progressStore = session.progressStore,
                appStateFlags = appStateFlags,
                jobFolderName = session.folderName,
                pdfFilename = pdfFilename,
                startPage = startPage,
                isDarkTheme = isDarkTheme && !useStandardSheets,
                useStandardSheets = useStandardSheets,
                onOpenReferenceDocument = { docType, page ->
                    navController.navigate("referenceViewer/${URLEncoder.encode(docType.name, "UTF-8")}/$page")
                },
                onOpenThreeDTarget = { cabinet, assemblyPage, plansPage, room ->
                    navController.navigate(
                        assemblyViewerRoute(
                            jobFolderName = session.folderName,
                            assemblyPage = assemblyPage ?: 1,
                            plansPage = plansPage ?: 1,
                            source = "3d",
                            cabinet = cabinet,
                            room = room,
                        )
                    )
                },
                onMaterialUnavailable = { navController.popBackStack("detail", false) },
                onBack = { navController.popBackStack() },
                overridePdfMarkupStore = session.pdfMarkupStore,
                pdfMarkupReadOnly = true,
            )
        }

        composable(
            "referenceViewer/{docType}/{startPage}",
            arguments = listOf(
                navArgument("docType") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val rawDocType = URLDecoder.decode(backStackEntry.arguments?.getString("docType").orEmpty(), "UTF-8")
            val docType = runCatching { ReferenceDocType.valueOf(rawDocType) }
                .getOrDefault(ReferenceDocType.ASSEMBLY)
            val startPage = backStackEntry.arguments?.getInt("startPage") ?: 1
            val refreshGeneration = session.scanCoordinator.state.collectAsState().value.snapshot.generation
            ReferencePdfViewerScreen(
                jobRepository = session.jobRepository,
                jobFolderName = session.folderName,
                docType = docType,
                startPage = startPage,
                refreshGeneration = refreshGeneration,
                continuousScrollDefault = continuousScrollDefault,
                isDarkTheme = isDarkTheme && !useStandardSheets,
                onBack = { navController.popBackStack() },
                overridePdfMarkupStore = session.pdfMarkupStore,
                pdfMarkupReadOnly = true,
            )
        }

        composable(
            "hardwoods/workspace/{docType}/{rowId}",
            arguments = listOf(
                navArgument("docType") { type = NavType.StringType },
                navArgument("rowId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            LaunchedEffect(session) {
                session.hardwoodsScanCoordinator.refresh(RefreshReason.APP_START, force = true)
            }
            val rawDocType = URLDecoder.decode(backStackEntry.arguments?.getString("docType").orEmpty(), "UTF-8")
            val docType = runCatching { HardwoodDocType.valueOf(rawDocType) }
                .getOrDefault(HardwoodDocType.FACE_FRAME_CUT_LIST)
            val rawRowId = URLDecoder.decode(backStackEntry.arguments?.getString("rowId").orEmpty(), "UTF-8")
            val rowId = rawRowId.takeIf { it.isNotBlank() && it != "_" }
            HardwoodsWorkspaceScreen(
                scanCoordinator = session.hardwoodsScanCoordinator,
                hardwoodsRepository = session.hardwoodsRepository,
                hardwoodsProgressStore = session.hardwoodsProgressStore,
                sheetRipProgressStore = session.sheetRipProgressStore,
                jobRepository = session.jobRepository,
                jobFolderName = session.folderName,
                initialDocType = docType,
                initialRowId = rowId,
                continuousScrollDefault = continuousScrollDefault,
                isDarkTheme = isDarkTheme && !useStandardSheets,
                onOpenThreeDTarget = { cabinet, assemblyPage, plansPage, room ->
                    navController.navigate(
                        assemblyViewerRoute(
                            jobFolderName = session.folderName,
                            assemblyPage = assemblyPage ?: 1,
                            plansPage = plansPage ?: 1,
                            source = "3d",
                            cabinet = cabinet,
                            room = room,
                        )
                    )
                },
                onBack = { navController.popBackStack() },
                overridePdfMarkupStore = session.pdfMarkupStore,
                pdfMarkupReadOnly = true,
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
                navArgument("hideUi") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            LaunchedEffect(session) {
                session.assemblyScanCoordinator.refresh(RefreshReason.APP_START, force = true)
            }
            val startPageAssembly = backStackEntry.arguments?.getInt("startPageAssembly") ?: 1
            val startPagePlans = backStackEntry.arguments?.getInt("startPagePlans") ?: 1
            val initialSource = backStackEntry.arguments?.getString("source")
                ?.let { URLDecoder.decode(it, "UTF-8") }
            val initialCabinet = backStackEntry.arguments?.getString("cab")
                ?.let { URLDecoder.decode(it, "UTF-8") }
            val initialRoom = backStackEntry.arguments?.getString("room")
                ?.let { URLDecoder.decode(it, "UTF-8") }
            val initialLayout = backStackEntry.arguments?.getString("layout")
                ?.let { runCatching { AssemblyViewLayout.valueOf(it) }.getOrNull() }
            val initialFirstPane = backStackEntry.arguments?.getString("first")
                ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
            val initialSecondPane = backStackEntry.arguments?.getString("second")
                ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
            val initialHideUi = backStackEntry.arguments?.getString("hideUi") == "1"
            val refreshGeneration = session.assemblyScanCoordinator.state.collectAsState().value.snapshot.generation
            AssemblyViewerScreen(
                jobRepository = session.jobRepository,
                assemblyStateStore = session.assemblyStateStore,
                specialtyStateStore = session.specialtyStateStore,
                jobFolderName = session.folderName,
                basePath = session.baseDir.absolutePath,
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
                isDarkTheme = isDarkTheme && !useStandardSheets,
                onBack = { navController.popBackStack() },
                overridePdfMarkupStore = session.pdfMarkupStore,
                pdfMarkupReadOnly = true,
            )
        }
    }
}

@Composable
private fun ArchiveCncDetail(
    session: ArchiveSession,
    appStateFlags: AppStateFeatureFlags,
    navController: NavHostController,
    onExitArchive: () -> Unit,
) {
    JobDetailScreen(
        scanCoordinator = session.scanCoordinator,
        appStateStore = session.appStateStore,
        jobRepository = session.jobRepository,
        progressStore = session.progressStore,
        specialtyStateStore = session.specialtyStateStore,
        appStateFlags = appStateFlags,
        jobFolderName = session.folderName,
        onMaterialClick = { material, startPage ->
            navController.navigate("viewer/${URLEncoder.encode(material.pdfFilename, "UTF-8")}/$startPage")
        },
        onOpenReferenceDocument = { docType, startPage ->
            navController.navigate("referenceViewer/${URLEncoder.encode(docType.name, "UTF-8")}/$startPage")
        },
        onOpenThreeD = {
            val target = resolveDefaultThreeDTarget(session.baseDir, session.jobRepository, session.folderName)
            navController.navigate(
                assemblyViewerRoute(
                    jobFolderName = session.folderName,
                    assemblyPage = target.assemblyPage,
                    plansPage = target.plansPage,
                    source = "3d",
                    room = target.room,
                )
            )
        },
        onSubmitPendingBadParts = { material ->
            session.progressStore.submitPendingBadParts(
                jobFolderName = session.folderName,
                pdfFilename = material.pdfFilename,
                fileFingerprint = material.fileFingerprint,
            )
        },
        onBack = onExitArchive,
        tabletId = session.tabletId,
        archiveClientFactory = { null },
        onArchiveCompleted = {},
    )
}

@Composable
private fun ArchiveHardwoodsDetail(
    session: ArchiveSession,
    navController: NavHostController,
    onExitArchive: () -> Unit,
) {
    HardwoodsJobDetailScreen(
        scanCoordinator = session.hardwoodsScanCoordinator,
        progressStore = session.hardwoodsProgressStore,
        jobRepository = session.jobRepository,
        specialtyStateStore = session.specialtyStateStore,
        jobFolderName = session.folderName,
        onOpenWorkspace = { docType ->
            navController.navigate("hardwoods/workspace/${URLEncoder.encode(docType.name, "UTF-8")}/_")
        },
        onOpenRipCutList = {
            navController.navigate(
                "hardwoods/workspace/${URLEncoder.encode(HardwoodDocType.FACE_FRAME_CUT_LIST.name, "UTF-8")}/$HARDWOODS_RIP_CUT_LIST_ROW_ID"
            )
        },
        onOpenReferenceDocument = { docType, startPage ->
            navController.navigate("referenceViewer/${URLEncoder.encode(docType.name, "UTF-8")}/$startPage")
        },
        onOpenThreeD = {
            val target = resolveDefaultThreeDTarget(session.baseDir, session.jobRepository, session.folderName)
            navController.navigate(
                assemblyViewerRoute(
                    jobFolderName = session.folderName,
                    assemblyPage = target.assemblyPage,
                    plansPage = target.plansPage,
                    source = "3d",
                    room = target.room,
                )
            )
        },
        onBack = onExitArchive,
    )
}

@Composable
private fun ArchiveSpecialtyDetail(
    session: ArchiveSession,
    specialtyViewerDefaultsStore: SpecialtyViewerDefaultsStore,
    navController: NavHostController,
    onExitArchive: () -> Unit,
) {
    val availability by produceState(SpecialtyAvailability(), session.folderName) {
        value = withContext(Dispatchers.IO) {
            loadSpecialtyAvailability(session.jobRepository, session.folderName)
        }
    }
    SpecialtyJobDetailScreen(
        jobFolderName = session.folderName,
        specialtyStateStore = session.specialtyStateStore,
        specialtyViewerDefaultsStore = specialtyViewerDefaultsStore,
        jobRepository = session.jobRepository,
        hasAssemblySheet = availability.hasAssemblySheet,
        hasPlansElevations = availability.hasPlansElevations,
        hasDeliverySheet = availability.hasDeliverySheet,
        hasThreeDAssets = availability.hasThreeDAssets,
        hasClosetRods = availability.hasClosetRods,
        onOpenReferenceDocument = { docType, startPage ->
            navController.navigate("referenceViewer/${URLEncoder.encode(docType.name, "UTF-8")}/$startPage")
        },
        onOpenThreeD = {
            val room = resolveSpecialtyThreeDRoom(session.baseDir, session.folderName)
            if (room != null) {
                navController.navigate(
                    assemblyViewerRoute(
                        jobFolderName = session.folderName,
                        assemblyPage = 1,
                        plansPage = 1,
                        source = "3d",
                        room = room,
                    )
                )
            }
        },
        onOpenDoorPanels = {
            navController.navigate(
                "hardwoods/workspace/${HardwoodDocType.DOOR_CUT_LIST.name}/$HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID"
            )
        },
        onOpenSawRipList = {
            navController.navigate(
                "hardwoods/workspace/${HardwoodDocType.DOOR_CUT_LIST.name}/$HARDWOODS_SAW_RIP_LIST_ROW_ID"
            )
        },
        onOpenClosetRods = {
            navController.navigate("hardwoods/workspace/${HardwoodDocType.CLOSET_ROD_CUT_LIST.name}/_")
        },
        onOpenSplitView = {
            navController.navigate(assemblyViewerRoute(session.folderName, 1, 1))
        },
        onJumpToCabinet = { cabinet ->
            navController.navigate(assemblyViewerRoute(session.folderName, 1, 1, cabinet = cabinet))
        },
        onBack = onExitArchive,
    )
}
