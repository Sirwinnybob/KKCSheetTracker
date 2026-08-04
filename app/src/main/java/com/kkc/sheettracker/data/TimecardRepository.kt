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
import java.io.IOException
import java.util.concurrent.TimeUnit

data class EmployeeInfo(
    val pin: String,
    val name: String,
    val displayName: String
)

data class PunchStatus(
    val found: Boolean,
    val name: String?,
    val displayName: String?,
    val isClockedIn: Boolean,
    val clockedInSince: String?,
    val hoursToday: Double
)

data class PunchResult(
    val name: String,
    val action: String,        // "in" or "out"
    val hoursWorked: Double?   // non-null on clock-out
)

class TimecardHttpException(val statusCode: Int) : IOException("Timecard server request failed: HTTP $statusCode")

class TimecardRepository(
    private val serverUrl: String,
    // AUD-01: per-device auth token sent to the hub as 'X-Hub-Token'. Empty = omit the header
    // (backward compatible during the staged rollout, before the hub enforces auth).
    private val hubToken: String = ""
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // Adds the device auth header when a token is configured.
    private fun Request.Builder.withHubAuth(): Request.Builder =
        if (hubToken.isNotBlank()) header("X-Hub-Token", hubToken) else this

    suspend fun getEmployees(): List<EmployeeInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$serverUrl/api/employees")
            .withHubAuth()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TimecardHttpException(response.code)
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                EmployeeInfo(
                    pin = obj.getString("pin"),
                    name = obj.getString("name"),
                    // AUD-11: the hub JSON key `display_name` is the human effective name
                    // (`_effective_display_name`), empty when it equals the real name — NOT the
                    // RTC numeric Display ID. Parse it so browser and tablet agree; a Hours
                    // custom name (resolved in TimecardStore) still takes precedence over it.
                    displayName = obj.optString("display_name")
                )
            }
        }
    }

    suspend fun getStatus(pin: String): PunchStatus = withContext(Dispatchers.IO) {
        val url = "$serverUrl/api/status".toHttpUrl().newBuilder()
            .addQueryParameter("pin", pin)
            .build()
        val request = Request.Builder()
            .url(url)
            .withHubAuth()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw TimecardHttpException(response.code)
            }
            val body = response.body?.string() ?: "{}"
            val obj = JSONObject(body)
            PunchStatus(
                found = obj.optBoolean("found", false),
                name = obj.optString("name").takeIf { it.isNotEmpty() },
                // AUD-11: hub `display_name` is the human effective name — parse it (blank
                // when it matches the real name). Hours custom names still win in TimecardStore.
                displayName = obj.optString("display_name").takeIf { it.isNotEmpty() },
                isClockedIn = obj.optBoolean("is_clocked_in", false),
                clockedInSince = obj.optString("clocked_in_since").takeIf { it.isNotEmpty() },
                hoursToday = obj.optDouble("hours_today", 0.0)
            )
        }
    }

    suspend fun punch(pin: String): PunchResult = withContext(Dispatchers.IO) {
        val json = JSONObject().put("pin", pin).toString()
        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$serverUrl/api/punch")
            .post(body)
            .withHubAuth()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw TimecardHttpException(response.code)
            }
            val respBody = response.body?.string() ?: "{}"
            val obj = JSONObject(respBody)
            PunchResult(
                name = obj.optString("name", ""),
                action = obj.optString("action", "in"),
                hoursWorked = if (obj.has("hours_worked")) obj.getDouble("hours_worked") else null
            )
        }
    }
}
