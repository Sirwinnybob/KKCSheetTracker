package com.kkc.sheettracker.data.mixorder

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BAD_DIR = "bad"

/**
 * What a consolidation run did. [bundle] is the file written (null when nothing was written or the
 * write failed verification); [leftUnknownVersion] counts files from a newer app that were left alone.
 */
data class ConsolidationResult(
    val records: Int,
    val filesAbsorbed: Int,
    val badMoved: Int,
    val leftUnknownVersion: Int,
    val bundle: File?
)

private val createdAtFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
}

/**
 * Packs the single record files and older bundles in the shared learning folder into one new bundle.
 *
 * Safe against everything a shared, Syncthing-synced folder can throw at it: the input list is fixed up
 * front (files that arrive later are untouched), unreadable or half-written files are moved to `bad/`
 * (never deleted), files from a newer app version stay in place, and nothing is deleted until the new
 * bundle has been read back and holds every gathered `recordId`. Running it again is a no-op.
 */
fun consolidateMixOrderLearning(
    baseDir: File,
    source: String,
    store: MixOrderLearningStore,
    now: Long = System.currentTimeMillis()
): ConsolidationResult = runMixOrderConsolidation(baseDir, source, store, now)

/** [verify] and [afterListing] are test seams; production uses [verifyBundleContains] and no hook. */
internal fun runMixOrderConsolidation(
    baseDir: File,
    source: String,
    store: MixOrderLearningStore,
    now: Long,
    verify: (bundle: File, recordIds: Set<String>) -> Boolean = ::verifyBundleContains,
    afterListing: () -> Unit = {}
): ConsolidationResult {
    // An unmounted share is a missing directory; never create it here.
    if (!baseDir.isDirectory) return ConsolidationResult(0, 0, 0, 0, null)

    runCatching { store.flushPending(baseDir) }

    val root = File(baseDir, LEARNING_DIR)
    val inputs = listInputs(root)
    afterListing()

    var unknownVersion = 0
    var badFound = 0
    var badMoved = 0
    val good = ArrayList<Parsed.Good>()
    for (input in inputs) {
        when (val parsed = parseInput(input.file, input.bundle)) {
            Parsed.Skip -> Unit
            Parsed.Unknown -> unknownVersion++
            Parsed.Bad -> {
                badFound++
                if (moveToBad(root, input.file)) badMoved++
            }
            is Parsed.Good -> good += parsed
        }
    }

    val needsMerge = good.size > 1 || (good.isNotEmpty() && (badFound > 0 || good.any { it.retireToBad }))
    if (!needsMerge) return ConsolidationResult(0, 0, badMoved, unknownVersion, null)

    val byId = LinkedHashMap<String, MixOrderRecord>()
    for (input in good) for (record in input.records) byId.putIfAbsent(record.recordId, record)
    fun failed() = ConsolidationResult(0, 0, badMoved, unknownVersion, null)

    val model = MixOrderBundle(
        createdAt = createdAtFormat.get()!!.format(Date(now)),
        source = source,
        records = byId.values.toList()
    )
    val bundleName = "${mixOrderTimestamp(now)}_${sanitizeFilePart(source)}_bundle.json"
    val bundle = runCatching {
        writeFileAtomically(File(root, BUNDLES_DIR), bundleName, MixOrderJson.gson.toJson(model))
    }.getOrNull() ?: return failed()

    val verified = runCatching { verify(bundle, byId.keys.toSet()) }.getOrDefault(false)
    if (!verified) {
        bundle.delete()
        return failed()
    }

    for (input in good) {
        val file = input.file
        // Gone already, or rewritten since it was read: its new content is not in the bundle, so keep it.
        if (!file.exists() || file.lastModified() != input.lastModified || file.length() != input.length) continue
        if (input.retireToBad) {
            if (moveToBad(root, file)) badMoved++
        } else {
            file.delete()
        }
    }
    return ConsolidationResult(byId.size, good.size, badMoved, unknownVersion, bundle)
}

