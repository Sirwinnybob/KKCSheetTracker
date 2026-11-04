package com.kkc.sheettracker.data.cnc

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Mix service `/status`. Old services omit identity: [machineId] and [capabilities] are then null. */
data class MixServiceStatus(
    val features: Set<String> = emptySet(),
    val machineId: String? = null,
    val machineName: String? = null,
    val capabilities: Set<String>? = null,
    val readOnly: Boolean = false,
)

/** Present fields with the wrong type fail closed (blank identity, no capabilities, read-only). */
fun parseMixServiceStatus(body: String): MixServiceStatus? = try {
    val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    if (root == null || root.bool("ok") != true) null
    else MixServiceStatus(
        features = root.strings("features").orEmpty(),
        machineId = root.optionalString("machineId"),
        machineName = root.optionalString("machineName")?.takeIf { it.isNotBlank() },
        capabilities = if (root.has("capabilities")) root.strings("capabilities").orEmpty() else null,
        readOnly = root.has("readOnly") && root.bool("readOnly") != false,
    )
} catch (_: RuntimeException) { null }

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.optionalString(key: String): String? = if (!has(key) || get(key).isJsonNull) null else get(key).stringOrNull().orEmpty()
private fun JsonObject.strings(key: String): Set<String>? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.stringOrNull() }?.toSet()

data class MachineAccess(
    val mixFiles: Boolean,
    val frz: Boolean,
    val secondPass: Boolean,
    /** Moves need an explicit MOVE_MATERIAL advertisement; legacy services without capabilities never move. */
    val moveMaterial: Boolean = false,
    /** Operator copy when the whole service is refused; null when only individual capabilities are absent. */
    val reason: String? = null,
    val code: String? = null,
)

/** Null when the service at this machine's address is the expected one (or a legacy Red service). */
fun machineIdentityRefusal(machine: CncMachine, status: MixServiceStatus): Pair<String, String>? = when {
    status.machineId == null -> if (machine.identityOptional) null else "machine_unverified" to
        "${machine.displayName}'s mix service didn't identify itself. Editing is off until the service is updated."
    status.machineId != machine.id -> "machine_mismatch" to
        "Expected ${machine.displayName}, but the mix service at this address is ${status.machineName ?: status.machineId.ifBlank { "unidentified" }}. Editing is off."
    else -> null
}

/**
 * Effective actions for [machine]: local capabilities, narrowed by advertised ones, and refused entirely
 * on an identity mismatch or read-only service. [requireStatus] is set at the mutation boundary, where an
 * unknown status refuses; screens leave it unset so a legacy Red service keeps today's behaviour.
 */
fun machineAccess(machine: CncMachine, status: MixServiceStatus?, requireStatus: Boolean = false): MachineAccess {
    val refusal = when {
        status == null -> if (requireStatus || !machine.identityOptional) "machine_unverified" to
            "Can't confirm ${machine.displayName}'s mix service. Refresh to try again." else null
        else -> machineIdentityRefusal(machine, status) ?: if (status.readOnly) "machine_read_only" to
            "${machine.displayName}'s mix service is read-only. Editing is off." else null
    }
    if (refusal != null) return MachineAccess(false, false, false, reason = refusal.second, code = refusal.first)
    fun allowed(capability: CncCapability) = capability in machine.capabilities &&
        (status?.capabilities == null || capability.name in status.capabilities)
    val mixFiles = allowed(CncCapability.MIX_FILES)
    val move = mixFiles && CncCapability.MOVE_MATERIAL in machine.capabilities &&
        status?.capabilities?.contains(CncCapability.MOVE_MATERIAL.name) == true
    return MachineAccess(mixFiles, mixFiles && allowed(CncCapability.FRZ_EDITING), allowed(CncCapability.SECOND_PASS), move)
}
