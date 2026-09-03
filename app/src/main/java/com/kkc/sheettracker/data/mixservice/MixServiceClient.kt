package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

// Result of the singular current-mix lookup (GET .../mix). Conflict means more than one mix
// definition already exists for this job+material -- the caller must not guess which one is
// "the" mix (that was the previous client-side bug: silently taking listMixes().firstOrNull()).
sealed class MixLookupResult {
    data class Found(val definition: MixDefinition) : MixLookupResult()
    object NotFound : MixLookupResult()
    data class Conflict(val names: List<String>) : MixLookupResult()
    object NetworkError : MixLookupResult()
}

class MixServiceClient(private val baseUrl: String = "http://192.168.20.4:8477") : MixOperationService, MixCatalogReader {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val root: String get() = baseUrl.trimEnd('/')

    private data class PgmListEnvelope(val ok: Boolean = false, val pgms: List<PgmInventoryItem> = emptyList())
    private data class MixListEnvelope(val ok: Boolean = false, val mixes: List<MixDefinition> = emptyList())
    private data class MixLookupEnvelope(val ok: Boolean = false, val mix: MixDefinition? = null, val status: String? = null)
    private data class MixConflictEnvelope(val ok: Boolean = false, val code: String? = null, val names: List<String> = emptyList())
    private data class PgmConflict(val pgm: String = "", val mixName: String = "")
    private data class PgmConflictsEnvelope(val ok: Boolean = false, val conflicts: List<PgmConflict> = emptyList())
    private data class OperationEnvelope(val ok: Boolean = false, val operation: MixServiceOperation? = null)
    private data class OperationsEnvelope(val ok: Boolean = false, val operations: List<MixServiceOperation> = emptyList())
    private data class CatalogEnvelope(
        val ok: Boolean = false,
        val revision: Long? = null,
        val entries: List<MixCatalogEntry> = emptyList(),
    )
    private data class CatalogMutationEnvelope(
        val ok: Boolean = false,
        val catalog: CatalogEnvelope? = null,
    )
    private data class CatalogSyncFailureEnvelope(
        val ok: Boolean = false,
        val code: String? = null,
        val error: String? = null,
        val mix: CatalogMutationEnvelope? = null,
        val recoveryUrl: String? = null,
        val recoveries: List<MixOperationRecovery> = emptyList(),
    )
    private data class CatalogErrorEnvelope(
        val ok: Boolean = false,
        val code: String? = null,
        val error: String? = null,
    )

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$root/status".toHttpUrl()).get().build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun listPgms(job: String, material: String): List<PgmInventoryItem> = withContext(Dispatchers.IO) {
        val url = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgms")
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                gson.fromJson(body, PgmListEnvelope::class.java)?.pgms.orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun listMixes(job: String, material: String? = null): List<MixDefinition>? = withContext(Dispatchers.IO) {
        val urlBuilder = "$root/mixes".toHttpUrl().newBuilder()
            .addQueryParameter("job", job)
        if (material != null) urlBuilder.addQueryParameter("material", material)
        val url = urlBuilder.build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                gson.fromJson(body, MixListEnvelope::class.java)?.mixes
            }
        }.getOrNull()
    }

    // Singular current-mix lookup. Distinct from listMixes(job, material), which returns every
    // matching definition -- this reports 409 Conflict when more than one exists instead of
    // leaving the caller to guess which is "the" mix.
    suspend fun getMix(job: String, material: String): MixLookupResult = withContext(Dispatchers.IO) {
        val url = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("mix")
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: return@use MixLookupResult.NetworkError
                        val envelope = gson.fromJson(body, MixLookupEnvelope::class.java)
                        val definition = envelope?.mix ?: return@use MixLookupResult.NetworkError
                        MixLookupResult.Found(definition.copy(status = envelope.status ?: definition.status))
                    }
                    404 -> MixLookupResult.NotFound
                    409 -> {
                        val body = response.body?.string() ?: return@use MixLookupResult.NetworkError
                        val envelope = gson.fromJson(body, MixConflictEnvelope::class.java)
                        MixLookupResult.Conflict(envelope?.names.orEmpty())
                    }
                    else -> MixLookupResult.NetworkError
                }
            }
        }.getOrDefault(MixLookupResult.NetworkError)
    }

    // Job-scoped by default: a .pgm filename is unique across the whole job, not per-material,
    // so a mix belonging to a different material in the same job still counts as a real
    // conflict. Returns null (not empty) on any failure so callers can fall back rather than
    // silently reporting "no conflicts" when the check itself didn't run.
    suspend fun getPgmConflicts(
        job: String,
        material: String,
        programs: List<String>,
        exclude: String? = null,
        scope: String = "job"
    ): List<DuplicateMixWarning>? = withContext(Dispatchers.IO) {
        if (programs.isEmpty()) return@withContext emptyList()
        val urlBuilder = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("mixes")
            .addPathSegment("pgm-conflicts")
            .addQueryParameter("programs", programs.joinToString(","))
            .addQueryParameter("scope", scope)
        if (exclude != null) urlBuilder.addQueryParameter("exclude", exclude)
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                gson.fromJson(body, PgmConflictsEnvelope::class.java)?.conflicts?.map {
                    DuplicateMixWarning(it.pgm, it.mixName)
                }
            }
        }.getOrNull()
    }

    suspend fun submitMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
    ): MixServiceOperation = submitMix(job, material, name, programs, replaceExisting = false)

    override suspend fun submitMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        replaceExisting: Boolean,
    ): MixServiceOperation {
        val payload = mutableMapOf<String, Any?>(
            "job" to job,
            "material" to material,
            "programs" to programs,
        )
        if (!replaceExisting) {
            payload["name"] = name
            payload["overwrite"] = false
        }
        return submitOperation(
            url = if (replaceExisting) {
                "$root/mixes/".toHttpUrl().newBuilder().addPathSegment(name).build()
            } else {
                "$root/mixes".toHttpUrl()
            },
            method = if (replaceExisting) "PUT" else "POST",
            payload = payload,
        )
    }

    override suspend fun submitCatalogMutation(
        action: ManageCodeOperationAction,
    ): MixCatalogMutationResult {
        if (action.job.isBlank()) {
            throw MixOperationClientException("catalog mutation is missing its job")
        }
        return when (action.kind) {
            ManageCodeOperationAction.CATALOG_CREATE -> createCatalogMix(
                job = action.job,
                material = action.material,
                name = action.name,
                programs = action.programs,
                expectedRevision = action.expectedRevision,
            )
            ManageCodeOperationAction.CATALOG_REPLACE -> replaceMix(
                job = action.job,
                material = action.material,
                name = action.name,
                programs = action.programs,
                expectedRevision = action.expectedRevision,
            )
            ManageCodeOperationAction.EXTERNAL_DELETE -> deleteExternalMix(
                job = action.job,
                material = action.material,
                filename = action.externalMixFilename,
                expectedRevision = action.expectedRevision,
            )
            else -> throw MixOperationClientException("unknown catalog action: ${action.kind}")
        }
    }

    suspend fun createCatalogMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        expectedRevision: Long,
    ): MixCatalogMutationResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("mixes")
            .build()
        val body = gson.toJson(
            mapOf(
                "name" to name,
                "programs" to programs,
                "expectedRevision" to expectedRevision,
            )
        ).toRequestBody(jsonMediaType)
        executeCatalogMutation(
            Request.Builder().url(url).post(body).build(),
            job,
            material,
            duplicateName = name,
        )
    }

    suspend fun replaceMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        expectedRevision: Long,
    ): MixCatalogMutationResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("mixes")
            .addPathSegment(name)
            .addPathSegment("replace")
            .build()
        val body = gson.toJson(
            mapOf("programs" to programs, "expectedRevision" to expectedRevision)
        ).toRequestBody(jsonMediaType)
        executeCatalogMutation(
            Request.Builder().url(url).post(body).build(),
            job,
            material,
        )
    }

    suspend fun deleteExternalMix(
        job: String,
        material: String,
        filename: String,
        expectedRevision: Long,
    ): MixCatalogMutationResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("external-mixes")
            .addPathSegment(filename)
            .addQueryParameter("expectedRevision", expectedRevision.toString())
            .build()
        executeCatalogMutation(
            Request.Builder().url(url).delete().build(),
            job,
            material,
        )
    }

    suspend fun listPgmEdits(job: String, material: String, historyLimit: Int = 20): PgmEditHistoryView? =
        withContext(Dispatchers.IO) {
            val url = "$root/jobs/".toHttpUrl().newBuilder()
                .addPathSegment(job)
                .addPathSegment("materials")
                .addPathSegment(material)
                .addPathSegment("pgm-edits")
                .addQueryParameter("historyLimit", historyLimit.toString())
                .build()
            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    gson.fromJson(body, PgmEditHistoryView::class.java)
                }
            }.getOrNull()
        }

    override suspend fun submitPgmEdits(
        job: String,
        material: String,
        requestId: String,
        files: List<PgmEditRow>
    ): MixServiceOperation = submitOperation(
        url = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgm-edits")
            .build(),
        method = "POST",
        payload = PgmEditBatchRequest(requestId, files),
    )

    override suspend fun getMixCatalog(job: String, material: String): MixCatalogFetchResult =
        withContext(Dispatchers.IO) {
            val url = materialUrl(job, material).newBuilder()
                .addPathSegment("mix-catalog")
                .build()
            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) return@use MixCatalogFetchResult.NetworkError
                    val snapshot = MixCatalogJson.parseFetchSnapshot(
                        response.body?.string().orEmpty(),
                        job,
                        material,
                    )
                        ?: return@use MixCatalogFetchResult.NetworkError
                    MixCatalogFetchResult.Success(snapshot)
                }
            }.getOrDefault(MixCatalogFetchResult.NetworkError)
        }

    override suspend fun getOperation(id: String): MixServiceOperation = withContext(Dispatchers.IO) {
        val url = "$root/operations/".toHttpUrl().newBuilder().addPathSegment(id).build()
        readOperation(Request.Builder().url(url).get().build())
    }

    override suspend fun listJobOperations(job: String): List<MixServiceOperation> = withContext(Dispatchers.IO) {
        val url = "$root/jobs/".toHttpUrl().newBuilder().addPathSegment(job).addPathSegment("operations").build()
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val envelope = gson.fromJson(response.body?.string().orEmpty(), OperationsEnvelope::class.java)
                if (response.code != 200 || envelope?.ok != true) throw MixOperationClientException("operation list failed")
                envelope.operations
            }
        }.getOrElse { throw MixOperationClientException(it.message ?: "operation list failed") }
    }

    private suspend fun submitOperation(
        url: okhttp3.HttpUrl,
        method: String,
        payload: Any,
    ): MixServiceOperation = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .method(method, gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()
        runCatching { client.newCall(request).execute().use(::parseAcceptedOperation) }
            .getOrElse { throw MixOperationClientException(it.message ?: "operation submission failed") }
    }

    private fun executeCatalogMutation(
        request: Request,
        job: String,
        material: String,
        duplicateName: String? = null,
    ): MixCatalogMutationResult = runCatching {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 200) {
                return@use MixCatalogJson.parseMutationSnapshot(body, job, material)
                    ?.let(MixCatalogMutationResult::Success)
                    ?: MixCatalogMutationResult.NetworkError
            }
            parseCatalogMutationFailure(response.code, body, job, material, duplicateName)
        }
    }.getOrDefault(MixCatalogMutationResult.NetworkError)

    private fun parseCatalogMutationFailure(
        statusCode: Int,
        body: String,
        job: String,
        material: String,
        duplicateName: String?,
    ): MixCatalogMutationResult {
        val bodyObject = runCatching {
            JsonParser.parseString(body).takeIf(JsonElement::isJsonObject)?.asJsonObject
        }.getOrNull()
        val syncSnapshot = bodyObject?.get("mix")?.let { mix ->
            MixCatalogJson.parseMutationSnapshot(mix, job, material)
        }
        val syncFailure = runCatching {
            gson.fromJson(body, CatalogSyncFailureEnvelope::class.java)
        }.getOrNull()?.let { envelope ->
            syncSnapshot?.let { snapshot ->
                MixCatalogMutationResult.SyncFailed(
                    snapshot = snapshot,
                    code = envelope.code ?: "history_sync_failed",
                    recoveryUrl = envelope.recoveryUrl,
                    recoveries = envelope.recoveries.ifEmpty {
                        envelope.recoveryUrl?.let { listOf(MixOperationRecovery(url = it)) }
                            ?: emptyList()
                    },
                )
            }
        }
        if (syncFailure != null) return syncFailure

        val error = runCatching {
            gson.fromJson(body, CatalogErrorEnvelope::class.java)
        }.getOrNull()
        val code = error?.code.orEmpty()
        val message = error?.error.orEmpty()
        return when (code) {
            "catalog_changed" -> MixCatalogMutationResult.CatalogChanged
            "external_mixes_present" -> MixCatalogMutationResult.ExternalMixesPresent
            "edit_busy" -> MixCatalogMutationResult.EditBusy
            "compile_busy" -> MixCatalogMutationResult.CompileBusy
            "winxiso_timeout" -> MixCatalogMutationResult.WinxisoTimeout
            "duplicate_mix" -> MixCatalogMutationResult.DuplicateName(duplicateName ?: message)
            "missing_program" -> MixCatalogMutationResult.MissingProgram(
                message.removePrefix("missing program:").trim()
            )
            "history_sync_failed" -> MixCatalogMutationResult.HistorySyncError(message)
            else -> when (statusCode) {
                400, 404, 422 -> MixCatalogMutationResult.BadRequest(message)
                else -> MixCatalogMutationResult.NetworkError
            }
        }
    }

    private fun materialUrl(job: String, material: String) = "$root/jobs/".toHttpUrl().newBuilder()
        .addPathSegment(job)
        .addPathSegment("materials")
        .addPathSegment(material)
        .build()

    private fun readOperation(request: Request): MixServiceOperation = runCatching {
        client.newCall(request).execute().use { response ->
            val envelope = gson.fromJson(response.body?.string().orEmpty(), OperationEnvelope::class.java)
            if (response.code != 200 || envelope?.ok != true || envelope.operation == null) {
                throw MixOperationClientException("operation status failed")
            }
            envelope.operation
        }
    }.getOrElse { throw MixOperationClientException(it.message ?: "operation status failed") }

    private fun parseAcceptedOperation(response: Response): MixServiceOperation {
        val envelope = gson.fromJson(response.body?.string().orEmpty(), OperationEnvelope::class.java)
        if (
            response.code != 202 ||
            envelope?.ok != true ||
            envelope.operation == null ||
            envelope.operation.id.isBlank()
        ) {
            throw MixOperationClientException("operation submission failed")
        }
        return envelope.operation
    }

}
