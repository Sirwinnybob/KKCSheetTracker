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
    val expectedRevision: Long
)

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
            if (active.isNotEmpty()) null
            else MixGenerationPlan(defaultMixName(materialName), emptyList(), catalog.revision)
        }
        is MixGenerationTarget.CreateAdditional -> {
            if (!isValidMixName(target.name) || active.any { it.name.equals(target.name, ignoreCase = true) }) null
            else MixGenerationPlan(target.name, emptyList(), catalog.revision)
        }
        is MixGenerationTarget.ReplaceActive -> {
            if (target.expectedRevision != catalog.revision) null
            else active.firstOrNull { it.name == target.name }?.let { entry ->
                MixGenerationPlan(entry.name, entry.programs, catalog.revision)
            }
        }
    }
}

fun isValidMixName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9 _-]{1,80}"))

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
