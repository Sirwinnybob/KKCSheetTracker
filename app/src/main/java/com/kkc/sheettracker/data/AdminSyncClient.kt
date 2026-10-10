package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DeliveryScheduleApplyResult(
    val schedule: com.kkc.sheettracker.data.models.DeliverySchedule,
    val failures: List<com.kkc.sheettracker.data.models.DeliveryScheduleFailure>
)

sealed interface DeliveryJobDateResult {
    data class Applied(val result: DeliveryScheduleApplyResult) : DeliveryJobDateResult
    /** HT definitively refused this job (409 full, 404 folder unknown, 400/422 closed job or bad input). */
    data class Refused(val status: Int, val reason: String) : DeliveryJobDateResult
    /** 503, timeout, any other failure: retry via a slot edit / request file. */
    object Unavailable : DeliveryJobDateResult
}

/**
 * Direct-write fast path to the Hours Tracker backend for admin-mode job order, job board, and
 * delivery schedule edits. Every method returns null/false on ANY failure (timeout, connection
 * refused, non-2xx) so the caller can fall back to the existing per-tablet request-file stores
 * (ProductionOrderRequestStore / JobBoardRequestStore / DeliveryScheduleRequestStore) — no retry
 * loop here, since a stalled UI gesture is worse than an immediate fallback.
 */
class AdminSyncClient(serverUrl: String) {

    private val baseUrl = serverUrl.trimEnd('/')
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
                .url("$baseUrl/api/admin-sync/production-order")
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
                .url("$baseUrl/api/admin-sync/job-board-edits")
                .post(body)
                .build()
            runCatching {
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Returns the canonical schedule on success, null on any failure (caller should fall back). */
    suspend fun applyDeliverySchedule(editRequest: DeliveryScheduleEditRequest): com.kkc.sheettracker.data.models.DeliverySchedule? =
        applyDeliveryScheduleEdits(editRequest)?.schedule

    /**
     * Returns the canonical schedule plus any per-slot failures HT reported on success, null on any
     * failure (caller should fall back to the request file).
     */
    suspend fun applyDeliveryScheduleEdits(editRequest: DeliveryScheduleEditRequest): DeliveryScheduleApplyResult? =
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
                .url("$baseUrl/api/admin-sync/delivery-schedule")
                .post(body)
                .build()
            runCatching {
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val doc = parseDeliveryScheduleDocument(response.body?.string() ?: return@use null)
                    DeliveryScheduleApplyResult(doc.schedule, doc.failures)
                }
            }.getOrNull()
        }

    /**
     * `PUT /api/delivery-schedule/jobs/{folderName}` — changes only this one job's Office delivery
     * date, so it cannot clobber a job another tablet just placed. `date == null` clears it (JSON
     * `null` body). Only call against a v2 server (schedule.isDated): an old server has no route.
     */
    suspend fun setDeliveryJobDate(folderName: String, date: java.time.LocalDate?, window: String?): DeliveryJobDateResult =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("api/delivery-schedule/jobs")
                .addPathSegment(folderName)
                .build()
            val bodyText = if (date == null) "null" else JSONObject().apply {
                put("date", date.toString())
                if (window != null) put("window", window)
            }.toString()
            val request = Request.Builder().url(url).put(bodyText.toRequestBody(jsonMediaType)).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val doc = parseDeliveryScheduleDocument(text)
                            DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(doc.schedule, doc.failures))
                        }
                        response.code in setOf(400, 404, 409, 422) -> DeliveryJobDateResult.Refused(
                            response.code,
                            runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
                        )
                        else -> DeliveryJobDateResult.Unavailable
                    }
                }
            }.getOrElse { DeliveryJobDateResult.Unavailable }
        }

    /**
     * Fast path for a tablet-authored hide/unhide, covering both Hardwoods and Specialty (see
     * `mode`). Returns true on success, false on ANY failure (caller should fall back to
     * HiddenMaterialsRequestStore) -- same contract as applyJobBoardEdits, no retry loop here.
     */
    suspend fun applyHiddenMaterialsAction(
        mode: com.kkc.sheettracker.data.models.HiddenMaterialsMode,
        action: String,
        scope: String,
        docType: String,
        material: String,
        tabletId: String,
        jobId: String?,
        requestedAt: String
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("mode", mode.name)
            put("action", action)
            put("scope", scope)
            put("docType", docType)
            put("material", material)
            put("tabletId", tabletId)
            put("requestedAt", requestedAt)
            if (jobId != null) put("jobId", jobId)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/admin-sync/hardwoods-hidden-materials")
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
