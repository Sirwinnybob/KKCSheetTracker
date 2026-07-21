package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct-write fast path to the Hours Tracker backend for admin-mode job order, job board, and
 * delivery schedule edits. Every method returns null/false on ANY failure (timeout, connection
 * refused, non-2xx) so the caller can fall back to the existing per-tablet request-file stores
 * (ProductionOrderRequestStore / JobBoardRequestStore / DeliveryScheduleRequestStore) — no retry
 * loop here, since a stalled UI gesture is worse than an immediate fallback.
 */
class AdminSyncClient(private val serverUrl: String) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** Returns the canonical order on success, null on any failure (caller should fall back). */
    suspend fun applyProductionOrder(order: List<String>, tabletId: String): List<String>? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("order", JSONArray(order))
                put("tabletId", tabletId)
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$serverUrl/api/admin-sync/production-order")
                .post(body)
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val obj = JSONObject(response.body?.string() ?: return@use null)
                    val arr = obj.getJSONArray("order")
                    (0 until arr.length()).map { arr.getString(it) }
                }
            }.getOrNull()
        }

    /** Returns true on success, false on any failure (caller should fall back). */
    suspend fun applyJobBoardEdits(edits: List<JobBoardEdit>, tabletId: String): Boolean =
        withContext(Dispatchers.IO) {
            val editsArray = JSONArray()
            edits.forEach { edit ->
                editsArray.put(JSONObject().apply {
                    put("folderName", edit.folderName)
                    edit.labelIds?.let { put("labelIds", JSONArray(it)) }
                    edit.boardSection?.let { put("boardSection", it) }
                })
            }
            val body = JSONObject().apply {
                put("edits", editsArray)
                put("tabletId", tabletId)
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$serverUrl/api/admin-sync/job-board-edits")
                .post(body)
                .build()
            runCatching {
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Returns the canonical schedule on success, null on any failure (caller should fall back). */
    suspend fun applyDeliverySchedule(editRequest: DeliveryScheduleEditRequest): com.kkc.sheettracker.data.models.DeliverySchedule? =
        withContext(Dispatchers.IO) {
            val slotEditsArray = JSONArray()
            editRequest.slotEdits.forEach { edit ->
                val jobsArray = JSONArray()
                edit.jobs.forEach { job ->
                    jobsArray.put(JSONObject().apply {
                        put("jobNumber", job.jobNumber)
                        put("description", job.description)
                        if (job.address.isNotBlank()) put("address", job.address)
                        if (job.folderName.isNotBlank()) put("folderName", job.folderName)
                    })
                }
                slotEditsArray.put(JSONObject().apply {
                    put("slot", edit.slot)
                    put("jobs", jobsArray)
                })
            }
            val body = JSONObject().apply {
                put("tabletId", editRequest.tabletId)
                put("resetAll", editRequest.resetAll)
                put("slotEdits", slotEditsArray)
            }.toString().toRequestBody(jsonMediaType)
            val httpRequest = Request.Builder()
                .url("$serverUrl/api/admin-sync/delivery-schedule")
                .post(body)
                .build()
            runCatching {
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    parseDeliverySchedule(response.body?.string() ?: return@use null)
                }
            }.getOrNull()
        }
}
