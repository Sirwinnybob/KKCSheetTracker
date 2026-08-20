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

data class CollisionPreview(val collision: Boolean, val validResolutions: List<String>)
data class OperationStatus(val operationId: String, val state: String, val errorSummary: String?)

/**
 * Plain REST trigger client for the Ready Jobs archive/restore lifecycle endpoints on the Hours
 * Tracker backend (routes/ready_jobs_archive_lifecycle.py, ready_jobs_worker_operations.py).
 * Mirrors AdminSyncClient's pattern: one shared OkHttpClient with a short timeout, plain
 * org.json.JSONObject parsing, runCatching { ... }.getOrNull() so any failure (timeout,
 * connection refused, non-2xx) collapses to null for the caller to handle.
 */
class ArchiveAdminClient(serverUrl: String) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun parseCollisionPreview(body: String): CollisionPreview {
        val obj = JSONObject(body)
        return CollisionPreview(
            collision = obj.getBoolean("collision"),
            validResolutions = obj.optJSONArray("validResolutions")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }.orEmpty(),
        )
    }

    suspend fun previewArchiveCollision(folderName: String): CollisionPreview? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/ready-jobs-archive/".toHttpUrl().newBuilder()
            .addPathSegment("archive")
            .addPathSegment(folderName)
            .addPathSegment("collision-preview")
            .build()
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseCollisionPreview(response.body?.string() ?: return@use null)
            }
        }.getOrNull()
    }

    suspend fun previewRestoreCollision(folderName: String): CollisionPreview? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/ready-jobs-archive/".toHttpUrl().newBuilder()
            .addPathSegment("restore")
            .addPathSegment(folderName)
            .addPathSegment("collision-preview")
            .build()
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseCollisionPreview(response.body?.string() ?: return@use null)
            }
        }.getOrNull()
    }

    private suspend fun trigger(
        direction: String, folderName: String, initiator: String, collisionChoice: String,
        renameTo: String?, overwriteConfirmed: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("initiator", initiator)
            put("collisionChoice", collisionChoice)
            renameTo?.let { put("renameTo", it) }
            put("overwriteConfirmed", overwriteConfirmed)
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

    suspend fun triggerArchive(
        folderName: String, initiator: String, collisionChoice: String,
        renameTo: String? = null, overwriteConfirmed: Boolean = false,
    ): String? = trigger("archive", folderName, initiator, collisionChoice, renameTo, overwriteConfirmed)

    suspend fun triggerRestore(
        folderName: String, initiator: String, collisionChoice: String,
        renameTo: String? = null, overwriteConfirmed: Boolean = false,
    ): String? = trigger("restore", folderName, initiator, collisionChoice, renameTo, overwriteConfirmed)

    suspend fun getOperationStatus(operationId: String): OperationStatus? = withContext(Dispatchers.IO) {
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
