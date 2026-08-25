package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

sealed class MixWriteResult {
    data class Success(val definition: MixDefinition) : MixWriteResult()
    data class DuplicateName(val name: String) : MixWriteResult()
    object UnknownJobOrMaterial : MixWriteResult()
    data class MissingProgram(val pgm: String) : MixWriteResult()
    data class BadRequest(val message: String) : MixWriteResult()
    object CompileBusy : MixWriteResult()
    object WinxisoTimeout : MixWriteResult()
    object NetworkError : MixWriteResult()
}

class MixServiceClient(private val baseUrl: String = "http://192.168.20.4:8477") {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val root: String get() = baseUrl.trimEnd('/')

    private data class PgmListEnvelope(val ok: Boolean = false, val pgms: List<PgmInventoryItem> = emptyList())
    private data class MixListEnvelope(val ok: Boolean = false, val mixes: List<MixDefinition> = emptyList())
    private data class MixWriteEnvelope(val ok: Boolean = false, val mix: MixDefinition? = null, val status: String? = null)
    private data class ErrorEnvelope(val ok: Boolean = false, val code: String? = null, val error: String? = null)

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$root/status".toHttpUrl()).get().build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun listPgms(job: String, material: String): List<PgmInventoryItem> = withContext(Dispatchers.IO) {
        val url = "$root/jobs/".toHttpUrl().newBuilder()
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
                gson.fromJson(body, PgmListEnvelope::class.java)?.pgms.orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun listMixes(job: String, material: String): List<MixDefinition>? = withContext(Dispatchers.IO) {
        val url = "$root/mixes".toHttpUrl().newBuilder()
            .addQueryParameter("job", job)
            .addQueryParameter("material", material)
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                gson.fromJson(body, MixListEnvelope::class.java)?.mixes
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
                    200 -> {
                        val responseBody = response.body?.string() ?: return@use MixWriteResult.NetworkError
                        val envelope = gson.fromJson(responseBody, MixWriteEnvelope::class.java)
                        val definition = envelope?.mix ?: return@use MixWriteResult.NetworkError
                        MixWriteResult.Success(definition.copy(status = envelope.status ?: definition.status))
                    }
                    404 -> MixWriteResult.UnknownJobOrMaterial
                    409 -> MixWriteResult.DuplicateName(name)
                    422 -> {
                        val message = errorMessage(response)
                        val prefix = "missing program: "
                        if (message.startsWith(prefix)) MixWriteResult.MissingProgram(message.removePrefix(prefix))
                        else MixWriteResult.BadRequest(message)
                    }
                    400 -> MixWriteResult.BadRequest(errorMessage(response))
                    503 -> MixWriteResult.CompileBusy
                    504 -> MixWriteResult.WinxisoTimeout
                    else -> MixWriteResult.NetworkError
                }
            }
        }.getOrDefault(MixWriteResult.NetworkError)
    }

    private fun errorMessage(response: Response): String =
        runCatching { gson.fromJson(response.body?.string().orEmpty(), ErrorEnvelope::class.java)?.error }
            .getOrNull().orEmpty()
}
