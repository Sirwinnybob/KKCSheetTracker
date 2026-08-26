package com.kkc.sheettracker.data.mixservice

data class ManageCodeChange(
    val orderOrMembershipChanged: Boolean,
    val programs: List<String>,
    val editRows: List<PgmEditRow>
)

fun buildManageCodeChange(
    rows: List<ManageCodeRow>,
    selections: Map<String, ManageCodeRowSelection>,
    locked: Set<String>,
    originalPrograms: List<String>
): ManageCodeChange {
    val programs = rows.flatMap { row ->
        val selection = selections[row.editablePgm] ?: ManageCodeRowSelection()
        if (row.editablePgm in locked || !selection.mix) emptyList() else row.pgmFiles
    }
    val editRows = rows.mapNotNull { row ->
        if (row.editablePgm in locked) return@mapNotNull null
        val selection = selections[row.editablePgm] ?: return@mapNotNull null
        if (!selection.removePUnload && !selection.secondPass) return@mapNotNull null
        PgmEditRow(
            name = row.editablePgm,
            secondPass = if (selection.superPass) "super" else if (selection.secondPass) "standard" else "none",
            removePUnload = selection.removePUnload
        )
    }
    return ManageCodeChange(
        orderOrMembershipChanged = programs != originalPrograms,
        programs = programs,
        editRows = editRows
    )
}

data class DuplicateMixWarning(val pgm: String, val otherMixName: String)

sealed interface MixGenerationTarget {
    data object FirstDefault : MixGenerationTarget
    data class CreateAdditional(val name: String) : MixGenerationTarget
    data class ReplaceActive(val name: String, val expectedRevision: Long) : MixGenerationTarget
}

data class MixGenerationPlan(
    val name: String,
    val programsBaseline: List<String>,
    val expectedRevision: Long,
    val mutation: MixCatalogMutation
)

data class ReboundManageCodeState(
    val rows: List<ManageCodeRow>,
    val selections: Map<String, ManageCodeRowSelection>
)

/** Rebinds a multi-active material to the operator-selected active mix before generation. */
fun rebindManageCodeForActiveMix(
    rows: List<ManageCodeRow>,
    selections: Map<String, ManageCodeRowSelection>,
    programs: List<String>
): ReboundManageCodeState {
    val orderedRows = applyExistingOrder(rows, programs)
    return ReboundManageCodeState(
        rows = orderedRows,
        selections = orderedRows.associate { row ->
            val existing = selections[row.editablePgm] ?: ManageCodeRowSelection()
            row.editablePgm to existing.copy(mix = row.editablePgm in programs)
        }
    )
}

enum class MixCatalogMutation { CREATE, REPLACE }

/**
 * Resolves an operator's intent against one immutable catalog revision. A null plan means the
 * target is stale, invalid, or unsafe to mutate. In particular, external files must be resolved
 * before the service-owned catalog can be changed.
 */
fun resolveMixGenerationTarget(
    target: MixGenerationTarget,
    catalog: MixCatalogSnapshot,
    materialName: String
): MixGenerationPlan? {
    val active = catalog.entries.filter { it.lifecycle == MixLifecycle.ACTIVE }
    if (catalog.entries.any { it.lifecycle == MixLifecycle.EXTERNAL }) return null
    return when (target) {
        MixGenerationTarget.FirstDefault -> {
            val name = defaultMixName(materialName)
            if (active.isNotEmpty() || !isAvailableMixName(name, catalog)) null
            else MixGenerationPlan(name, emptyList(), catalog.revision, MixCatalogMutation.CREATE)
        }
        is MixGenerationTarget.CreateAdditional -> {
            val name = target.name
            if (!isAvailableMixName(name, catalog)) null
            else MixGenerationPlan(name, emptyList(), catalog.revision, MixCatalogMutation.CREATE)
        }
        is MixGenerationTarget.ReplaceActive -> {
            if (target.expectedRevision != catalog.revision) null
            else active.firstOrNull { it.name == target.name }?.let { entry ->
                MixGenerationPlan(entry.name, entry.programs, catalog.revision, MixCatalogMutation.REPLACE)
            }
        }
    }
}

private fun isAvailableMixName(name: String, catalog: MixCatalogSnapshot): Boolean =
    isValidMixName(name) && catalog.entries.none { it.name.equals(name, ignoreCase = true) }

private val windowsReservedMixNames = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
)

/** Matches the service's safe-name validator; service validation remains authoritative. */
fun isValidMixName(name: String): Boolean {
    return name == name.trim() &&
        name.matches(Regex("[A-Za-z0-9 _—()\\-]+")) &&
        name.uppercase() !in windowsReservedMixNames
}

fun defaultMixName(materialName: String): String =
    materialName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Mix" } + "Mix"

fun findCrossMixDuplicates(
    programs: List<String>,
    thisMixName: String,
    otherMixes: List<MixDefinition>
): List<DuplicateMixWarning> = programs.mapNotNull { pgm ->
    otherMixes.firstOrNull { it.name != thisMixName && pgm in it.programs }
        ?.let { owner -> DuplicateMixWarning(pgm, owner.name) }
}

/** Archived definitions are recovery records, not production ownership claims. */
fun findCrossMixDuplicates(
    programs: List<String>,
    thisMixName: String,
    catalog: MixCatalogSnapshot
): List<DuplicateMixWarning> = findCrossMixDuplicates(
    programs = programs,
    thisMixName = thisMixName,
    otherMixes = catalog.entries
        .filter { it.lifecycle == MixLifecycle.ACTIVE }
        .map { MixDefinition(name = it.name, programs = it.programs) }
)
