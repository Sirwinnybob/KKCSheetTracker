package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

sealed class MixWriteResult {
    data class Success(val definition: MixDefinition) : MixWriteResult()
    // The mix mutation itself succeeded (defn is real and already in the service's store) but
    // writing that outcome into the material's .pgm_edit_history.json sidecar failed. Not a
    // failed write -- callers must not retry the mutation on this result, since retrying
    // create/update against an already-created mix name produces a real DuplicateName.
    data class SyncFailed(val definition: MixDefinition, val code: String, val recoveryUrl: String?) : MixWriteResult()
    data class DuplicateName(val name: String) : MixWriteResult()
    object UnknownJobOrMaterial : MixWriteResult()
    data class MissingProgram(val pgm: String) : MixWriteResult()
    data class BadRequest(val message: String) : MixWriteResult()
    object CompileBusy : MixWriteResult()
    object WinxisoTimeout : MixWriteResult()
    object NetworkError : MixWriteResult()
}

// Result of the singular current-mix lookup (GET .../mix). Conflict means more than one mix
// definition already exists for this job+material -- the caller must not guess which one is
// "the" mix (that was the previous client-side bug: silently taking listMixes().firstOrNull()).
sealed class MixLookupResult {
    data class Found(val definition: MixDefinition) : MixLookupResult()
    object NotFound : MixLookupResult()
    data class Conflict(val names: List<String>) : MixLookupResult()
    object NetworkError : MixLookupResult()
}

sealed class PgmEditSubmitResult {
    data class Success(val response: PgmEditBatchResponse) : PgmEditSubmitResult()
    object Disabled : PgmEditSubmitResult()
    object EditBusy : PgmEditSubmitResult()
    object CompileBusy : PgmEditSubmitResult()
    object WinxisoTimeout : PgmEditSubmitResult()
    object NetworkError : PgmEditSubmitResult()
}

class MixServiceClient(private val baseUrl: String = "http://192.168.20.4:8477") {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val root: String get() = baseUrl.trimEnd('/')

    private data class PgmListEnvelope(val ok: Boolean = false, val pgms: List<PgmInventoryItem> = emptyList())
    private data class MixListEnvelope(val ok: Boolean = false, val mixes: List<MixDefinition> = emptyList())
    private data class MixWriteEnvelope(val ok: Boolean = false, val mix: MixDefinition? = null, val status: String? = null)
    private data class ErrorEnvelope(val ok: Boolean = false, val code: String? = null, val error: String? = null)
    private data class MixLookupEnvelope(val ok: Boolean = false, val mix: MixDefinition? = null, val status: String? = null)
    private data class MixConflictEnvelope(val ok: Boolean = false, val code: String? = null, val names: List<String> = emptyList())
    private data class MixCatalogEnvelope(
        val ok: Boolean = false,
        val revision: Long? = null,
        val entries: List<MixCatalogEntry> = emptyList()
    )
    private data class MixCatalogMutationEnvelope(
        val ok: Boolean = false,
        val catalog: MixCatalogEnvelope? = null
    )
    private data class MixCatalogSyncErrorEnvelope(
        val ok: Boolean = false,
        val code: String? = null,
        val error: String? = null,
        val mix: MixCatalogMutationEnvelope? = null,
        val recoveryUrl: String? = null
    )
    // A history-sync-failure error body's own "mix" field is the *whole* completed-mutation
    // envelope (i.e. another {ok, mix, status}), not a bare MixDefinition -- the server nests it
    // because it's literally the same result object the 200 success path would have returned.
    private data class MixSyncErrorEnvelope(
        val ok: Boolean = false,
        val code: String? = null,
        val error: String? = null,
        val mix: MixWriteEnvelope? = null,
        val recoveryUrl: String? = null
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

    suspend fun createMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        overwrite: Boolean = false
    ): MixWriteResult = writeMix("POST", job, material, name, programs, overwrite)

