package com.kkc.sheettracker.viewer3d

import java.io.File

/** Returns the supported tablet model for a room, or null when that model is absent. */
fun findMediumGlbForRoom(baseDir: File, jobFolderName: String, roomName: String?): File? {
    val safeRoomName = roomName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val model = File(baseDir, "$jobFolderName/3D/$safeRoomName/3d_medium.glb")
    return model.takeIf { it.isFile }
}
