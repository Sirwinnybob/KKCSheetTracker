package com.kkc.sheettracker.data.cnc

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Destination policy for PUNLOAD in a moved material (plan Task 5 wire values). */
enum class PunloadPolicy(val wire: String) { REMOVE("remove"), RESTORE("restore"), KEEP_REMOVED("keepRemoved") }

/** One tracked sheet, copied read-only from canonical progress. Moves never write statuses. */
data class SheetProgressEntry(
    val jobFolderName: String,
    val pdfFilename: String,
    val page: Int,
    val fileFingerprint: String,
    val status: String,
    val pgms: List<String>,
)

data class SheetProgressSnapshot(val version: Int, val capturedAtMs: Long, val entries: List<SheetProgressEntry>)

data class TransferPreviewRequest(
    val source: CncMachine,
    val destination: CncMachine,
    val job: String,
    val material: String,
    val expectedSourceRevision: String,
    val progress: SheetProgressSnapshot,
    /** Selected worklist supplying order/settings only; metadata decides which sheets remain. */
    val worklistFile: String?,
    val punloadPolicy: PunloadPolicy,
)

data class TransferPreview(
    val previewId: String,
    val completed: List<String>,
    val remaining: List<String>,
    val conflicts: List<String>,
    val warnings: List<String>,
    val canStart: Boolean,
)

data class TransferOperation(val id: String, val phase: String, val outcome: String?, val error: String?) {
    val isTerminal: Boolean get() = outcome != null
    /** Only a completed outcome is a verified move (destination read back and source retired). */
    val succeeded: Boolean get() = outcome == "completed"
    /** The service guarantees the source is untouched and nothing was published. */
    val failed: Boolean get() = outcome == "failed"
    /** Source retired into recovery but activation didn't verify. Terminal; the office resolves it. */
    val needsAttention: Boolean get() = outcome == "needs_attention"
}

sealed interface TransferResult<out T> {
    data class Ok<T>(val value: T) : TransferResult<T>
    data class Rejected(val code: String, val message: String) : TransferResult<Nothing>
    /** The service predates material transfers. */
    data object Unsupported : TransferResult<Nothing>
    /** No readable reply. For [MaterialTransferApi.start] the outcome is uncertain. */
    data object Offline : TransferResult<Nothing>
}

interface MaterialTransferApi {
    suspend fun preview(request: TransferPreviewRequest): TransferResult<TransferPreview>
    suspend fun start(previewId: String, requestId: String): TransferResult<TransferOperation>
    suspend fun operation(operationId: String): TransferResult<TransferOperation>
}

/**
 * Typed transport for the coordinating (destination) service's public material-transfer routes.
 * Bound to one endpoint snapshot; writes use fresh connections and are never retried.
 */
class MaterialTransferClient(private val endpoint: String, http: OkHttpClient = DEFAULT_HTTP) : MaterialTransferApi {
    private val readHttp = http
    private val writeHttp = http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false).readTimeout(90, TimeUnit.SECONDS).build()

    override suspend fun preview(request: TransferPreviewRequest): TransferResult<TransferPreview> {
        val body = JsonObject().apply {
            addProperty("sourceMachineId", request.source.id)
            addProperty("destinationMachineId", request.destination.id)
            addProperty("job", request.job)
            addProperty("material", request.material)
            addProperty("expectedSourceRevision", request.expectedSourceRevision)
            add("progressSnapshot", JsonObject().apply {
                addProperty("version", request.progress.version)
                addProperty("capturedAtMs", request.progress.capturedAtMs)
                add("entries", JsonArray().apply { request.progress.entries.forEach { entry -> add(JsonObject().apply {
                    addProperty("jobFolderName", entry.jobFolderName)
                    addProperty("pdfFilename", entry.pdfFilename)
                    addProperty("page", entry.page)
                    addProperty("fileFingerprint", entry.fileFingerprint)
                    addProperty("status", entry.status)
                    add("pgms", JsonArray().apply { entry.pgms.forEach(::add) })
                }) } })
            })
            request.worklistFile?.let { addProperty("worklistFile", it) }
            addProperty("punloadPolicy", request.punloadPolicy.wire)
        }
        // Preview writes nothing, but it reserves nothing either; a lost reply is simply retried by the operator.
        return execute(post(url("material-transfers", "preview"), body), writeHttp) { root ->
            val preview = root["preview"].obj()
            TransferPreview(preview.string("previewId")!!.also { require(it.isNotBlank()) },
                preview.strings("completed"), preview.strings("remaining"), preview.strings("conflicts"),
                preview.strings("warnings"), preview["canStart"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true)
        }
    }

    override suspend fun start(previewId: String, requestId: String): TransferResult<TransferOperation> {
        val body = JsonObject().apply { addProperty("previewId", previewId); addProperty("requestId", requestId) }
        return execute(post(url("material-transfers"), body), writeHttp, ::parseOperation)
    }

    override suspend fun operation(operationId: String): TransferResult<TransferOperation> =
        execute(Request.Builder().url(url("material-transfers", operationId)).get().build(), readHttp, ::parseOperation)

    private fun parseOperation(root: JsonObject): TransferOperation {
        val operation = root["operation"].obj()
        val id = operation.string("id") ?: operation.string("operationId")
        require(!id.isNullOrBlank()) { "operation without an ID" }
        return TransferOperation(id, operation.string("phase").orEmpty(), operation.string("outcome"), operation.string("error"))
    }

    private fun url(vararg segments: String) = endpoint.trimEnd('/').toHttpUrl().newBuilder()
        .apply { segments.forEach { addPathSegment(it) } }.build()

    private fun post(url: okhttp3.HttpUrl, body: JsonObject) =
        Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()

    private suspend fun <T> execute(request: Request, http: OkHttpClient, parse: (JsonObject) -> T): TransferResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val root = runCatching { JsonParser.parseString(response.body?.string().orEmpty()).obj() }.getOrNull()
                        ?: return@use TransferResult.Offline
                    if (response.isSuccessful && root.bool("ok") == true) {
                        runCatching { TransferResult.Ok(parse(root)) }.getOrElse { TransferResult.Offline }
                    } else {
                        val message = root.string("error").orEmpty()
                        when {
                            response.code == 404 && message.startsWith("unknown route") -> TransferResult.Unsupported
                            root.bool("ok") == false && root.string("code") != null -> TransferResult.Rejected(root.string("code")!!, message)
                            else -> TransferResult.Offline
                        }
                    }
                }
            } catch (_: IOException) {
                TransferResult.Offline
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val DEFAULT_HTTP = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}

private fun JsonElement?.obj(): JsonObject = requireNotNull(this?.takeIf { it.isJsonObject }?.asJsonObject) { "object expected" }
private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
private fun JsonObject.strings(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }.orEmpty()
