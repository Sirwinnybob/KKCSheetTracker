package com.kkc.sheettracker.navigation

import android.app.Activity
import android.util.Log
import androidx.navigation.NavHostController
import com.kkc.sheettracker.ui.components.NavDestination
import java.net.URLEncoder

private const val NAV_TAG = "KKC_NAV"
private const val NAV_CLICK_DEBOUNCE_MS = 180L

enum class TopLevelTab(val route: String) {
    DASHBOARD("dashboard"),
    JOBS("jobs"),
    SEARCH("search"),
    HOURS("hours"),
    SETTINGS("settings"),
    SUPPLY("supply");

    companion object {
        fun fromDestination(destination: NavDestination): TopLevelTab {
            return when (destination) {
                NavDestination.DASHBOARD -> DASHBOARD
                NavDestination.JOBS -> JOBS
                NavDestination.SEARCH -> SEARCH
                NavDestination.HOURS -> HOURS
                NavDestination.SETTINGS -> SETTINGS
                NavDestination.SUPPLY -> SUPPLY
            }
        }

        fun toDestination(tab: TopLevelTab): NavDestination {
            return when (tab) {
                DASHBOARD -> NavDestination.DASHBOARD
                JOBS -> NavDestination.JOBS
                SEARCH -> NavDestination.SEARCH
                HOURS -> NavDestination.HOURS
                SETTINGS -> NavDestination.SETTINGS
                SUPPLY -> NavDestination.SUPPLY
            }
        }
    }
}

class NavigationCoordinator(
    private val dashboardNavController: NavHostController,
    private val jobsNavController: NavHostController,
    private val searchNavController: NavHostController,
    private val hoursNavController: NavHostController,
    private val settingsNavController: NavHostController,
    private val supplyNavController: NavHostController,
    private val getHomeTab: () -> TopLevelTab,
    private val getSelectedTab: () -> TopLevelTab,
    private val setSelectedTab: (TopLevelTab) -> Unit
) {
    private var lastTopLevelTapAt = 0L
    private var navInFlight = false

    private fun controllerFor(tab: TopLevelTab): NavHostController {
        return when (tab) {
            TopLevelTab.DASHBOARD -> dashboardNavController
            TopLevelTab.JOBS -> jobsNavController
            TopLevelTab.SEARCH -> searchNavController
            TopLevelTab.HOURS -> hoursNavController
            TopLevelTab.SETTINGS -> settingsNavController
            TopLevelTab.SUPPLY -> supplyNavController
        }
    }

    private fun currentController(): NavHostController = controllerFor(getSelectedTab())

    fun navigateTopLevel(tab: TopLevelTab, reselectPopToRoot: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - lastTopLevelTapAt < NAV_CLICK_DEBOUNCE_MS) {
            Log.d(NAV_TAG, "tab_switch_rejected reason=debounce target=${tab.route}")
            return
        }
        if (navInFlight) {
            Log.d(NAV_TAG, "tab_switch_rejected reason=in_flight target=${tab.route}")
            return
        }
        lastTopLevelTapAt = now
        navInFlight = true
        try {
            val source = getSelectedTab()
            if (source == tab) {
                if (reselectPopToRoot) {
                    val popped = controllerFor(tab).popBackStack(tab.route, false)
                    Log.d(NAV_TAG, "tab_reselect tab=${tab.route} pop_to_root=$popped")
                }
                return
            }
            setSelectedTab(tab)
            Log.d(NAV_TAG, "tab_switch source=${source.route} target=${tab.route}")
        } finally {
            navInFlight = false
        }
    }

    fun openSheetInJobs(jobFolderName: String, pdfFilename: String, page: Int) {
        val targetRoute = viewerRoute(jobFolderName, pdfFilename, page)
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "sheet_open_tab_switch target=jobs")
        }
        val controller = jobsNavController
        if (isCurrentViewerTarget(controller, jobFolderName, pdfFilename, page)) {
            Log.d(NAV_TAG, "sheet_open_dedup route=$targetRoute")
            return
        }
        controller.navigate(targetRoute) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "sheet_open route=$targetRoute")
    }

    fun openJobDetailInJobs(jobFolderName: String) {
        val targetRoute = "job/${URLEncoder.encode(jobFolderName, "UTF-8")}"
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "job_detail_tab_switch target=jobs")
        }
        jobsNavController.navigate(targetRoute) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "job_detail_open route=$targetRoute")
    }

    fun openHardwoodsJobInJobs(jobFolderName: String) {
        val targetRoute = "hardwoods/job/${URLEncoder.encode(jobFolderName, "UTF-8")}"
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "hardwoods_open_tab_switch target=jobs")
        }
        jobsNavController.navigate(targetRoute) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "hardwoods_open route=$targetRoute")
    }

    fun openSpecialtyJobInJobs(jobFolderName: String) {
        val targetRoute = "specialty/job/${URLEncoder.encode(jobFolderName, "UTF-8")}"
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "specialty_open_tab_switch target=jobs")
        }
        jobsNavController.navigate(targetRoute) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "specialty_open route=$targetRoute")
    }

    fun openHardwoodsRouteInJobs(route: String) {
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "hardwoods_route_tab_switch target=jobs")
        }
        jobsNavController.navigate(route) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "hardwoods_route_open route=$route")
    }

    fun openAssemblyViewerInJobs(jobFolderName: String, assemblyPage: Int, plansPage: Int) {
        val targetRoute = "assembly/viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/$assemblyPage/$plansPage"
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "assembly_viewer_tab_switch target=jobs")
        }
        jobsNavController.navigate(targetRoute) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "assembly_viewer_open route=$targetRoute")
    }

    fun openAssemblyJobInJobs(jobFolderName: String) {
        val targetRoute = "assembly/job/${URLEncoder.encode(jobFolderName, "UTF-8")}"
        if (getSelectedTab() != TopLevelTab.JOBS) {
            setSelectedTab(TopLevelTab.JOBS)
            Log.d(NAV_TAG, "assembly_job_tab_switch target=jobs")
        }
        jobsNavController.navigate(targetRoute) {
            launchSingleTop = true
        }
        Log.d(NAV_TAG, "assembly_job_open route=$targetRoute")
    }

    fun onBackPressed(activity: Activity) {
        val homeTab = getHomeTab()
        val selected = getSelectedTab()
        val controller = currentController()
        val popped = controller.popBackStack()
        if (popped) {
            Log.d(NAV_TAG, "back_action result=pop tab=${selected.route}")
            return
        }
        if (selected != homeTab) {
            setSelectedTab(homeTab)
            Log.d(NAV_TAG, "back_action result=switch_to_home home=${homeTab.route} from=${selected.route}")
            return
        }
        Log.d(NAV_TAG, "back_action result=exit")
        activity.finish()
    }

    private fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int): String {
        return "viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(pdfFilename, "UTF-8")}/$page"
    }

    private fun isCurrentViewerTarget(
        controller: NavHostController,
        jobFolderName: String,
        pdfFilename: String,
        page: Int
    ): Boolean {
        val backStackEntry = controller.currentBackStackEntry ?: return false
        val route = backStackEntry.destination.route ?: return false
        if (!route.startsWith("viewer/")) return false
        val args = backStackEntry.arguments ?: return false
        val currentFolder = args.getString("folderName") ?: return false
        val currentPdf = args.getString("pdfFilename") ?: return false
        val currentPage = args.getInt("startPage")
        return currentFolder == jobFolderName && currentPdf == pdfFilename && currentPage == page
    }
}
