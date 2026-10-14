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
import java.time.Instant

/**
 * THE mapping from a KST item to its `handoff.json` (source, id). Used for reading keys, the REST
 * body and the sidecar — never build a ref any other way.
 *
 * Merged specialty items already carry the right id: office items keep their raw id (also when a
 * tablet overlay edited them, see SpecialtyProgressStore.mergeSpecialtyItems), tablet-created
 * items carry `tablet:<uuid>`, checklist rows carry KST's `checklist:<id>` which HT keys by the
 * raw id (HT silently accepts a wrong id, so this strip is load-bearing).
 */
fun handoffFlagRef(item: SpecialtyItem): HandoffFlagRef {
    // HT strips ids, so trim on every branch (the checklist prefix is stripped first, then trimmed).
    val id = item.id.trim()
    return if (id.startsWith("checklist:")) HandoffFlagRef(HandoffFlagRef.CHECKLIST, id.removePrefix("checklist:").trim())
    else HandoffFlagRef(HandoffFlagRef.SPECIALTY_ITEMS, id)
}

fun handoffFlagRef(item: AdminBoardStockItem): HandoffFlagRef =
    HandoffFlagRef(HandoffFlagRef.BOARD_STOCK, item.id.trim())

internal fun handoffJsonFile(baseDir: File, jobFolderName: String): File =
    File(baseDir, "$jobFolderName/.metadata/admin/handoff.json")

/**
 * Reads `itemFlags` from HT's `handoff.json` (HT is the only writer). Unknown flag names or values
 * are Auto; an entry left with no known flag is dropped; other top-level keys are ignored.
 */
internal fun parseHandoffItemFlags(json: String): Map<HandoffFlagRef, HandoffItemFlags> {
    val root = parseRoot(json) ?: return emptyMap()
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

private fun parseRoot(json: String): JsonObject? =
    runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.primitiveString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

/**
 * `itemFlagsSetAt[key].truck` (ISO instant) per item: when HT last applied a truck change, from
 * this tablet or the office. Tolerant: anything that is not an object holding a parseable instant
 * is skipped. Used only to decide that a queued request has since been applied or overridden.
 */
internal fun parseHandoffTruckSetAt(json: String): Map<HandoffFlagRef, Instant> {
    val setAt = parseRoot(json)?.get("itemFlagsSetAt")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
    return setAt.entrySet().mapNotNull { (key, value) ->
        val ref = HandoffFlagRef.fromKey(key) ?: return@mapNotNull null
        val text = value.takeIf { it.isJsonObject }?.asJsonObject?.primitiveString("truck") ?: return@mapNotNull null
        val at = runCatching { Instant.parse(text) }.getOrNull() ?: return@mapNotNull null
        ref to at
    }.toMap()
}

/** What the tablet reads from `handoff.json`: pinned flags plus when each truck pin was last set. */
data class HandoffJsonState(
    val flags: Map<HandoffFlagRef, HandoffItemFlags> = emptyMap(),
    val truckSetAt: Map<HandoffFlagRef, Instant> = emptyMap()
)

/**
 * Reads `handoff.json` once (exactly that file; `.sync-conflict-` copies are never read). Missing
 * file, corrupt JSON or missing sections all mean "no flags". Call on IO.
 */
fun loadHandoffJsonState(baseDir: File, jobFolderName: String): HandoffJsonState {
    val file = handoffJsonFile(baseDir, jobFolderName)
    if (!file.isFile) return HandoffJsonState()
    val text = runCatching { file.readText() }.getOrNull() ?: return HandoffJsonState()
    return HandoffJsonState(
        flags = runCatching { parseHandoffItemFlags(text) }.getOrElse { emptyMap() },
        truckSetAt = runCatching { parseHandoffTruckSetAt(text) }.getOrElse { emptyMap() }
    )
}

/** Missing file, corrupt JSON or missing `itemFlags` all mean "no flags". Call on IO. */
fun loadHandoffItemFlags(baseDir: File, jobFolderName: String): Map<HandoffFlagRef, HandoffItemFlags> =
    loadHandoffJsonState(baseDir, jobFolderName).flags
