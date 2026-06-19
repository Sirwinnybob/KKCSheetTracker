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

class TimecardRepository(private val serverUrl: String) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getEmployees(): List<EmployeeInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$serverUrl/api/employees")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                EmployeeInfo(
                    pin = obj.getString("pin"),
                    name = obj.getString("name"),
                    // display_name from the hub is the RTC-1000's numeric Display ID (e.g. "501"),
                    // NOT a human name. Custom display names are resolved by TimecardStore.
                    displayName = ""
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
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext PunchStatus(
                    found = false,
                    name = null,
                    displayName = null,
                    isClockedIn = false,
                    clockedInSince = null,
                    hoursToday = 0.0
                )
            }
            val body = response.body?.string() ?: "{}"
            val obj = JSONObject(body)
            PunchStatus(
                found = obj.optBoolean("found", false),
                name = obj.optString("name").takeIf { it.isNotEmpty() },
                // display_name from hub is the RTC-1000's numeric Display ID — not a human name.
                displayName = null,
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
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Punch failed: HTTP ${response.code}")
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
