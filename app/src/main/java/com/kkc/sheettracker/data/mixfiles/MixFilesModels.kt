package com.kkc.sheettracker.data.mixfiles

enum class MixFileKind { MIX, FRZ, REVISION }

data class MixFileEntry(
    val path: String,
    val pgm: String,
    val parameters: LinkedHashMap<String, String>,
    val fields: String?,
    val quantity: Int,
    val remaining: Int?,
    val pgmExists: Boolean,
    val headerDrift: Boolean,
)

data class MixFileRow(
    val file: String,
    val kind: MixFileKind,
    val revisionOf: String?,
    val revisionNumber: Int?,
    val revision: String,
    val companion: String?,
    val companionMismatch: Boolean,
    val error: String?,
    val modifiedAt: String?,
    val entries: List<MixFileEntry>,
)

data class MixFilesCatalog(val job: String, val material: String, val folderRevision: String, val files: List<MixFileRow>)
data class FrzMismatch(val pgm: String, val index: Int, val tabletLocked: Boolean, val frzRemaining: Int)
data class MixFilesError(
    val status: Int,
    val code: String,
    val message: String,
    val current: MixFileRow?,
    val mismatches: List<FrzMismatch>,
    val edit: String?,
    val pgm: String?,
    val reason: String?,
    val frzRestored: Boolean?,
)

sealed interface MixFilesResult<out T> {
    data class Ok<T>(val value: T) : MixFilesResult<T>
    data class Rejected(val error: MixFilesError) : MixFilesResult<Nothing>
    data object ServiceOutdated : MixFilesResult<Nothing>
    data object Offline : MixFilesResult<Nothing>
}

sealed interface EntrySpec {
    data class NewPgm(val pgm: String) : EntrySpec
    data class Existing(val path: String, val parameters: Map<String, String>, val fields: String?) : EntrySpec
    data class Pending(val pgm: String, val quantity: Int?, val remaining: Int?) : EntrySpec
}

data class Completion(val completedPgms: List<String>, val resolutions: Map<String, String>, val companionExpectedRevision: String?)
data class NewMix(val file: String, val entries: List<EntrySpec>)

interface MixFilesApi {
    suspend fun catalog(job: String, material: String): MixFilesResult<MixFilesCatalog>
    suspend fun folderRevision(job: String, material: String): MixFilesResult<String>
    suspend fun write(job: String, material: String, file: String, expectedRevision: String?, entries: List<EntrySpec>, completion: Completion?): MixFilesResult<MixFilesCatalog>
    suspend fun frz(job: String, material: String, file: String, expectedRevision: String, completion: Completion, pending: List<EntrySpec>): MixFilesResult<MixFilesCatalog>
    suspend fun rename(job: String, material: String, file: String, newFile: String, expectedRevision: String): MixFilesResult<MixFilesCatalog>
    suspend fun delete(job: String, material: String, file: String, expectedRevision: String): MixFilesResult<MixFilesCatalog>
    suspend fun deleteRevision(job: String, material: String, file: String, expectedRevision: String): MixFilesResult<MixFilesCatalog>
    suspend fun restore(job: String, material: String, revisionFile: String, targetFile: String, expectedTargetRevision: String?, completion: Completion? = null): MixFilesResult<MixFilesCatalog>
    suspend fun split(job: String, material: String, file: String, expectedRevision: String, entries: List<EntrySpec>, newMixes: List<NewMix>, completion: Completion?): MixFilesResult<MixFilesCatalog>
    suspend fun features(): Set<String>
}
