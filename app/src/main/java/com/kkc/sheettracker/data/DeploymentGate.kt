package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.util.Locale

internal data class DeploymentGateDecision(
    val includeJob: Boolean,
    val hiddenFromProduction: Boolean
)

internal object DeploymentGateRules {
    private val gson = Gson()

    fun evaluate(jobDir: File, isDebugBuild: Boolean): DeploymentGateDecision {
        val gateFile = File(jobDir, ".metadata/deployment_gate.json")
        val gate = loadGate(gateFile) ?: return DeploymentGateDecision(
            includeJob = true,
            hiddenFromProduction = false
        )

        val hiddenFromProduction = bool(gate, "hiddenFromProduction", "hidden_from_production")
        val deployed = boolOrNull(gate, "deployed") ?: true
        val pending = bool(gate, "pending", "isPending")
        val notParseReady = bool(gate, "notParseReady", "not_parse_ready")
        val parseReady = boolOrNull(gate, "parseReady", "parse_ready")
        val status = stringOrNull(gate, "status", "state")?.lowercase(Locale.US)
        val statusBlocks = status in setOf(
            "pending",
            "not_ready",
            "not-ready",
            "not_parse_ready",
            "not-parse-ready",
            "parse_pending"
        )

        val blockedByReadiness = (!deployed) || pending || notParseReady || (parseReady == false) || statusBlocks
        if (blockedByReadiness) {
            return DeploymentGateDecision(
                includeJob = false,
                hiddenFromProduction = false
            )
        }

        if (hiddenFromProduction && !isDebugBuild) {
            return DeploymentGateDecision(
                includeJob = false,
                hiddenFromProduction = false
            )
        }

        return DeploymentGateDecision(
            includeJob = true,
            hiddenFromProduction = hiddenFromProduction && isDebugBuild
        )
    }

    private fun loadGate(file: File): JsonObject? {
        if (!file.exists() || !file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), JsonObject::class.java) }.getOrNull()
    }

    private fun bool(root: JsonObject, vararg keys: String): Boolean {
        return keys.firstNotNullOfOrNull { key -> boolValue(root.get(key)) } ?: false
    }

    private fun boolOrNull(root: JsonObject, vararg keys: String): Boolean? {
        return keys.firstNotNullOfOrNull { key -> boolValue(root.get(key)) }
    }

    private fun boolValue(element: JsonElement?): Boolean? {
        if (element == null || element.isJsonNull) return null
        return runCatching {
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    when (element.asString.trim().lowercase(Locale.US)) {
                        "true", "1", "yes", "y" -> true
                        "false", "0", "no", "n" -> false
                        else -> null
                    }
                }
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt != 0
                else -> null
            }
        }.getOrNull()
    }

    private fun stringOrNull(root: JsonObject, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            val value = root.get(key) ?: return@firstNotNullOfOrNull null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return@firstNotNullOfOrNull null
            value.asString
        }
    }
}
