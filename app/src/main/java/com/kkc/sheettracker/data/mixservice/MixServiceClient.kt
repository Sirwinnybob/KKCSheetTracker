package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class MixWriteResult {
    data class Success(val definition: MixDefinition) : MixWriteResult()
    data class DuplicateName(val name: String) : MixWriteResult()
    object UnknownJobOrMaterial : MixWriteResult()
    data class MissingPrograms(val missing: List<String>) : MixWriteResult()
    object InvalidName : MixWriteResult()
    object CompileBusy : MixWriteResult()
    object NetworkError : MixWriteResult()
}

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

    suspend fun createMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        overwrite: Boolean = false
    ): MixWriteResult = writeMix("POST", job, material, name, programs, overwrite)

    suspend fun updateMix(
        job: String,
        material: String,
        name: String,
        programs: List<String>
    ): MixWriteResult = writeMix("PUT", job, material, name, programs, overwrite = false)

    private suspend fun writeMix(
        method: String,
        job: String,
        material: String,
        name: String,
        programs: List<String>,
        overwrite: Boolean
    ): MixWriteResult = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val url = if (method == "POST") {
            "$root/mixes".toHttpUrl()
        } else {
            "$root/mixes/".toHttpUrl().newBuilder().addPathSegment(name).build()
        }
        val payload = mutableMapOf<String, Any?>(
            "job" to job,
            "material" to material,
            "programs" to programs
        )
        if (method == "POST") {
            payload["name"] = name
            payload["overwrite"] = overwrite
        }
        val body = gson.toJson(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).method(method, body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 201 -> {
                        val responseBody = response.body?.string() ?: return@use MixWriteResult.NetworkError
                        MixWriteResult.Success(gson.fromJson(responseBody, MixDefinition::class.java))
                    }
                    400 -> MixWriteResult.InvalidName
                    404 -> MixWriteResult.UnknownJobOrMaterial
                    409 -> MixWriteResult.DuplicateName(name)
                    422 -> {
                        val missing = runCatching {
                            gson.fromJson(response.body?.string().orEmpty(), com.google.gson.JsonObject::class.java)
                                ?.getAsJsonArray("missing")?.map { it.asString }
                        }.getOrNull().orEmpty()
                        MixWriteResult.MissingPrograms(missing)
                    }
                    503 -> MixWriteResult.CompileBusy
                    else -> MixWriteResult.NetworkError
                }
            }
        }.getOrDefault(MixWriteResult.NetworkError)
    }
}
