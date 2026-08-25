package com.kkc.sheettracker.data.mixservice

data class PgmInventoryItem(
    val name: String = "",
    val size: Long = 0,
    val mtime: String = ""
) {
    // Gson has no accessible no-arg constructor to call for this class purely via reflection,
    // even though every parameter has a Kotlin default — without one, Gson falls back to Unsafe
    // field allocation and skips the declared defaults entirely, so a field absent from a real
    // response would deserialize to null/0 instead of its default. See SupplyModels.kt's
    // StoredSupplyItem for the same pattern.
    constructor() : this(name = "", size = 0, mtime = "")
}

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
) {
    // Same Gson no-arg-constructor gotcha as PgmInventoryItem above.
    constructor() : this(
        name = "",
        job = "",
        material = "",
        programs = emptyList(),
        mixFilename = "",
        createdAt = null,
        updatedAt = null,
        lastCompiledAt = null,
        lastCompileOk = null,
        lastCompileError = null,
        status = null
    )
}

data class PgmEditRow(
    val pgm: String,
    val secondPass: String,
    val removePUnload: Boolean
)

data class PgmEditBatchRequest(
    val requestId: String,
    val rows: List<PgmEditRow>
)

// The per-file identity key in the pgm-edits response is assumed to be `pgm`, mirroring the
// request row's own field name. The CLI API plan (Task 4) hasn't shipped yet; if the real
// response uses a different key, only this class needs updating.
data class PgmEditFileResult(
    val pgm: String = "",
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
    val removePUnload: Boolean? = null,
    val mixFiles: List<String> = emptyList()
)

data class PgmEditFileHistory(
    val current: PgmEditCurrentState? = null
)

data class PgmEditHistoryView(
    val files: Map<String, PgmEditFileHistory> = emptyMap()
)