/** True when [bundle] reads back as a bundle holding every id in [recordIds]. */
internal fun verifyBundleContains(bundle: File, recordIds: Set<String>): Boolean = runCatching {
    val parsed = MixOrderJson.gson.fromJson(bundle.readText(), MixOrderBundle::class.java)
    val present = parsed.records.filterNotNull().mapTo(HashSet()) { it.recordId }
    present.containsAll(recordIds)
}.getOrDefault(false)

private class Input(val file: File, val bundle: Boolean)

private fun listInputs(root: File): List<Input> {
    val singles = root.listFiles().orEmpty().filter { it.isFile && it.isJson() }.sortedBy { it.name }
    val bundles = File(root, BUNDLES_DIR).listFiles().orEmpty().filter { it.isFile && it.isJson() }.sortedBy { it.name }
    return singles.map { Input(it, bundle = false) } + bundles.map { Input(it, bundle = true) }
}

private sealed interface Parsed {
    /** Vanished or unreadable right now; leave it for a later run. */
    data object Skip : Parsed

    /** Written by a newer app; keep it exactly where it is. */
    data object Unknown : Parsed

    data object Bad : Parsed

    /** [retireToBad]: some records inside were unusable, so after merging the usable ones the file is kept in `bad/`. */
    class Good(
        val file: File,
        val records: List<MixOrderRecord>,
        val lastModified: Long,
        val length: Long,
        val retireToBad: Boolean
    ) : Parsed
}

private fun parseInput(file: File, bundle: Boolean): Parsed {
    val lastModified = file.lastModified()
    val length = file.length()
    val text = try {
        file.readText()
    } catch (ignored: Exception) {
        return Parsed.Skip
    }
    return try {
        val element = MixOrderJson.gson.fromJson(text, JsonElement::class.java)
        if (element == null || !element.isJsonObject) return Parsed.Bad
        val obj = element.asJsonObject
        when {
            versionOf(obj) > MixOrderJson.CURRENT_VERSION -> Parsed.Unknown
            bundle -> parseBundle(file, obj, lastModified, length)
            else -> {
                val record = MixOrderJson.gson.fromJson(obj, MixOrderRecord::class.java)
                if (record != null && record.isUsable()) {
                    Parsed.Good(file, listOf(record), lastModified, length, retireToBad = false)
                } else {
                    Parsed.Bad
                }
            }
        }
    } catch (ignored: Exception) {
        Parsed.Bad
    }
}

private fun parseBundle(file: File, obj: JsonObject, lastModified: Long, length: Long): Parsed {
    val array = obj.get("records")?.takeIf { it.isJsonArray }?.asJsonArray ?: return Parsed.Bad
    val records = ArrayList<MixOrderRecord>()
    var unusable = false
    for (element in array) {
        if (element.isJsonNull) continue
        if (!element.isJsonObject) {
            unusable = true
            continue
        }
        if (versionOf(element.asJsonObject) > MixOrderJson.CURRENT_VERSION) return Parsed.Unknown
        val record = runCatching { MixOrderJson.gson.fromJson(element, MixOrderRecord::class.java) }.getOrNull()
        if (record != null && record.isUsable()) records += record else unusable = true
    }
    if (unusable && records.isEmpty()) return Parsed.Bad
    return Parsed.Good(file, records, lastModified, length, retireToBad = unusable)
}

/** A missing `version` means 1, like the readers; a malformed one throws and the file counts as bad. */
private fun versionOf(obj: JsonObject): Int {
    val element = obj.get("version")
    return if (element == null || element.isJsonNull) 1 else element.asInt
}

/** Moves [file] into `bad/` under a name that never overwrites. Returns whether it moved. */
private fun moveToBad(root: File, file: File): Boolean = runCatching {
    val dir = File(root, BAD_DIR).apply { mkdirs() }
    val target = uniqueFile(dir, file.name)
    if (!file.renameTo(target)) {
        file.copyTo(target, overwrite = false)
        file.delete()
    }
    true
}.getOrDefault(false)
