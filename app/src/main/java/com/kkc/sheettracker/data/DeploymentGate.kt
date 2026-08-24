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
        // No gate file means the folder has not been approved by Ready Jobs Watcher yet.
        // Treat it as hidden so unrecognized or in-progress folders never surface on tablets.
        val gate = loadGate(gateFile) ?: return DeploymentGateDecision(
            includeJob = false,
            hiddenFromProduction = false
        )

        val hiddenFromProduction = bool(gate, "hiddenFromProduction", "hidden_from_production")
        val deployed = boolOrNull(gate, "deployed") ?: true
        // The live Ready Jobs worker treats `deployed` as the sole processing gate.
        // `parseReady` is reparse progress, not a production-visibility decision.
        if (!deployed) {
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

}
