package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.mixservice.MIX_SERVICE_BASE_URL

enum class CncCapability { MIX_FILES, FRZ_EDITING, SECOND_PASS, MOVE_MATERIAL }

data class CncMachine(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: Set<CncCapability>,
    /** Only the original service may omit `/status` identity; every other machine must name itself. */
    val identityOptional: Boolean = false,
    /** The service removes PUNLOAD on every second-pass edit; the tablet shows it as fixed and always sends it. */
    val forcesPUnloadRemoval: Boolean = false,
)

object CncMachines {
    val scm = CncMachine(
        id = "scm",
        displayName = "Red SCM",
        baseUrl = MIX_SERVICE_BASE_URL,
        // MOVE_MATERIAL is tablet support only; a move also needs both services to advertise it.
        capabilities = setOf(CncCapability.MIX_FILES, CncCapability.FRZ_EDITING, CncCapability.SECOND_PASS, CncCapability.MOVE_MATERIAL),
        identityOptional = true,
    )
    /** Blue SCM has no FRZ support; never add FRZ_EDITING here. */
    val scmNew = CncMachine(
        id = "scm-new",
        displayName = "Blue SCM",
        baseUrl = "http://192.168.20.101:8477",
        capabilities = setOf(CncCapability.MIX_FILES, CncCapability.SECOND_PASS, CncCapability.MOVE_MATERIAL),
        forcesPUnloadRemoval = true,
    )
    val all: List<CncMachine> = listOf(scm, scmNew)
    val mixMachines: List<CncMachine> get() = all.filter { CncCapability.MIX_FILES in it.capabilities }

    /** Restored or deep-linked IDs that are unknown or cannot browse mixes fall back to Red. */
    fun mixMachine(id: String?): CncMachine = mixMachines.firstOrNull { it.id == id } ?: scm
}
