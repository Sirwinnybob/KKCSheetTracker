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
    /** 503, timeout, malformed server URL, any other failure: retry via a slot edit / request file. */
    object Unavailable : DeliveryJobDateResult
    /**
     * HT returned 2xx but no usable v2 document, so the change WAS applied. Do not retry (that would
     * resend stale whole-slot intent); wait for the live/file refresh to show the result.
     */
    object AcceptedWithoutDocument : DeliveryJobDateResult
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
                put("requestedAt", editRequest.requestedAt)
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
                    val text = response.body?.string() ?: return@use null
                    // A 2xx without any schedule object (e.g. `{}`) would parse as an empty v1
                    // schedule and blank the board; treat it as a failure so the caller falls back.
                    val root = JSONObject(text)
                    if (root.optJSONObject("slots") == null &&
                        root.optJSONObject("datedSlots") == null &&
                        root.optJSONObject("schedule") == null
                    ) return@use null
                    val doc = parseDeliveryScheduleDocument(text)
                    DeliveryScheduleApplyResult(doc.schedule, doc.failures)
                }
            }.getOrNull()
        }

    /**
     * `PUT /api/delivery-schedule/jobs/{folderName}` — changes only this one job's Office delivery
     * date, so it cannot clobber a job another tablet just placed. The body is always a JSON object
     * carrying [tabletId] so HT attributes the change to this tablet: set is
     * `{"date","window"?,"tabletId"}`, clear (`date == null`) is `{"date":null,"tabletId"}`. An HT
     * build that predates the object form of a clear answers 422; that clear is retried once with
     * the old bare `null` body. Only call against a v2 server (schedule.isDated): an old server has
     * no route.
     */
    suspend fun setDeliveryJobDate(
        folderName: String,
        date: java.time.LocalDate?,
        window: String?,
        tabletId: String
    ): DeliveryJobDateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // Built inside runCatching: a malformed configured server URL must degrade to
                // Unavailable (fall back to the request file), never throw into the UI.
                val url = baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/delivery-schedule/jobs")
                    .addPathSegment(folderName)
                    .build()
                val bodyText = JSONObject().apply {
                    put("date", if (date == null) JSONObject.NULL else date.toString())
                    if (date != null && window != null) put("window", window)
                    put("tabletId", tabletId)
                }.toString()
                val first = putJobDate(url, bodyText)
                if (date == null && first is DeliveryJobDateResult.Refused && first.status == 422) {
                    putJobDate(url, "null")
                } else {
                    first
                }
            }.getOrElse { DeliveryJobDateResult.Unavailable }
        }

    private fun putJobDate(url: okhttp3.HttpUrl, bodyText: String): DeliveryJobDateResult {
        val request = Request.Builder().url(url).put(bodyText.toRequestBody(jsonMediaType)).build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    // 2xx means HT applied the change. Only trust the body as the canonical
                    // schedule when it is a v2 document; anything else must not blank the board.
                    val doc = runCatching { parseDeliveryScheduleDocument(text) }.getOrNull()
                    if (doc != null && doc.schedule.isDated) {
                        DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(doc.schedule, doc.failures))
                    } else {
                        DeliveryJobDateResult.AcceptedWithoutDocument
                    }
                }
                response.code in setOf(400, 404, 409, 422) ->
                    DeliveryJobDateResult.Refused(response.code, refusalReason(response.code, text))
                else -> DeliveryJobDateResult.Unavailable
            }
        }
    }

    /**
     * User-facing reason for a definitive refusal. FastAPI `detail` is a string for our own
     * HTTPExceptions but a list of `{loc,msg,type}` for 422 validation errors; non-JSON or missing
     * detail falls back to per-status text.
     */
    private fun refusalReason(status: Int, body: String): String {
        val detail = runCatching { JSONObject(body).opt("detail") }.getOrNull()
        val parsed = when (detail) {
            is String -> detail.trim()
            is JSONArray -> (0 until detail.length())
                .mapNotNull { detail.optJSONObject(it)?.optString("msg")?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString("; ")
            else -> ""
        }
        if (parsed.isNotEmpty()) return parsed
        return when (status) {
            409 -> "That day already has 6 deliveries"
            404 -> "No office job for this folder"
            else -> "The office job is closed or the request was invalid"
        }
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
