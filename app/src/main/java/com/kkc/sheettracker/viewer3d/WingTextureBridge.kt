package com.kkc.sheettracker.viewer3d

import android.graphics.Bitmap
import com.kkc.sheettracker.data.cvjob.CvMaterialTexture
import com.kkc.sheettracker.data.cvjob.CvMaterialTextureLibrary
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.isDoorHardwareRole
import com.kkc.sheettracker.ui.doorpreview.loadDoorTextureBitmap
import java.io.File
import java.security.MessageDigest

data class WingTextureDescriptor(
    val texture: CvMaterialTexture,
    val worldWidthInches: Double?,
    val worldHeightInches: Double?,
    val tile: Boolean
)

/** Converts the established native JXL material cache into a WebView-safe local PNG only on demand. */
object WingTextureBridge {
    fun descriptor(library: CvMaterialTextureLibrary, materialId: String?, materialName: String): WingTextureDescriptor? =
        library.faceTexture(materialId, materialName)?.let { texture ->
            WingTextureDescriptor(texture, texture.worldWidthInches, texture.worldHeightInches, texture.tile)
        }

    suspend fun materialize(cacheDirectory: File, descriptor: WingTextureDescriptor): File? {
        val bitmap = loadDoorTextureBitmap(descriptor.texture) ?: return null
        cacheDirectory.mkdirs()
        val key = MessageDigest.getInstance("SHA-256")
            .digest((descriptor.texture.file.absolutePath + descriptor.texture.file.lastModified()).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(cacheDirectory, "$key.png")
        if (target.isFile && target.length() > 0) return target
        val temporary = File(cacheDirectory, "$key.tmp")
        return try {
            temporary.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            if (temporary.renameTo(target)) target else null
        } finally {
            temporary.delete()
        }
    }

    /** Board IDs remain canonical; DOR uses its linked manufacturing component, never sourcePid. */
    fun descriptorFor(
        part: WingScenePart,
        doorsById: Map<String, DoorPreviewData>,
        library: CvMaterialTextureLibrary
    ): WingTextureDescriptor? {
        part.material?.materialId?.let { return descriptor(library, it, "") }
        val door = part.doorId?.let(doorsById::get) ?: return null
        val component = door.parts.firstOrNull { !isDoorHardwareRole(it.role) } ?: return null
        return descriptor(library, component.materialId, component.material)
    }
}
