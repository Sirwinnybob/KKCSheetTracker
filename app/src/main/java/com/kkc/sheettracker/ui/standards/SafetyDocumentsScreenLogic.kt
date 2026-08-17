package com.kkc.sheettracker.ui.standards

import java.io.File

/**
 * Pure listing logic for the read-only Safety / SDS document library. Data comes from the
 * `.safety` folder at the root of the shared Ready Jobs tree (a sibling of `.metadata` and the
 * job folders, NOT nested under `.metadata`) — this screen never writes anything.
 */
object SafetyDocumentsScreenLogic {

    val tabTitles = listOf(
        "Documents (PDFs)",
        "Safety Committee Meetings",
        "Safety Concerns"
    )

    fun meetingDocumentsDir(basePath: String): File =
        File(File(basePath, ".safety"), "safety_meetings")

    fun listPdfs(safetyDir: File): List<File> {
        if (!safetyDir.isDirectory) return emptyList()
        return safetyDir.listFiles { file -> file.isFile && file.extension.equals("pdf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * The safety concerns feed is visible when the user has explicitly subscribed with the
     * safety password OR when admin mode is unlocked. Admin mode grants access without
     * persisting a separate safety subscription, so access follows the admin session.
     */
    fun hasSafetyConcernsAccess(safetySubscriber: Boolean, adminMode: Boolean): Boolean =
        safetySubscriber || adminMode
}
