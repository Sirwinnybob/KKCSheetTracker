package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest

data class PgmRevertRow(val name: String, val expectedSha256: String? = null)
data class PgmPreviewRow(val name: String, val secondPass: String, val removePUnload: Boolean,
    val expectedSha256: String? = null, val restoreOriginal: Boolean = false)
data class PgmPreviewRequest(val requestId: String, val files: List<PgmPreviewRow>)
data class PgmPreviewIntent(val mode: String, val removePUnload: Boolean, val restoreOriginal: Boolean)
data class PgmNotice(val code: String, val message: String)
data class PgmFallbackReason(val stage: String, val code: String, val message: String)
data class PgmConversion(val decode: String, val encode: String, val fallbackReasons: List<PgmFallbackReason>)
data class PgmPreviewFile(val name: String, val originalSha256: String, val baseSha256: String,
    val proposedSourceSha256: String, val currentSource: String, val proposedSource: String,
    val diff: String, val requested: PgmPreviewIntent, val before: PgmEditCurrentState,
    val proposed: PgmEditCurrentState, val conversion: PgmConversion, val notices: List<PgmNotice>)
data class PgmPreviewResult(val ok: Boolean, val file: PgmPreviewFile)

fun validPgmHash(value: String?): Boolean = value != null && value.matches(Regex("[0-9a-f]{64}"))

/** Validate primitives before Gson can coerce a number/string or fill an omitted flag. */
fun decodePgmPreview(result: Any?): PgmPreviewResult {
    val gson = Gson()
    val encoded = gson.toJson(result)
    check(encoded.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Preview is too large; Apply remains available" }
    fun JsonObject.text(key: String): String {
        val field = get(key)
        check(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) { "Invalid preview field: $key" }
        return field.asString
    }
    fun JsonObject.flag(key: String): Boolean {
        val field = get(key)
        check(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isBoolean) { "Invalid preview flag: $key" }
        return field.asBoolean
    }
    val root = gson.toJsonTree(result).also { check(it.isJsonObject) { "Invalid preview response" } }.asJsonObject
    check(root.flag("ok") && root.text("operation") == "preview") { "Preview failed" }
    val file = root.getAsJsonObject("file") ?: error("Preview file is missing")
    check(file.text("name").endsWith(".pgm", true)) { "Invalid preview program" }
    listOf("originalSha256", "baseSha256", "proposedSourceSha256").forEach { check(validPgmHash(file.text(it))) { "Invalid preview revision" } }
    val source = file.text("proposedSource")
    check(source.all { it.code <= 255 } && file.text("currentSource").all { it.code <= 255 }) { "Preview source is not Latin-1" }
    file.text("diff")
    val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    check(hash == file.text("proposedSourceSha256")) { "Preview source hash does not match" }
    for (key in listOf("before", "proposed")) {
        val state = file.getAsJsonObject(key) ?: error("Preview state is missing")
        check(state.text("mode") in setOf("none", "standard", "super")) { "Invalid preview state" }
        state.flag("punloadRemoved")
    }
    val wanted = file.getAsJsonObject("requested") ?: error("Preview intent is missing")
    check(wanted.text("mode") in setOf("none", "standard", "super"))
    wanted.flag("removePUnload"); wanted.flag("restoreOriginal")
    val conversion = file.getAsJsonObject("conversion") ?: error("Preview conversion is missing")
    check(conversion.text("decode") in setOf("rust", "scm"))
    check(conversion.text("encode") in setOf("rust", "scm", "none", "not_needed"))
    val reasons = conversion.getAsJsonArray("fallbackReasons") ?: error("Preview fallback details are missing")
    val notices = file.getAsJsonArray("notices") ?: error("Preview notices are missing")
    check(reasons.size() <= 3 && notices.size() <= 32)
    (reasons.toList() + notices.toList()).forEach { value ->
        check(value.isJsonObject)
        check(value.asJsonObject.text("code").length in 1..80 && value.asJsonObject.text("message").length <= 1024)
    }
    reasons.forEach { check(it.asJsonObject.text("stage") in setOf("decode", "encode", "validation_decode")) }
    return gson.fromJson(root, PgmPreviewResult::class.java)
}
