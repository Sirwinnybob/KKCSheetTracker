package com.kkc.sheettracker.data.cvjob

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

const val CV_MATERIAL_TEXTURES_RELATIVE_PATH = ".metadata/material_textures"

/** CV IDs are canonical; names support older exports without material IDs. */
data class DoorMaterialRef(val id: String? = null, val name: String = "")

data class CvMaterialTexture(
    val file: File,
    val worldWidthInches: Double?,
    val worldHeightInches: Double?,
    val tile: Boolean,
    val sha256: String? = null
)

/** Read-only catalog. Missing/blank/Paint Grade faces deliberately resolve to no image (white). */
class CvMaterialTextureLibrary internal constructor(
    private val faces: Map<String, CvMaterialTexture?> = emptyMap(),
    private val names: Map<String, List<String>> = emptyMap()
) {
    val isEmpty: Boolean get() = faces.isEmpty()

    fun faceTexture(materialId: String?, materialName: String): CvMaterialTexture? {
        val id = materialId?.trim()?.takeIf { it.isNotEmpty() }
            ?: names[materialName.trim().lowercase(Locale.ROOT)]?.singleOrNull() ?: return null
        return faces[id]?.takeIf { it.file.isFile }
    }

    companion object { val Empty = CvMaterialTextureLibrary() }
}

/** Reads only the exact shared manifest; malformed/partial/unavailable sync data means white. */
fun loadCvMaterialTextures(root: File): CvMaterialTextureLibrary = try {
    parseCvMaterialTextures(File(root, "manifest.json").readText(), root)
} catch (_: Exception) { CvMaterialTextureLibrary.Empty }

fun parseCvMaterialTextures(json: String, root: File): CvMaterialTextureLibrary = try {
    val document = JsonParser.parseString(json).asJsonObject
    if (document.textureText("schemaVersion") != "1") CvMaterialTextureLibrary.Empty else {
        val textures = document.textureObjects("textures").associate { texture ->
            val name = texture.textureText("name").orEmpty().trim().lowercase(Locale.ROOT)
            val sourceName = texture.textureText("sourcePath").orEmpty().replace('\\', '/').substringAfterLast('/')
            val ignored = name in setOf("", "blank", "no texture", "paint grade") ||
                sourceName.equals("Cabinets.jpg", ignoreCase = true)
            val file = safeTextureFile(root, texture.textureText("file").orEmpty())
            texture.textureText("id").orEmpty() to if (ignored || file == null) null else CvMaterialTexture(
                file = file,
                worldWidthInches = texture.textureInches("worldWidthMm"),
                worldHeightInches = texture.textureInches("worldHeightMm"),
                tile = texture.textureText("tile") == "true",
                sha256 = texture.textureText("sha256")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            )
        }
        val materials = document.textureObjects("materials")
        CvMaterialTextureLibrary(
            faces = materials.associate { material ->
                material.textureText("id").orEmpty() to material.textureObjects("layers")
                    .singleOrNull { it.textureText("typeId") == "1" }
                    ?.textureText("textureId")?.let { textures[it] }
            },
            names = materials.groupBy({ it.textureText("name").orEmpty().trim().lowercase(Locale.ROOT) },
                { it.textureText("id").orEmpty() })
        )
    }
} catch (_: Exception) { CvMaterialTextureLibrary.Empty }

private fun safeTextureFile(root: File, relative: String): File? {
    val path = relative.replace('\\', '/')
    if (path.isBlank() || path.startsWith('/') || ':' in path || path.split('/').any { it == ".." } ||
        !path.endsWith(".jxl", ignoreCase = true)) return null
    val canonicalRoot = root.canonicalFile
    return File(canonicalRoot, path).canonicalFile.takeIf { it.toPath().startsWith(canonicalRoot.toPath()) }
}

private fun JsonObject.textureText(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.textureObjects(key: String): List<JsonObject> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
private fun JsonObject.textureInches(key: String): Double? =
    textureText(key)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.div(25.4)
