package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MixServiceClient(private val baseUrl: String = "http://192.168.20.4:8477") {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/status".toHttpUrl()).get().build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun listPgms(job: String, material: String): List<PgmInventoryItem> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/jobs/".toHttpUrl().newBuilder()
            .addPathSegment(job)
            .addPathSegment("materials")
            .addPathSegment(material)
            .addPathSegment("pgms")
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val type = object : TypeToken<List<PgmInventoryItem>>() {}.type
                gson.fromJson<List<PgmInventoryItem>>(body, type).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun listMixes(job: String, material: String): List<MixDefinition>? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/mixes".toHttpUrl().newBuilder()
            .addQueryParameter("job", job)
            .addQueryParameter("material", material)
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val type = object : TypeToken<List<MixDefinition>>() {}.type
                gson.fromJson<List<MixDefinition>>(body, type)
            }
        }.getOrNull()
    }
}
