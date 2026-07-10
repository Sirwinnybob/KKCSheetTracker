package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic per-tablet counter used to tie-break tracker events sharing the same wall-clock
 * timestamp. Not a merged cross-node Lamport clock -- the watcher's sort key primaries on
 * timestamp (tracker_action_stream.py:_sort_combined_actions); this only disambiguates
 * same-instant events from this one tablet. See METADATA_AUDIT.md R-01.
 *
 * Lazily initialized: `next()` works in-memory-only until `init()` is called once (see
 * MainActivity), so unit tests that construct ProgressStore/HardwoodsProgressStore directly
 * don't need any setup.
 */
object TrackerLamportClock {
    private val lock = Any()
    private val counter = AtomicLong(0L)
    private var backingFile: File? = null
    private var loaded = false

    fun init(stateDir: File) {
        synchronized(lock) {
            backingFile = File(stateDir, "tracker_lamport.txt")
            loaded = false
        }
    }

    fun next(): Long {
        synchronized(lock) {
            ensureLoadedLocked()
            val value = counter.incrementAndGet()
            persistLocked(value)
            return value
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = backingFile ?: return
        val persisted = runCatching { file.readText().trim().toLong() }.getOrNull()
        if (persisted != null && persisted > counter.get()) {
            counter.set(persisted)
        }
    }

    private fun persistLocked(value: Long) {
        val file = backingFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(value.toString())
        }
    }

    /** Test-only: restore the singleton to its pristine, un-initialized state so one test class
     * can't leak counter/backingFile/loaded state into another. See METADATA_AUDIT.md R-01. */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            counter.set(0L)
            backingFile = null
            loaded = false
        }
    }
}

/** One ndjson tracker event line, matching the schema Ready Jobs Watcher's
 * tracker_action_stream.py expects (see METADATA_AUDIT.md R-01 design doc). */
data class TrackerEvent(
    val op: String,
    val payload: JsonObject,
    val wallTime: String,
    val lamport: Long,
    val eventId: String = UUID.randomUUID().toString()
)

fun encodeTrackerEventLine(event: TrackerEvent): String {
    val json = JsonObject()
    json.addProperty("op", event.op)
    json.add("payload", event.payload)
    json.addProperty("wallTime", event.wallTime)
    json.addProperty("lamport", event.lamport)
    json.addProperty("eventId", event.eventId)
    return json.toString()
}

/** True append -- opens, writes one newline-terminated line, closes. Never rewrites prior lines. */
fun appendTrackerEvent(file: File, event: TrackerEvent) {
    file.parentFile?.mkdirs()
    FileOutputStream(file, true).use { stream ->
        stream.write((encodeTrackerEventLine(event) + "\n").toByteArray(Charsets.UTF_8))
        runCatching { stream.fd.sync() }
    }
}

/** Tolerant per-line parse: skips (does not throw on) any line that fails to parse, so a torn
 * last line read mid-append by a peer tablet doesn't drop the whole file. */
fun readTrackerEvents(file: File): List<JsonObject> {
    if (!file.exists()) return emptyList()
    return file.readLines(Charsets.UTF_8).mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
    }
}