    suspend fun updateMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>
    ): MixWriteResult = writeMix("PUT", job, material, name, programs, overwrite = false)

    private suspend fun writeMix(
        method: String,
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        overwrite: Boolean
    ): MixWriteResult = withContext(Dispatchers.IO) {
        val url = if (method == "POST") {
            "$root/mixes".toHttpUrl()
        } else {
            "$root/mixes/".toHttpUrl().newBuilder().addPathSegment(name).build()
        }
        val payload = mutableMapOf<String, Any?>(
            "job" to job,
            "material" to material,
            "programs" to programs
        )
        if (method == "POST") {
            payload["name"] = name
            payload["overwrite"] = overwrite
        }
        val body = gson.toJson(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).method(method, body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val responseBody = response.body?.string() ?: return@use MixWriteResult.NetworkError
                        val envelope = gson.fromJson(responseBody, MixWriteEnvelope::class.java)
                        val definition = envelope?.mix ?: return@use MixWriteResult.NetworkError
                        MixWriteResult.Success(definition.copy(status = envelope.status ?: definition.status))
                    }
                    404 -> MixWriteResult.UnknownJobOrMaterial
                    409 -> parseSyncFailure(response) ?: MixWriteResult.DuplicateName(name)
                    422 -> {
                        val message = errorMessage(response)
                        val prefix = "missing program: "
                        if (message.startsWith(prefix)) MixWriteResult.MissingProgram(message.removePrefix(prefix))
                        else MixWriteResult.BadRequest(message)
                    }
                    400 -> MixWriteResult.BadRequest(errorMessage(response))
                    500 -> parseSyncFailure(response) ?: MixWriteResult.NetworkError
                    503 -> parseSyncFailure(response) ?: MixWriteResult.CompileBusy
                    504 -> parseSyncFailure(response) ?: MixWriteResult.WinxisoTimeout
                    else -> MixWriteResult.NetworkError
                }
            }
        }.getOrDefault(MixWriteResult.NetworkError)
    }

    private fun errorMessage(response: Response): String =
        runCatching { gson.fromJson(response.body?.string().orEmpty(), ErrorEnvelope::class.java)?.error }
            .getOrNull().orEmpty()

    // Reads the response body once. Returns null (no sync-failure shape found) rather than
    // throwing, so callers can fall back to their normal per-status-code result.
    private fun parseSyncFailure(response: Response): MixWriteResult.SyncFailed? {
        val envelope = runCatching {
            gson.fromJson(response.body?.string().orEmpty(), MixSyncErrorEnvelope::class.java)
        }.getOrNull() ?: return null
        val definition = envelope.mix?.mix ?: return null
        val merged = definition.copy(status = envelope.mix.status ?: definition.status)
        return MixWriteResult.SyncFailed(merged, envelope.code ?: "history_sync_failed", envelope.recoveryUrl)
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

    suspend fun submitPgmEdits(
        job: String,
        material: String,
        requestId: String,
        files: List<PgmEditRow>
    ): PgmEditSubmitResult = withContext(Dispatchers.IO) {
        val url = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgm-edits")
            .build()
        val body = gson.toJson(PgmEditBatchRequest(requestId, files)).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val responseBody = response.body?.string() ?: return@use PgmEditSubmitResult.NetworkError
                        PgmEditSubmitResult.Success(gson.fromJson(responseBody, PgmEditBatchResponse::class.java))
                    }
                    403 -> PgmEditSubmitResult.Disabled
                    409 -> PgmEditSubmitResult.EditBusy
                    503 -> PgmEditSubmitResult.CompileBusy
                    504 -> PgmEditSubmitResult.WinxisoTimeout
                    else -> PgmEditSubmitResult.NetworkError
                }
            }
        }.getOrDefault(PgmEditSubmitResult.NetworkError)
    }

    suspend fun getMixCatalog(job: String, material: String): MixCatalogFetchResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("mix-catalog")
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return@use MixCatalogFetchResult.NetworkError
                val envelope = gson.fromJson(response.body?.string().orEmpty(), MixCatalogEnvelope::class.java)
                    ?: return@use MixCatalogFetchResult.NetworkError
                val snapshot = envelope.toSnapshot(job, material) ?: return@use MixCatalogFetchResult.NetworkError
                MixCatalogFetchResult.Success(snapshot)
            }
        }.getOrDefault(MixCatalogFetchResult.NetworkError)
    }

    suspend fun replaceMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        expectedRevision: Long
    ): MixCatalogMutationResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("mixes")
            .addPathSegment(name)
            .addPathSegment("replace")
            .build()
        val body = gson.toJson(
            mapOf("programs" to programs, "expectedRevision" to expectedRevision)
        ).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        executeCatalogMutation(request, job, material)
    }

    suspend fun createCatalogMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        expectedRevision: Long
    ): MixCatalogMutationResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("mixes")
            .build()
        val body = gson.toJson(
            mapOf(
                "name" to name,
                "programs" to programs,
                "expectedRevision" to expectedRevision
            )
        ).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        executeCatalogMutation(request, job, material, duplicateName = name)
    }

    suspend fun deleteExternalMix(
        job: String,
        material: String,
        filename: String,
        expectedRevision: Long
    ): MixCatalogMutationResult = withContext(Dispatchers.IO) {
        val url = materialUrl(job, material).newBuilder()
            .addPathSegment("external-mixes")
            .addPathSegment(filename)
            .addQueryParameter("expectedRevision", expectedRevision.toString())
            .build()
        val request = Request.Builder().url(url).delete().build()
        executeCatalogMutation(request, job, material)
    }

    private fun executeCatalogMutation(
        request: Request,
        job: String,
        material: String,
        duplicateName: String? = null
    ): MixCatalogMutationResult = runCatching {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when (response.code) {
                200 -> {
                    val envelope = gson.fromJson(
                        body,
                        MixCatalogMutationEnvelope::class.java
                    ) ?: return@use MixCatalogMutationResult.NetworkError
                    envelope.toSnapshot(job, material)?.let(MixCatalogMutationResult::Success)
                        ?: MixCatalogMutationResult.NetworkError
                }
                else -> parseCatalogMutationFailure(response.code, body, job, material, duplicateName)
            }
        }
    }.getOrDefault(MixCatalogMutationResult.NetworkError)

    private fun parseCatalogMutationFailure(
        statusCode: Int,
        body: String,
        job: String,
        material: String,
        duplicateName: String?
    ): MixCatalogMutationResult {
        val syncFailure = runCatching {
            gson.fromJson(body, MixCatalogSyncErrorEnvelope::class.java)
        }.getOrNull()?.let { envelope ->
            envelope.mix?.toSnapshot(job, material)?.let { snapshot ->
                MixCatalogMutationResult.SyncFailed(
                    snapshot = snapshot,
                    code = envelope.code ?: "history_sync_failed",
                    recoveryUrl = envelope.recoveryUrl
                )
            }
        }
        if (syncFailure != null) return syncFailure

        val error = runCatching { gson.fromJson(body, ErrorEnvelope::class.java) }.getOrNull()
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

    private fun MixCatalogEnvelope.toSnapshot(
        job: String,
        material: String,
        requireOk: Boolean = true
    ): MixCatalogSnapshot? {
        if (requireOk && !ok) return null
        val revision = revision ?: return null
        return MixCatalogSnapshot(job, material, revision, entries)
    }

    private fun MixCatalogMutationEnvelope.toSnapshot(job: String, material: String): MixCatalogSnapshot? =
        if (ok) catalog?.toSnapshot(job, material, requireOk = false) else null

    private fun materialUrl(job: String, material: String) = "$root/jobs/".toHttpUrl().newBuilder()
        .addPathSegment(job)
        .addPathSegment("materials")
        .addPathSegment(material)
        .build()

}
