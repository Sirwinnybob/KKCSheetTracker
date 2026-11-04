package com.kkc.sheettracker.data.mixorder

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Under the Ready Jobs base folder. Holds single record files, `bundles/` and `bad/`. */
const val LEARNING_DIR = ".metadata/mix_order_learning"

internal const val BUNDLES_DIR = "bundles"

private val timestampFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US)
}

/** File-name timestamp shared by record and bundle files, so names sort chronologically. */
internal fun mixOrderTimestamp(timestampMs: Long): String = timestampFormat.get()!!.format(Date(timestampMs))

internal fun sanitizeFilePart(value: String?): String {
    val sanitized = value
        .orEmpty()
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.', '_')
    return sanitized.ifBlank { "unknown-tablet" }
}

internal fun File.isJson(): Boolean = name.endsWith(".json", ignoreCase = true)

/** [fileName] in [dir], or the first free `-1`, `-2`, ... variant, so nothing is ever overwritten. */
internal fun uniqueFile(dir: File, fileName: String): File {
    val base = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', "")
    var candidate = File(dir, fileName)
    var suffix = 1
    while (candidate.exists()) {
        val suffixedName = if (extension.isBlank()) "$base-$suffix" else "$base-$suffix.$extension"
        candidate = File(dir, suffixedName)
        suffix++
    }
    return candidate
}

/**
 * Writes [text] to a unique file in [dir] via a `.tmp` sibling and a rename, so a reader never sees a
 * half-written file. A failed write removes its temp file. [write] is a test seam.
 */
internal fun writeFileAtomically(
    dir: File,
    fileName: String,
    text: String,
    write: (File, String) -> Unit = { file, content -> file.writeText(content) }
): File {
    dir.mkdirs()
    val target = uniqueFile(dir, fileName)
    val tmp = File(dir, "${target.name}.tmp")
    try {
        write(tmp, text)
        if (!tmp.renameTo(target)) {
            try {
                tmp.copyTo(target, overwrite = true)
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
            tmp.delete()
        }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
    return target
}

/**
 * Shared, Syncthing-friendly store for mix-order learning records. Every record is its own file so
 * tablets never write the same file; files are written temp-then-rename so a reader never sees a
 * half-written record. When the shared folder is unreachable the record is parked in [pendingDir]
 * and moved over on the next successful [record].
 *
 * Logging must never fail or delay Apply: [record] swallows every error.
 */
class MixOrderLearningStore(
    private val pendingDir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gson = MixOrderJson.gson
    private val lock = Any()

    /** Writes to the shared folder (flushing anything pending first), else to [pendingDir]. Never throws. */
    fun record(baseDir: File?, record: MixOrderRecord) {
        runCatching {
            synchronized(lock) {
                val fileName = recordFileName(clock(), record.tabletId)
                val json = gson.toJson(record)
                // An unmounted share is a missing directory; mkdirs would silently recreate it on local storage.
                if (baseDir != null && baseDir.isDirectory) {
                    val sharedDir = File(baseDir, LEARNING_DIR)
                    val wrote = runCatching {
                        runCatching { flushPending(baseDir) }
                        writeFile(sharedDir, fileName, json)
                        true
                    }.getOrDefault(false)
                    if (wrote) return@runCatching
                }
                writeFile(pendingDir, fileName, json)
            }
        }
    }

    /** Moves pending record files into the shared folder. Returns how many arrived; failures stay pending. */
    fun flushPending(baseDir: File): Int {
        if (!baseDir.isDirectory) return 0
        val files = pendingDir.listFiles().orEmpty()
            .filter { it.isFile && it.isJson() }
            .sortedBy { it.name }
        if (files.isEmpty()) return 0

        val sharedDir = File(baseDir, LEARNING_DIR)
        var moved = 0
        for (file in files) {
            val arrived = runCatching {
                writeFile(sharedDir, file.name, file.readText())
                file.delete()
                true
            }.getOrDefault(false)
            if (arrived) moved++
        }
        return moved
    }

    /**
     * Every usable record: single files in the learning folder plus every JSON file in `bundles`, deduped by
     * `recordId`. Unparseable files, non-`.json` files (`.tmp`, `.txt`), directories and records or
     * bundles with a newer `version` than this app understands are skipped.
     */
    fun readAll(baseDir: File): List<MixOrderRecord> {
        val root = File(baseDir, LEARNING_DIR)
        val singles = root.listFiles().orEmpty().filter { it.isFile && it.isJson() }.sortedBy { it.name }
        val bundles = File(root, BUNDLES_DIR).listFiles().orEmpty().filter { it.isFile && it.isJson() }.sortedBy { it.name }

        val seen = HashSet<String>()
        val result = ArrayList<MixOrderRecord>()
        for (file in singles) parseCached(file, bundle = false).forEach { if (seen.add(it.recordId)) result += it }
        for (file in bundles) parseCached(file, bundle = true).forEach { if (seen.add(it.recordId)) result += it }
        return result
    }

    private fun parseCached(file: File, bundle: Boolean): List<MixOrderRecord> {
        val path = file.absolutePath
        val modified = file.lastModified()
        val length = file.length()
        parseCache[path]?.let { if (it.lastModified == modified && it.length == length) return it.records }

        val records = runCatching { parseFile(file, bundle) }.getOrDefault(emptyList())
        if (parseCache.size >= PARSE_CACHE_LIMIT) parseCache.clear()
        parseCache[path] = CachedParse(modified, length, records)
        return records
    }

    private fun parseFile(file: File, bundle: Boolean): List<MixOrderRecord> {
        val text = file.readText()
        val records = if (bundle) {
            val parsed = gson.fromJson(text, MixOrderBundle::class.java) ?: return emptyList()
            if (parsed.version > MixOrderJson.CURRENT_VERSION) return emptyList()
            @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
            (parsed.records ?: emptyList())
        } else {
            listOfNotNull(gson.fromJson(text, MixOrderRecord::class.java))
        }
        // Gson can leave null elements in a hand-edited list.
        return (records as List<MixOrderRecord?>).filterNotNull()
            .filter { it.isUsable() && it.version <= MixOrderJson.CURRENT_VERSION }
    }

    private fun writeFile(dir: File, fileName: String, json: String) {
        writeFileAtomically(dir, fileName, json)
    }

    private fun recordFileName(timestampMs: Long, tabletId: String?): String =
        "${mixOrderTimestamp(timestampMs)}_${sanitizeFilePart(tabletId)}_mixorder.json"

    private class CachedParse(val lastModified: Long, val length: Long, val records: List<MixOrderRecord>)

    companion object {
        private const val PARSE_CACHE_LIMIT = 4000

        /** Keyed by absolute path; an entry is reused only while lastModified and length are unchanged. */
        private val parseCache = ConcurrentHashMap<String, CachedParse>()
    }
}
