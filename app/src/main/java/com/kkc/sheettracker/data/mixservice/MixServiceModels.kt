package com.kkc.sheettracker.data.mixservice

data class PgmInventoryItem(
    val name: String = "",
    val size: Long = 0,
    val mtime: Long = 0
)

data class PgmEditRow(
    val name: String,
    val secondPass: String,
    val removePUnload: Boolean
)

data class PgmEditBatchRequest(
    val requestId: String,
    val files: List<PgmEditRow>
)

data class PgmEditCurrentState(
    val mode: String? = null,
    val punloadRemoved: Boolean? = null,
    val mixFiles: List<String> = emptyList()
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
)

data class PgmEditHistoryView(
    val files: Map<String, PgmEditFileHistory> = emptyMap()
)
