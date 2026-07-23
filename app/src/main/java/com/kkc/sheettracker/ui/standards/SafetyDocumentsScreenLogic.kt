package com.kkc.sheettracker.ui.standards

import java.io.File

/**
 * Pure listing logic for the read-only Safety / SDS document library. Data comes from the
 * `.safety` folder at the root of the shared Ready Jobs tree (a sibling of `.metadata` and the
 * job folders, NOT nested under `.metadata`) — this screen never writes anything.
 */
object SafetyDocumentsScreenLogic {

    fun listPdfs(safetyDir: File): List<File> {
        if (!safetyDir.isDirectory) return emptyList()
        return safetyDir.listFiles { file -> file.isFile && file.extension.equals("pdf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
}
