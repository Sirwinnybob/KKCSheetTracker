package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import java.net.URLEncoder

internal const val VIEWER_ROUTE_PATTERN = "viewer/{folderName}/{pdfFilename}/{startPage}?mixName={mixName}&mixFile={mixFile}&preferStartPage={preferStartPage}"

// Navigation uses Uri.decode, which leaves literal '+' intact. Encode spaces as %20.
internal fun viewerRouteSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun viewerRoute(jobFolderName: String, pdfFilename: String, page: Int,
    mixSelection: ViewerMixSelection? = null, preferStartPage: Boolean = false): String {
    val base = "viewer/${viewerRouteSegment(jobFolderName)}/${viewerRouteSegment(pdfFilename)}/$page"
    val query = buildList {
        mixSelection?.let { add("mixName=${viewerRouteSegment(it.name)}"); add("mixFile=${viewerRouteSegment(it.mixFile)}") }
        if (preferStartPage) add("preferStartPage=true")
    }
    return base + if (query.isEmpty()) "" else "?" + query.joinToString("&")
}

/** NavBackStackEntry String arguments have already been decoded once by Navigation. */
internal fun viewerMixSelection(name: String?, file: String?): ViewerMixSelection? =
    if (name == null || file == null) null else ViewerMixSelection(name, file)
