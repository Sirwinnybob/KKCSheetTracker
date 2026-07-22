package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.data.models.MoldingUsage // used by Task 8's fetchUsage()
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
}
