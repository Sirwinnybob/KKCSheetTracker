package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.data.models.MoldingUsage
import java.io.File

private val moldingLibraryGson = GsonBuilder().create()

internal fun parseMoldingLibrary(json: String): MoldingLibrary {
    val root = moldingLibraryGson.fromJson(json, JsonObject::class.java) ?: return MoldingLibrary()
    val categories = root.getAsJsonArray("categories")?.map { it.asString } ?: emptyList()
    val moldingsArr = root.getAsJsonArray("moldings") ?: return MoldingLibrary(categories = categories)
    val moldings = moldingsArr.map { elem ->
        val obj = elem.asJsonObject
        MoldingLibraryItem(
            id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: "",
            category = obj.get("category")?.takeIf { !it.isJsonNull }?.asString ?: "",
            fileId = obj.get("fileId")?.takeIf { !it.isJsonNull }?.asString ?: "",
            name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }
    return MoldingLibrary(categories = categories, moldings = moldings)
}

/**
 * Reads the molding library cache published by Hours Tracker.
 * Storage path: {baseDir}/.metadata/moldings_cache/
 * Read-only on the tablet; Hours Tracker is the sole writer.
 * Call on Dispatchers.IO.
 */
class MoldingLibraryRepository(private val baseDir: File) {

    internal val cacheDir = File(baseDir, ".metadata/moldings_cache")

    fun fetchLibrary(): MoldingLibrary {
        val file = File(cacheDir, "library.json")
        if (!file.exists() || !file.isFile) return MoldingLibrary()
        return runCatching { parseMoldingLibrary(file.readText()) }.getOrElse { MoldingLibrary() }
    }

    fun profileSvgFile(category: String, fileId: String, showMeasurements: Boolean): File? {
        val suffix = if (showMeasurements) "_dim" else ""
        val file = File(cacheDir, "$category/$fileId$suffix.svg")
        return file.takeIf { it.exists() && it.isFile }
    }

    fun fetchUsage(moldingId: String): List<MoldingUsage> {
        val file = File(cacheDir, "usage_index.json")
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
            val root = moldingLibraryGson.fromJson(file.readText(), JsonObject::class.java)
                ?: return@runCatching emptyList()
            val arr = root.getAsJsonArray(moldingId) ?: return@runCatching emptyList()
            arr.map { elem ->
                val obj = elem.asJsonObject
                MoldingUsage(
                    job = obj.get("job")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString,
                    estimatedFeet = obj.get("estimatedFeet")?.takeIf { !it.isJsonNull }?.asDouble
                )
            }
        }.getOrElse { emptyList() }
    }
}
