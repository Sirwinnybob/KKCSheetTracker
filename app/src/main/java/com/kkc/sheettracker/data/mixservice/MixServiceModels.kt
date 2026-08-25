package com.kkc.sheettracker.data.mixservice

data class PgmInventoryItem(
    val name: String = "",
    val size: Long = 0,
    val mtime: Long = 0
)

data class MixDefinition(
    val name: String = "",
    val job: String = "",
    val material: String = "",
    val programs: List<String> = emptyList(),
    val mixFilename: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastCompiledAt: String? = null,
    val lastCompileOk: Boolean? = null,
    val lastCompileError: String? = null,
    val status: String? = null
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

data class PgmEditFileResult(
    val name: String = "",
    val status: String = "",
    val mixFiles: List<String> = emptyList()
)

data class PgmEditBatchResponse(
    val ok: Boolean = false,
    val requestId: String = "",
    val backupDir: String? = null,
    val files: List<PgmEditFileResult> = emptyList()
)

data class PgmEditCurrentState(
    val mode: String? = null,
    val punloadRemoved: Boolean? = null,
    val mixFiles: List<String> = emptyList()
)

data class PgmEditFileHistory(
    val current: PgmEditCurrentState? = null
)

data class PgmEditHistoryView(
    val files: Map<String, PgmEditFileHistory> = emptyMap()
)
