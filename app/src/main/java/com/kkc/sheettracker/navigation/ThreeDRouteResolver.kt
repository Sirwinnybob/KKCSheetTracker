package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.JobRepository
import java.io.File

internal data class ThreeDRouteTarget(
    val assemblyPage: Int,
    val plansPage: Int,
    val room: String?,
)

/** Resolves the first usable 3D room and its corresponding reference pages for a job root. */
internal fun resolveDefaultThreeDTarget(
    baseDir: File,
    jobRepository: JobRepository,
    jobFolderName: String,
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
    val threeDDir = File(baseDir, "$jobFolderName/3D")
    val sheetRooms = assemblyRooms
        .firstOrNull { it.first.equals("Kitchen", ignoreCase = true) }
        ?: assemblyRooms
            .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            .firstOrNull()
    val sheetRoomHasGlb = sheetRooms?.let { (room, _) ->
        File(threeDDir, room).isDirectory && File(threeDDir, "$room/3d_medium.glb").exists()
    } == true
    val firstRoom = if (sheetRoomHasGlb) {
        sheetRooms
    } else {
        val fsRooms = threeDDir.listFiles()
            ?.filter { it.isDirectory && File(it, "3d_medium.glb").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        val fsRoom = fsRooms.firstOrNull { it.equals("Kitchen", ignoreCase = true) }
            ?: fsRooms.firstOrNull()
        if (fsRoom != null) {
            assemblyRooms.firstOrNull { it.first.equals(fsRoom, ignoreCase = true) }
                ?: (fsRoom to (sheetRooms?.second ?: 1))
        } else {
            null
        }
    }

    val firstAssemblyPage = firstRoom?.second
        ?: assemblyCabinetToPages.values.flatten().minOrNull()
        ?: 1
    val firstPlansPage = plansDoc?.cabinetToPages?.values?.flatten()?.minOrNull() ?: 1

    return ThreeDRouteTarget(
        assemblyPage = firstAssemblyPage,
        plansPage = firstPlansPage,
        room = firstRoom?.first,
    )
}

internal fun resolveSpecialtyThreeDRoom(
    baseDir: File,
    jobFolderName: String,
): String? {
    val threeDDir = File(baseDir, "$jobFolderName/3D")
    if (!threeDDir.isDirectory) return null
    val rooms = threeDDir.listFiles()
        ?.filter { it.isDirectory && File(it, "3d_medium.glb").exists() }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()
    if (rooms.isEmpty()) return null
    return rooms.firstOrNull { it.equals("Kitchen", ignoreCase = true) } ?: rooms.first()
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
