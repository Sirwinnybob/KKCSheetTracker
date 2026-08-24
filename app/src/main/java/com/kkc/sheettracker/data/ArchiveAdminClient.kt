package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OperationStatus(val operationId: String, val state: String, val errorSummary: String?)

/**
 * The small lifecycle boundary used by the detail action sheet. It deliberately has no
 * collision-resolution controls: tablet archive requests always fail safely on a collision.
 */
interface ArchiveLifecycleClient {
    suspend fun triggerArchive(folderName: String, initiator: String): String?
    suspend fun getOperationStatus(operationId: String): OperationStatus?
}

/**
 * Plain REST trigger client for the Ready Jobs archive/restore lifecycle endpoints on the Hours
 * Tracker backend (routes/ready_jobs_archive_lifecycle.py, ready_jobs_worker_operations.py).
 * Mirrors AdminSyncClient's pattern: one shared OkHttpClient with a short timeout, plain
 * org.json.JSONObject parsing, runCatching { ... }.getOrNull() so any failure (timeout,
 * connection refused, non-2xx) collapses to null for the caller to handle.
 */
class ArchiveAdminClient(serverUrl: String) : ArchiveLifecycleClient {
    private val baseUrl = serverUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun trigger(
        direction: String,
        folderName: String,
        initiator: String,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("initiator", initiator)
            put("collisionChoice", "fail")
        }.toString().toRequestBody(jsonMediaType)
        val url = "$baseUrl/api/ready-jobs-archive/".toHttpUrl().newBuilder()
            .addPathSegment(direction)
            .addPathSegment(folderName)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string() ?: return@use null).getString("operationId")
            }
        }.getOrNull()
    }

    override suspend fun triggerArchive(folderName: String, initiator: String): String? =
        trigger("archive", folderName, initiator)

    /** Reserved bounded transport for a future live-job restore action. */
    suspend fun triggerRestore(folderName: String, initiator: String): String? =
        trigger("restore", folderName, initiator)

    override suspend fun getOperationStatus(operationId: String): OperationStatus? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/ready-jobs-worker/operations/".toHttpUrl().newBuilder()
            .addPathSegment(operationId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val obj = JSONObject(response.body?.string() ?: return@use null)
                OperationStatus(
                    operationId = obj.getString("operationId"),
                    state = obj.getString("state"),
                    errorSummary = obj.optString("errorSummary").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()
    }
}
