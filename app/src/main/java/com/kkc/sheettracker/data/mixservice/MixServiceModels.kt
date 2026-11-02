package com.kkc.sheettracker.data.mixservice

data class PgmInventoryItem(
    val name: String = "",
    val size: Long = 0,
    val mtime: Long = 0,
    val sha256: String? = null,
    val revisionError: String? = null,
)

data class PgmEditRow(
    val name: String,
    val secondPass: String,
    val removePUnload: Boolean,
    val expectedSha256: String? = null,
    val expectedSourceSha256: String? = null,
)

data class PgmEditBatchRequest(
    val requestId: String,
    val files: List<PgmEditRow>
)

data class PgmEditCurrentState(
    val mode: String? = null,
    val punloadRemoved: Boolean? = null,
    val mixFiles: List<String> = emptyList(),
    val stateSource: String? = null,
)

/** Canonical ledger events, decoded directly by MixServiceClient.listPgmEdits. */
data class PgmEditHistoryEvent(
    val eventId: String = "",
    val requestId: String? = null,
    val at: String? = null,
    val actor: String? = null,
    val action: String = "",
    val requestedMode: String? = null,
    val punloadRequested: Boolean? = null,
    val before: PgmEditCurrentState? = null,
    val final: PgmEditCurrentState? = null,
    val result: PgmEditCurrentState? = null,
    val backup: String? = null,
    val restoredFrom: String? = null,
    val mixFiles: List<String> = emptyList(),
    val error: String? = null,
)

data class PgmEditFileHistory(
    val current: PgmEditCurrentState? = null,
    val history: List<PgmEditHistoryEvent> = emptyList(),
    val stateSource: String? = null,
) {
    val isKnown: Boolean get() = stateSource == "ledger" && current?.mode in setOf("none", "standard", "super") &&
        current?.punloadRemoved != null && current.stateSource !in setOf("unknown", "failed_restore_error")
}

data class PgmEditHistoryView(
    val files: Map<String, PgmEditFileHistory> = emptyMap(),
    val ok: Boolean = false,
    val stateSource: String? = null,
    val error: String? = null,
) {
    val isLedger: Boolean get() = ok && stateSource == "ledger" && error == null
    val isMissing: Boolean get() = !ok && stateSource == "unknown" && error == "history_missing" && files.isEmpty()
    // An uncertain known file blocks the material; never project unknown as clean.
    val canEdit: Boolean get() = isMissing || (isLedger && files.values.all { it.isKnown })
    fun file(pgm: String): PgmEditFileHistory? = files.entries.firstOrNull { it.key.equals(pgm, true) }?.value
    fun records(pgms: Collection<String>): Boolean = isLedger && canEdit && pgms.all { file(it)?.isKnown == true }
}
