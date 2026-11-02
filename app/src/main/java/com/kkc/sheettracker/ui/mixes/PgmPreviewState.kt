package com.kkc.sheettracker.ui.mixes

import androidx.compose.runtime.*
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.*

data class PgmPreviewKey(val machine: String, val job: String, val material: String, val pgm: String,
    val originalSha256: String, val intent: SecondPassState, val restoreOriginal: Boolean)

class PgmPreviewState {
    var key by mutableStateOf<PgmPreviewKey?>(null); private set
    var result by mutableStateOf<PgmPreviewFile?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var stale by mutableStateOf(false); private set
    var previews by mutableStateOf<Map<String, PgmPreviewFile>>(emptyMap()); private set

    fun start(value: PgmPreviewKey) {
        if (value != key) result = null
        key = value; loading = true; error = null; stale = false
    }
    fun complete(value: PgmPreviewKey, file: PgmPreviewFile): Boolean {
        if (key != value) return false
        result = file; loading = false; stale = false; error = null
        previews = previews + (pgmIdentity(value.pgm) to file)
        return true
    }
    fun fail(value: PgmPreviewKey, message: String): Boolean {
        if (key != value) return false
        loading = false; error = message; stale = result != null
        previews = previews - pgmIdentity(value.pgm)
        return true
    }
    fun invalidate(pgm: String? = null) {
        previews = if (pgm == null) emptyMap() else previews.filterKeys { !it.equals(pgm, true) }
        if (pgm == null || key?.pgm?.equals(pgm, true) == true) {
            key = null; result = null; loading = false; error = null; stale = false
        }
    }
    fun markStale(message: String) {
        stale = true; error = message; previews = emptyMap()
        key = null; loading = false // A late response cannot make this current again.
    }
}

class PgmRevisionState {
    var hashes by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private var changed by mutableStateOf<Set<String>>(emptySet())
    private var offline by mutableStateOf(true)
    private var loaded by mutableStateOf(false)
    private var reviewRequired by mutableStateOf(false)
    var message by mutableStateOf<String?>(null); private set
    val needsReview: Boolean get() = offline || changed.isNotEmpty() || reviewRequired

    fun observe(rows: List<PgmInventoryItem>, dirty: Boolean, explicit: Boolean): Boolean {
        if (rows.isEmpty()) { unavailable("Can't read CNC revisions. Refresh before applying."); return false }
        val fresh = rows.filter { validPgmHash(it.sha256) }.associate { it.name to it.sha256!! }
        val differences = (hashes.keys + fresh.keys).map(::pgmIdentity).filter { name -> hashes.pgmValue(name) != fresh.pgmValue(name) }.toSet()
        val replace = !loaded || explicit || !dirty
        if (replace) { hashes = fresh; changed = emptySet(); reviewRequired = false } else changed = differences
        loaded = true; offline = false
        message = when {
            reviewRequired -> message
            changed.isNotEmpty() -> "PGM files changed on the CNC. Refresh revisions and review your draft."
            fresh.size != rows.size -> "Some CNC revisions couldn't be read. Those files cannot be applied."
            else -> null
        }
        return differences.isNotEmpty()
    }
    fun unavailable(reason: String) { offline = true; message = reason }
    fun requireReview(reason: String) { reviewRequired = true; message = reason }
    fun canApply(draft: SecondPassDraft?): Boolean {
        if (!loaded || offline || reviewRequired || draft == null) return false
        return planSecondPass(draft).flatMap { when (it) {
            is SecondPassStep.Edit -> it.rows.map(PgmEditRow::name)
            is SecondPassStep.Revert -> it.pgms
        } }.all { validPgmHash(hashes.pgmValue(it)) && pgmIdentity(it) !in changed }
    }
}
