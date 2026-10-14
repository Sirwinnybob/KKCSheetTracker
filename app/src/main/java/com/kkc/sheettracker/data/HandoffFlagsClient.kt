package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface HandoffFlagPutResult {
    data class Applied(val flags: HandoffItemFlags) : HandoffFlagPutResult
    /** The office changed this flag more recently; refresh, show theirs, don't retry. */
    object Skipped : HandoffFlagPutResult
    /**
     * Any non-2xx (status = HTTP code), or status null for no connection, a timeout, and a 2xx whose
     * body is empty or not JSON (the reply cannot be trusted, so it is not treated as applied).
     * The sidecar carries the change in every case.
     */
    data class Failed(val status: Int?) : HandoffFlagPutResult
}

/** Hours Tracker handoff routes (same app/port as the admin-sync routes). Never throws. */
class HandoffFlagsClient(serverUrl: String) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    // Parsed per call inside runCatching: a malformed configured server URL must degrade to
    // Failed(null) / null (fall back to the sidecar), never throw into the UI or the constructor.
    private fun jobUrl(folderName: String, vararg tail: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().addPathSegments("api/jobs").addPathSegment(folderName).apply {
            tail.forEach { addPathSegment(it) }
        }.build()

    suspend fun putTruck(folderName: String, ref: HandoffFlagRef, truck: HandoffTruck?, requestedAt: String): HandoffFlagPutResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("source", ref.source)
                    .put("id", ref.id)
                    .put("truck", truck?.wire ?: JSONObject.NULL)
                    .put("requestedAt", requestedAt)
                    .toString()
                val request = Request.Builder().url(jobUrl(folderName, "handoff", "flags")).put(body.toRequestBody(json)).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use HandoffFlagPutResult.Failed(response.code)
                    val obj = JSONObject(response.body?.string().orEmpty())
                    if (obj.optBoolean("skipped")) HandoffFlagPutResult.Skipped
                    else HandoffFlagPutResult.Applied(parseFlags(obj.optJSONObject("flags")))
                }
            }.getOrElse { HandoffFlagPutResult.Failed(null) }
        }

    /** HT computes Auto placement; never port those rules. Null on any failure. Side effect: HT applies pending requests first. */
    suspend fun fetchTruckList(folderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(jobUrl(folderName, "handoff", "truck-list")).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val items = JSONObject(response.body?.string().orEmpty()).optJSONArray("items") ?: return@use null
                    (0 until items.length()).mapNotNull { i ->
                        val obj = items.optJSONObject(i) ?: return@mapNotNull null
                        val ref = HandoffFlagRef(obj.optString("source"), obj.optString("id"))
                        if (ref.source.isBlank() || ref.id.isBlank()) return@mapNotNull null
                        val auto = parseFlags(obj.optJSONObject("auto"))
                        val effective = parseFlags(obj.optJSONObject("effective"))
                        ref to HandoffTruckListEntry(ref, auto.truck, effective.truck, effective.count, obj.optBoolean("excluded"))
                    }.toMap()
                }
            }.getOrNull()
        }

    private fun parseFlags(obj: JSONObject?): HandoffItemFlags {
        if (obj == null) return HandoffItemFlags()
        fun str(name: String) = if (obj.isNull(name)) null else obj.optString(name)
        return HandoffItemFlags(HandoffTruck.fromWire(str("truck")), HandoffCount.fromWire(str("count")))
    }

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
