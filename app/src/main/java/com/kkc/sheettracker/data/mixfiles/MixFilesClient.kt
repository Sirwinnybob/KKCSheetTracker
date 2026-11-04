package com.kkc.sheettracker.data.mixfiles

import com.google.gson.JsonObject
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.MixServiceStatus
import com.kkc.sheettracker.data.cnc.machineAccess
import com.kkc.sheettracker.data.cnc.parseMixServiceStatus
import com.kkc.sheettracker.data.mixservice.MixOperationClientException
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.kkc.sheettracker.data.mixfiles.MixFilesJson.revision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class MixFilesClient(
    private val machine: CncMachine,
    private val http: OkHttpClient = DEFAULT_HTTP,
) : MixFilesApi {
    // The CNC uses HTTP/1.0 and may close a read connection while OkHttp is
    // taking it from the shared pool. Never put a non-replayable write on one.
    private val mutationHttp = http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun catalog(job: String, material: String): MixFilesResult<MixFilesCatalog> =
        execute(Request.Builder().url(url(job, material)).get().build(), MixFilesJson::parseCatalog)

    override suspend fun folderRevision(job: String, material: String): MixFilesResult<String> =
        execute(Request.Builder().url(url(job, material, "revision")).get().build(), MixFilesJson::parseFolderRevision)

    override suspend fun write(job: String, material: String, file: String, expectedRevision: String?, entries: List<EntrySpec>, completion: Completion?): MixFilesResult<MixFilesCatalog> =
        post(job, material, "write", fileBody(file, expectedRevision).apply {
            add("entries", MixFilesJson.entries(entries))
            completion?.let { add("completion", MixFilesJson.completion(it)) }
        })

    override suspend fun frz(job: String, material: String, file: String, expectedRevision: String, completion: Completion, pending: List<EntrySpec>): MixFilesResult<MixFilesCatalog> =
        post(job, material, "frz", fileBody(file, expectedRevision).apply {
            add("completion", MixFilesJson.completion(completion))
            add("pending", MixFilesJson.entries(pending))
        })

    override suspend fun rename(job: String, material: String, file: String, newFile: String, expectedRevision: String): MixFilesResult<MixFilesCatalog> =
        post(job, material, "rename", fileBody(file, expectedRevision).apply { addProperty("newFile", newFile) })

    override suspend fun delete(job: String, material: String, file: String, expectedRevision: String): MixFilesResult<MixFilesCatalog> =
        post(job, material, "delete", fileBody(file, expectedRevision))

    override suspend fun deleteRevision(job: String, material: String, file: String, expectedRevision: String): MixFilesResult<MixFilesCatalog> =
        post(job, material, "delete-revision", fileBody(file, expectedRevision))

    override suspend fun restore(job: String, material: String, revisionFile: String, targetFile: String, expectedTargetRevision: String?, completion: Completion?): MixFilesResult<MixFilesCatalog> =
        post(job, material, "restore", JsonObject().apply {
            addProperty("revisionFile", revisionFile)
            addProperty("targetFile", targetFile)
            revision("expectedTargetRevision", expectedTargetRevision)
            completion?.let { add("completion", MixFilesJson.completion(it)) }
        })

    override suspend fun split(job: String, material: String, file: String, expectedRevision: String, entries: List<EntrySpec>, newMixes: List<NewMix>, completion: Completion?): MixFilesResult<MixFilesCatalog> =
        post(job, material, "split", fileBody(file, expectedRevision).apply {
            add("entries", MixFilesJson.entries(entries))
            add("newMixes", MixFilesJson.newMixes(newMixes))
            completion?.let { add("completion", MixFilesJson.completion(it)) }
        })

    override suspend fun features(): Set<String> = serviceStatus()?.features.orEmpty()

    /** `/status`, or null when unreachable or unreadable. Identity fields are absent on old services. */
    suspend fun serviceStatus(): MixServiceStatus? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(root().addPathSegment("status").build()).get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.let(::parseMixServiceStatus)
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Capability is checked before POST: old services must never be probed with a mutation. */
    suspend fun submitPgmEditRevert(job: String, material: String, requestId: String, pgms: List<String>): MixServiceOperation {
        return submitPgmEditRevertRows(job, material, requestId, pgms.map { com.kkc.sheettracker.data.mixservice.PgmRevertRow(it) })
    }

    suspend fun submitPgmEditRevertRows(job: String, material: String, requestId: String, pgms: List<com.kkc.sheettracker.data.mixservice.PgmRevertRow>): MixServiceOperation {
        val status = serviceStatus()
        val access = machineAccess(machine, status, requireStatus = true)
        access.reason?.let { throw MixOperationClientException(it) }
        if (!access.secondPass) throw MixOperationClientException("Second-pass editing isn't available on ${machine.displayName}")
        val supported = status?.features.orEmpty()
        if ("pgm-edits-revert" !in supported) throw MixOperationClientException("Second-pass revert is not supported by this CNC")
        if (pgms.any { it.expectedSha256 != null } && "pgm-edit-revisions" !in supported) throw MixOperationClientException("Revision checks need a CNC editor update")
        val body = JsonObject().apply {
            addProperty("requestId", requestId)
            add("files", JsonArray().apply { pgms.forEach { pgm -> add(JsonObject().apply {
                addProperty("name", pgm.name)
                addProperty("to", "original")
                pgm.expectedSha256?.let { addProperty("expectedSha256", it) }
            }) } })
        }
        val endpoint = root().addPathSegment("jobs").addPathSegment(job)
            .addPathSegment("materials").addPathSegment(material)
            .addPathSegment("pgm-edits").addPathSegment("revert").build()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint).post(MixFilesJson.stringify(body).toRequestBody(JSON_MEDIA_TYPE)).build()
            // Do not automatically repeat an uncertain write, including on a stale pooled socket.
            // The /status preflight just used a pooled read connection; never put this write on it.
            mutationHttp.newCall(request).execute().use { response ->
                val envelope = Gson().fromJson(response.body?.string().orEmpty(), RevertEnvelope::class.java)
                if (response.code != 202 || envelope?.ok != true || envelope.operation == null || envelope.operation.id.isBlank()) {
                    throw MixOperationClientException("Second-pass revert submission failed")
                }
                envelope.operation
            }
        }
    }

    private data class RevertEnvelope(val ok: Boolean = false, val operation: MixServiceOperation? = null)

    private fun root(): HttpUrl.Builder = machine.baseUrl.trimEnd('/').toHttpUrl().newBuilder()

    private fun url(job: String, material: String, action: String? = null): HttpUrl = root()
        .addPathSegment("jobs").addPathSegment(job)
        .addPathSegment("materials").addPathSegment(material)
        .addPathSegment("mix-files")
        .apply { action?.let { addPathSegment(it) } }
        .build()

    private fun fileBody(file: String, expectedRevision: String?): JsonObject = JsonObject().apply {
        addProperty("file", file)
        revision("expectedRevision", expectedRevision)
    }

    private suspend fun post(job: String, material: String, action: String, body: JsonObject): MixFilesResult<MixFilesCatalog> =
        execute(Request.Builder().url(url(job, material, action))
            .post(MixFilesJson.stringify(body).toRequestBody(JSON_MEDIA_TYPE)).build(), MixFilesJson::parseMutationCatalog)

    private suspend fun <T> execute(request: Request, parse: (String) -> T?): MixFilesResult<T> = withContext(Dispatchers.IO) {
        val retryDelays = longArrayOf(500, 1000, 2000, 3000, 5000)
        for (attempt in 0..retryDelays.size) {
            var busy = false
            val result: MixFilesResult<T> = try {
                (if (request.method == "GET") http else mutationHttp).newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use MixFilesResult.Offline
                    if (response.isSuccessful) {
                        parse(body)?.let { MixFilesResult.Ok(it) } ?: MixFilesResult.Offline
                    } else {
                        val error = MixFilesJson.parseError(body, response.code) ?: return@use MixFilesResult.Offline
                        when {
                            response.code == 404 && error.message.startsWith("unknown route") -> MixFilesResult.ServiceOutdated
                            response.code == 503 && error.code == "server_busy" -> { busy = true; MixFilesResult.Offline }
                            else -> MixFilesResult.Rejected(error)
                        }
                    }
                }
            } catch (_: IOException) {
                MixFilesResult.Offline
            }
            // The service returns server_busy before dispatching the request. A lost
            // response is ambiguous, so never retry a mutation after an IO failure.
            if (!busy || attempt == retryDelays.size) return@withContext result
            delay(retryDelays[attempt])
        }
        MixFilesResult.Offline
    }

    companion object {
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
