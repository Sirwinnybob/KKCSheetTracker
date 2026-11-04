package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.cnc.CncCapability
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.CncMachines

/** A machine holds a material when its folder is known there; an offline machine counts only with a cached catalog. */
fun catalogHoldsMaterial(state: CatalogState?): Boolean = when (state) {
    is CatalogState.Live, is CatalogState.Outdated -> true
    is CatalogState.Offline -> state.cached != null
    CatalogState.Loading, CatalogState.NoFolder, null -> false
}

val CncMachine.supportsFrz: Boolean get() = CncCapability.FRZ_EDITING in capabilities

/** Which machine supplies a material's mix context. One machine's catalog is never mixed with another's. */
sealed interface MaterialOwnership {
    /** [explicit] when the caller (navigation or operator choice) named the machine. */
    data class Single(val machine: CncMachine, val explicit: Boolean = false) : MaterialOwnership
    /** The folder is on several machines and nothing chose one: show each separately or ask. */
    data class Ambiguous(val machines: List<CncMachine>) : MaterialOwnership
}

/**
 * Resolves a (job, material) owner from the machine-keyed catalog states. An explicit, known machine wins.
 * Otherwise a material held only by Blue SCM belongs to Blue; held by both is ambiguous; anything else stays on Red.
 */
fun resolveMaterialOwnership(
    statesByMachine: Map<String, CatalogState?>,
    explicitMachineId: String? = null,
    machines: List<CncMachine> = CncMachines.mixMachines,
): MaterialOwnership {
    machines.firstOrNull { it.id == explicitMachineId }?.let { return MaterialOwnership.Single(it, explicit = true) }
    val holders = machines.filter { catalogHoldsMaterial(statesByMachine[it.id]) }
    return when {
        holders.size > 1 -> MaterialOwnership.Ambiguous(holders)
        holders.size == 1 -> MaterialOwnership.Single(holders.single())
        else -> MaterialOwnership.Single(CncMachines.scm)
    }
}

/** True once every machine has answered (or failed) for this material, so a resolved owner won't flip on load. */
fun materialOwnershipSettled(statesByMachine: Map<String, CatalogState?>, machines: List<CncMachine> = CncMachines.mixMachines): Boolean =
    machines.all { statesByMachine[it.id].let { state -> state != null && state != CatalogState.Loading } }
