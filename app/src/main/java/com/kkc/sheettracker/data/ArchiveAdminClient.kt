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
    suspend fun triggerRestore(folderName: String, initiator: String): String?
    suspend fun getOperationStatus(operationId: String): OperationStatus?
}

/**
 * Narrow restore selection contract. It exposes only archived folder names so the tablet can
 * initiate an admin restore without reintroducing the removed archive browsing/cache surface.
 */
interface ArchiveRestoreClient : ArchiveLifecycleClient {
    suspend fun listArchivedFolderNames(): List<String>?
}

/**
 * Plain REST trigger client for the Ready Jobs archive/restore lifecycle endpoints on the Hours
 * Tracker backend (routes/ready_jobs_archive_lifecycle.py, ready_jobs_worker_operations.py).
 * Mirrors AdminSyncClient's pattern: one shared OkHttpClient with a short timeout, plain
 * org.json.JSONObject parsing, runCatching { ... }.getOrNull() so any failure (timeout,
 * connection refused, non-2xx) collapses to null for the caller to handle.
 */
class ArchiveAdminClient(serverUrl: String) : ArchiveRestoreClient {
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

    override suspend fun triggerRestore(folderName: String, initiator: String): String? =
        trigger("restore", folderName, initiator)

    override suspend fun listArchivedFolderNames(): List<String>? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/ready-jobs-archive/library".toHttpUrl()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val archives = JSONObject(response.body?.string() ?: return@use null)
                    .optJSONObject("archives")
                    ?: return@use emptyList()
                buildList {
                    val keys = archives.keys()
                    while (keys.hasNext()) {
                        val entry = archives.optJSONObject(keys.next()) ?: continue
                        entry.optString("folderName").trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        }.getOrNull()
    }

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
