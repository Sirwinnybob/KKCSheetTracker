package com.kkc.sheettracker.data.mixorder

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.kkc.sheettracker.data.mixfiles.MixDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Whether Apply used the smart suggestion or the default page order. Serializes lowercase. */
enum class MixOrderMode(val wire: String) {
    @SerializedName("smart") SMART("smart"),
    @SerializedName("default") DEFAULT("default")
}

/** Everything the screen knew about a new-mix draft when it computed a suggestion. */
data class SmartMixContext(
    val mode: MixOrderMode,
    val sheets: List<SmartSheet>,
    val defaultOrder: List<String>,
    val suggestedOrder: List<String>,
    val weights: MixOrderWeights
)

/**
 * One learning example: what the app suggested versus what the user finally applied.
 * Orders hold [SmartSheet.key] values.
 */
data class MixOrderRecord(
    val version: Int = 1,
    val kind: String = "record",
    val recordId: String,
    val job: String,
    val material: String,
    val mixFile: String,
    val tabletId: String,
    val appliedAt: String,
    val appVersion: String,
    val mode: String,
    val edgeBandIn: Double,
    val smallPartIn: Double,
    val weightsUsed: List<Double>,
    val defaultOrder: List<String>,
    val suggestedOrder: List<String>,
    val finalOrder: List<String>,
    val sheets: Map<String, SheetFeatures>,
    val lockedSheets: List<String>
)

/** A PC- or tablet-produced file that packs many records; readers dedupe them by `recordId`. */
data class MixOrderBundle(
    val version: Int = 1,
    val kind: String = "bundle",
    val records: List<MixOrderRecord> = emptyList()
)

object MixOrderJson {
    const val CURRENT_VERSION = 1

    val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}

fun MixOrderRecord.toJson(): String = MixOrderJson.gson.toJson(this)

/** Null for anything that is not a usable record. Version checks are the reader's business. */
fun parseMixOrderRecord(json: String): MixOrderRecord? =
    runCatching { MixOrderJson.gson.fromJson(json, MixOrderRecord::class.java) }.getOrNull()?.takeIf { it.isUsable() }

// Gson bypasses Kotlin null checks, so hand-edited or truncated files can carry nulls in non-null fields.
@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
internal fun MixOrderRecord.isUsable(): Boolean =
    recordId != null && recordId.isNotBlank() &&
        defaultOrder != null && suggestedOrder != null && finalOrder != null &&
        sheets != null && lockedSheets != null && weightsUsed != null

private val appliedAtFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
}

/**
 * Record for a saved new mix that had smart context; null otherwise (existing mixes, failed saves
 * and drafts without a suggestion are never logged). `finalOrder` is the sheets in the order of
 * their first PGM in [submitted]; a sheet is locked when any of its PGMs is in [lockedPgms].
 */
fun buildMixOrderRecord(
    submitted: MixDraft,
    context: SmartMixContext?,
    saved: Boolean,
    lockedPgms: Collection<String>,
    job: String,
    material: String,
    tabletId: String,
    appVersion: String,
    now: Long,
    recordId: String = UUID.randomUUID().toString()
): MixOrderRecord? {
    if (!saved || submitted.newFile == null || context == null) return null

    val sheetByPgm = HashMap<String, SmartSheet>()
    for (sheet in context.sheets) for (pgm in sheet.pgms) sheetByPgm.putIfAbsent(pgm.lowercase(Locale.ROOT), sheet)

    val finalSheets = LinkedHashSet<SmartSheet>()
    for (entry in submitted.entries) {
        val pgm = entry.source?.pgm ?: entry.newPgm ?: continue
        sheetByPgm[pgm.lowercase(Locale.ROOT)]?.let { finalSheets += it }
    }
    val locked = lockedPgms.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    return MixOrderRecord(
        recordId = recordId,
        job = job,
        material = material,
        mixFile = submitted.newFile,
        tabletId = tabletId,
        appliedAt = appliedAtFormat.get()!!.format(Date(now)),
        appVersion = appVersion,
        mode = context.mode.wire,
        edgeBandIn = EDGE_BAND_IN,
        smallPartIn = SMALL_PART_IN,
        weightsUsed = context.weights.values,
        defaultOrder = context.defaultOrder,
        suggestedOrder = context.suggestedOrder,
        finalOrder = finalSheets.map { it.key },
        sheets = context.sheets.associate { it.key to it.features },
        lockedSheets = finalSheets.filter { sheet -> sheet.pgms.any { it.lowercase(Locale.ROOT) in locked } }.map { it.key }
    )
}
