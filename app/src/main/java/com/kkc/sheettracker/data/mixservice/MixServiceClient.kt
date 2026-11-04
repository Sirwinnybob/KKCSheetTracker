package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.MixServiceStatus
import com.kkc.sheettracker.data.cnc.machineAccess
import com.kkc.sheettracker.data.cnc.machineIdentityRefusal
import com.kkc.sheettracker.data.cnc.parseMixServiceStatus
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

/** Second-pass and PGM inventory client bound to one explicit machine. */
class MixServiceClient(private val machine: CncMachine) : MixOperationService {
    private val baseUrl: String get() = machine.baseUrl
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val root: String get() = baseUrl.trimEnd('/')

    private data class PgmListEnvelope(val ok: Boolean = false, val pgms: List<PgmInventoryItem> = emptyList())
    private data class OperationEnvelope(val ok: Boolean = false, val operation: MixServiceOperation? = null)

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        // POST acceptance may be lost after the CNC has already queued the mutation.
        // The CNC uses HTTP/1.0 and may close a pooled read connection (such as the /status
        // preflight) as it is reused. Never put a non-replayable write on one.
        private val mutationClient = client.newBuilder().connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false).build()
    }

    suspend fun listPgms(job: String, material: String, withSha256: Boolean = false): List<PgmInventoryItem> = withContext(Dispatchers.IO) {
        val url = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgms")
            .apply { if (withSha256) addQueryParameter("withSha256", "true") }
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                gson.fromJson(body, PgmListEnvelope::class.java)?.pgms.orEmpty().map { item ->
                    if (item.sha256 == null || validPgmHash(item.sha256)) item else item.copy(sha256 = null, revisionError = "Invalid CNC revision")
                }
            }
        }.getOrDefault(emptyList())
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
                    parseHistory(body)
                }
            }.getOrNull()
        }

    /** Wire trust cannot come from Gson's omitted-field defaults or scalar coercions. */
    private fun parseHistory(body: String): PgmEditHistoryView? {
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (root["ok"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } != true ||
            root["stateSource"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } != true ||
            !root.has("error") || root["error"].let { !it.isJsonNull && !(it.isJsonPrimitive && it.asJsonPrimitive.isString) } ||
            root["files"]?.isJsonObject != true) return null
        for ((_, value) in root["files"].asJsonObject.entrySet()) {
            if (!value.isJsonObject) return null
            val record = value.asJsonObject
            if (record["history"]?.isJsonArray != true || record["current"]?.isJsonObject != true) return null
            val current = record["current"].asJsonObject
            if (current["mode"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } != true ||
                current["punloadRemoved"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } != true) return null
        }
        return gson.fromJson(root, PgmEditHistoryView::class.java)?.takeIf { it.canEdit }
    }

    override suspend fun submitPgmEdits(
        job: String,
        material: String,
        requestId: String,
        files: List<PgmEditRow>
    ): MixServiceOperation {
        // Verify the service at this address is still the bound, writable machine before any POST.
        val status = serviceStatus()
        val access = machineAccess(machine, status, requireStatus = true)
        access.reason?.let { throw MixOperationClientException(it) }
        if (!access.secondPass) throw MixOperationClientException("Second-pass editing isn't available on ${machine.displayName}")
        if (files.any { it.expectedSha256 != null || it.expectedSourceSha256 != null } && "pgm-edit-revisions" !in status?.features.orEmpty()) {
            throw MixOperationClientException("The CNC editor needs an update for revision checks")
        }
        return submitOperation(
        url = "$root/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgm-edits")
            .build(),
        method = "POST",
        payload = PgmEditBatchRequest(requestId, files),
    )
    }

    suspend fun editorFeatures(): Set<String> = serviceStatus()?.features.orEmpty()

    suspend fun serviceStatus(): MixServiceStatus? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$root/status").get().build()).execute().use { response ->
                if (!response.isSuccessful) null else parseMixServiceStatus(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun submitPgmPreview(job: String, material: String, requestId: String, row: PgmEditRow,
        restoreOriginal: Boolean = false): MixServiceOperation {
        val status = serviceStatus()
        // Preview writes nothing, so a read-only service may still preview; a different machine may not.
        status?.let { machineIdentityRefusal(machine, it) }?.let { throw MixOperationClientException(it.second) }
        if ("pgm-edit-preview" !in status?.features.orEmpty()) throw MixOperationClientException("Source preview needs a CNC service update")
        return submitOperation(url = "$root/jobs/".toHttpUrl().newBuilder().addPathSegment(job)
            .addPathSegment("materials").addPathSegment(material).addPathSegment("pgm-edits").addPathSegment("preview").build(),
            method = "POST", payload = PgmPreviewRequest(requestId, listOf(PgmPreviewRow(row.name, row.secondPass,
                row.removePUnload, row.expectedSha256, restoreOriginal))))
    }

    override suspend fun getOperation(id: String): MixServiceOperation = withContext(Dispatchers.IO) {
        val url = "$root/operations/".toHttpUrl().newBuilder().addPathSegment(id).build()
        readOperation(Request.Builder().url(url).get().build())
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
        runCatching { mutationClient.newCall(request).execute().use(::parseAcceptedOperation) }
            .getOrElse { throw MixOperationClientException(it.message ?: "operation submission failed") }
    }

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
