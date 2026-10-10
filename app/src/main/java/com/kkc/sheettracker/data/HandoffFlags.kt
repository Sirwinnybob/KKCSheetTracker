package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.SpecialtyItem
import java.io.File

/**
 * THE mapping from a KST item to its `handoff.json` (source, id). Used for reading keys, the REST
 * body and the sidecar — never build a ref any other way.
 *
 * Merged specialty items already carry the right id: office items keep their raw id (also when a
 * tablet overlay edited them, see SpecialtyProgressStore.mergeSpecialtyItems), tablet-created
 * items carry `tablet:<uuid>`, checklist rows carry KST's `checklist:<id>` which HT keys by the
 * raw id (HT silently accepts a wrong id, so this strip is load-bearing).
 */
fun handoffFlagRef(item: SpecialtyItem): HandoffFlagRef =
    if (item.id.startsWith("checklist:")) HandoffFlagRef(HandoffFlagRef.CHECKLIST, item.id.removePrefix("checklist:"))
    else HandoffFlagRef(HandoffFlagRef.SPECIALTY_ITEMS, item.id)

fun handoffFlagRef(item: AdminBoardStockItem): HandoffFlagRef =
    HandoffFlagRef(HandoffFlagRef.BOARD_STOCK, item.id.trim())

internal fun handoffJsonFile(baseDir: File, jobFolderName: String): File =
    File(baseDir, "$jobFolderName/.metadata/admin/handoff.json")

/**
 * Reads `itemFlags` from HT's `handoff.json` (HT is the only writer). Unknown flag names or values
 * are Auto; an entry left with no known flag is dropped; other top-level keys are ignored.
 */
internal fun parseHandoffItemFlags(json: String): Map<HandoffFlagRef, HandoffItemFlags> {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
    val itemFlags = root.get("itemFlags")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
    return itemFlags.entrySet().mapNotNull { (key, value) ->
        val ref = HandoffFlagRef.fromKey(key) ?: return@mapNotNull null
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val flags = HandoffItemFlags(
            truck = HandoffTruck.fromWire(obj.primitiveString("truck")),
            count = HandoffCount.fromWire(obj.primitiveString("count"))
        )
        if (flags.isAuto) null else ref to flags
    }.toMap()
}

private fun JsonObject.primitiveString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

/** Missing file, corrupt JSON or missing `itemFlags` all mean "no flags". Call on IO. */
fun loadHandoffItemFlags(baseDir: File, jobFolderName: String): Map<HandoffFlagRef, HandoffItemFlags> {
    val file = handoffJsonFile(baseDir, jobFolderName)
    if (!file.isFile) return emptyMap()
    return runCatching { parseHandoffItemFlags(file.readText()) }.getOrElse { emptyMap() }
}
