package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.mixservice.*

data class SecondPassRevisionSnapshot(val hashes: Map<String, String>, val previews: Map<String, PgmPreviewFile> = emptyMap())
internal class RevisionRefused(message: String) : IllegalStateException(message)

fun previewRequest(draft: SecondPassDraft, pgm: String, hash: String, requestId: String = "preview"): PgmPreviewRequest {
    check(validPgmHash(hash)) { "Refresh CNC revisions before previewing" }
    val name = draft.wanted.keys.firstOrNull { it.equals(pgm, true) } ?: error("PGM is not in this draft")
    val wanted = draft.wanted.getValue(name)
    val plan = planSecondPass(SecondPassDraft(mapOf(name to wanted), draft.current.filterKeys { it.equals(name, true) }))
    val restore = plan.any { it is SecondPassStep.Revert }
    return PgmPreviewRequest(requestId, listOf(PgmPreviewRow(name, when {
        wanted.superPass -> "super"
        wanted.secondPass -> "standard"
        else -> "none"
    }, wanted.removePUnload, hash, restore)))
}

class SecondPassRevisionSupport(
    val snapshot: SecondPassRevisionSnapshot,
    val refresh: suspend () -> Map<String, String>,
    val revert: suspend (String, String, String, List<PgmRevertRow>) -> MixServiceOperation,
) {
    private fun hash(values: Map<String, String>, name: String): String =
        values.pgmValue(name)?.takeIf(::validPgmHash) ?: throw RevisionRefused("Refresh CNC revisions before editing $name")

    fun preflight(plan: List<SecondPassStep>) {
        plan.flatMap { when (it) { is SecondPassStep.Edit -> it.rows.map(PgmEditRow::name); is SecondPassStep.Revert -> it.pgms } }
            .forEach { name ->
                val captured = hash(snapshot.hashes, name)
                snapshot.previews.pgmValue(name)?.let { preview ->
                    if (preview.originalSha256 != captured) throw RevisionRefused("$name changed since preview; refresh and review")
                }
            }
    }

    fun editRows(rows: List<PgmEditRow>, activeHashes: Map<String, String>): List<PgmEditRow> = rows.map { row ->
        val preview = snapshot.previews.pgmValue(row.name)?.takeIf {
            it.requested.mode == row.secondPass && it.requested.removePUnload == row.removePUnload
        }
        row.copy(expectedSha256 = hash(activeHashes, row.name), expectedSourceSha256 = preview?.proposedSourceSha256)
    }

    fun revertRows(names: List<String>, activeHashes: Map<String, String>): List<PgmRevertRow> =
        names.map { PgmRevertRow(it, hash(activeHashes, it)) }

    suspend fun afterRestore(names: List<String>, activeHashes: Map<String, String>): Map<String, String> {
        val fresh = refresh()
        val merged = activeHashes.toMutableMap()
        for (name in names) {
            val restored = hash(fresh, name)
            val preview = snapshot.previews.pgmValue(name)
            if (preview != null && restored != preview.baseSha256) {
                throw RevisionRefused("$name differs from the previewed original; review CNC history before applying again")
            }
            merged[merged.keys.firstOrNull { it.equals(name, true) } ?: name] = restored
        }
        return merged
    }
}
