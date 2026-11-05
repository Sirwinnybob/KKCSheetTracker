package com.kkc.sheettracker.data.cnc

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.mixservice.MIX_SERVICE_BASE_URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val machineHttpClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

/** Independent read-only consumer: never shares mix-operation timeouts or mutation methods. */
class MachineStateClient(
    private val baseUrl: String = MIX_SERVICE_BASE_URL,
    private val client: OkHttpClient = machineHttpClient,
) : MachineStateSource {
    override suspend fun fetch(): Result<MachineState> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/machine/state").get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        check(it.code == 200) { "Machine response HTTP ${it.code}" }
                        Result.success(parseMachineState(it.body?.string().orEmpty()))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Result.failure(e)
                }
                continuation.resume(result)
            }
        })
    }
}

private fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.text(key: String): String? = get(key)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isString
}?.asString?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.boolean(key: String): Boolean? = get(key)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
}?.asBoolean

private fun parseMachineState(body: String): MachineState {
    val root = JsonParser.parseString(body).objectOrNull()
    require(root?.boolean("ok") == true) { "Invalid machine snapshot" }
    val loaded = root.get("currentProgram").objectOrNull()?.let { program ->
        program.text("name")?.let { name ->
            val candidates = program.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { element ->
                    element.objectOrNull()?.let {
                        val path = it.text("path")
                        val folder = it.text("jobFolder")
                        if (path == null && folder == null) null else ProgramCandidate(path, folder)
                    }
                }.orEmpty()
            LoadedProgram(name, program.boolean("running"), program.text("loadedAt"),
                program.text("path"), program.text("jobFolder"),
                program.boolean("ambiguous") == true, candidates)
        }
    }
    val finished = root.get("lastProgram").objectOrNull()?.let { program ->
        program.text("name")?.let { FinishedProgram(it, program.text("path"), program.text("start"), program.text("end")) }
    }
    val maestroLoaded = root.get("maestroLoadedPrograms")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }
    return MachineState(root.text("asOf"), root.text("reportDate"), root.boolean("haveReport"),
        root.text("state"), if (root.has("displayProducing")) root.boolean("displayProducing") else root.boolean("producing"), loaded, finished,
        maestroLoaded)
}

