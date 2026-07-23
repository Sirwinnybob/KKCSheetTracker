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
            name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
            frameStyle = obj.get("frameStyle")?.takeIf { !it.isJsonNull }?.asString,
            hidden = obj.get("hidden")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        )
    }
    return MoldingLibrary(categories = categories, moldings = moldings)
}

internal fun processSvgForDarkMode(svgXml: String): String {
    var result = svgXml
        .replace("stroke=\"#000000\"", "stroke=\"#ffffff\"")
        .replace("stroke=\"#000\"", "stroke=\"#ffffff\"")
        .replace("stroke=\"black\"", "stroke=\"#ffffff\"")
        .replace("fill=\"#000000\"", "fill=\"#ffffff\"")
        .replace("fill=\"#000\"", "fill=\"#ffffff\"")
        .replace("fill=\"black\"", "fill=\"#ffffff\"")

    result = result.replace(Regex("""<rect([^>]*)\bfill="(#ffffff|white)"""")) { match ->
        "<rect${match.groupValues[1]}fill=\"#000000\""
    }
    return result
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

    fun profileSvgBytes(category: String, fileId: String, showMeasurements: Boolean, isDarkPreview: Boolean): ByteArray? {
        val file = profileSvgFile(category, fileId, showMeasurements) ?: return null
        if (!isDarkPreview) return file.readBytes()
        val text = file.readText()
        return processSvgForDarkMode(text).toByteArray(Charsets.UTF_8)
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
                val deliveredVal = (obj.get("deliveredFeet") ?: obj.get("deliveredAmount") ?: obj.get("delivered"))
                    ?.takeIf { !it.isJsonNull }?.asDouble
                MoldingUsage(
                    job = obj.get("job")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString,
                    estimatedFeet = obj.get("estimatedFeet")?.takeIf { !it.isJsonNull }?.asDouble,
                    deliveredFeet = deliveredVal
                )
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Job counts per molding id in one read of usage_index.json, instead of the N+1 pattern of
     * calling [fetchUsage] once per molding (used by the molding grid to badge every visible card).
     */
    fun fetchUsageCounts(): Map<String, Int> {
        val file = File(cacheDir, "usage_index.json")
        if (!file.exists() || !file.isFile) return emptyMap()
        return runCatching {
            val root = moldingLibraryGson.fromJson(file.readText(), JsonObject::class.java)
                ?: return@runCatching emptyMap()
            root.entrySet().associate { (key, value) ->
                key to (value.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0)
            }
        }.getOrElse { emptyMap() }
    }
}
