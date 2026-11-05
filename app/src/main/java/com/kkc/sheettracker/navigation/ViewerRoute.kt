package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import java.net.URLEncoder

internal const val VIEWER_ROUTE_PATTERN = "viewer/{folderName}/{pdfFilename}/{startPage}?mixName={mixName}&mixFile={mixFile}&preferStartPage={preferStartPage}&machine={machine}"

// Navigation uses Uri.decode, which leaves literal '+' intact. Encode spaces as %20.
internal fun viewerRouteSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int,
    mixSelection: ViewerMixSelection? = null, preferStartPage: Boolean = false,
    /** The CNC whose catalog supplies mix context; null lets the viewer resolve the owner itself. */
    machineId: String? = null): String {
    val base = "viewer/${viewerRouteSegment(jobFolderName)}/${viewerRouteSegment(pdfFilename)}/$page"
    val query = buildList {
        mixSelection?.let { add("mixName=${viewerRouteSegment(it.name)}"); add("mixFile=${viewerRouteSegment(it.mixFile)}") }
        if (preferStartPage) add("preferStartPage=true")
        machineId?.let { add("machine=${viewerRouteSegment(it)}") }
    }
    return base + if (query.isEmpty()) "" else "?" + query.joinToString("&")
}

/**
 * Horizontal direction for a sheet-viewer to sheet-viewer swap (the navbar's switch-CNC jump):
 * 1 = the new viewer enters from the right (Blue SCM), -1 = from the left (Red SCM). Machines sit
 * left to right in [CncMachines.all] order, so each machine always arrives from its own side.
 * Null when either side isn't the viewer, so other routes keep the NavHost's default transition.
 */
internal fun cncSwitchSlideDirection(fromRoute: String?, toRoute: String?, toMachineId: String?): Int? {
    if (fromRoute != VIEWER_ROUTE_PATTERN || toRoute != VIEWER_ROUTE_PATTERN) return null
    val index = CncMachines.all.indexOfFirst { it.id == toMachineId }
    return if (index == 0) -1 else 1
}

/** NavBackStackEntry String arguments have already been decoded once by Navigation. */
internal fun viewerMixSelection(name: String?, file: String?): ViewerMixSelection? =
    if (name == null || file == null) null else ViewerMixSelection(name, file)

/**
 * Mixes route bound to one (job, material, machine); a null machine lets Mixes resolve the owner.
 * Folder and material keep the original URLEncoder form because that destination decodes them again.
 */
internal fun manageCodeRoute(jobFolderName: String, material: String? = null, machineId: String? = null): String {
    val query = buildList {
        material?.let { add("material=${URLEncoder.encode(it, "UTF-8")}") }
        machineId?.let { add("machine=${viewerRouteSegment(it)}") }
    }
    return "manage_code/${URLEncoder.encode(jobFolderName, "UTF-8")}" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
}
