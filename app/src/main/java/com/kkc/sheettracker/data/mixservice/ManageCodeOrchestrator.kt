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
    data class ReplaceActive(
        val name: String,
        val expectedRevision: Long,
        val programsBaseline: List<String>
    ) : MixGenerationTarget
}

data class MixGenerationPlan(
    val name: String,
    val programsBaseline: List<String>,
    val expectedRevision: Long,
    val mutation: MixCatalogMutation
)

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
            if (active.isNotEmpty() || !isCatalogMixNameAvailable(name, catalog)) null
            else MixGenerationPlan(name, emptyList(), catalog.revision, MixCatalogMutation.CREATE)
        }
        is MixGenerationTarget.CreateAdditional -> {
            val name = target.name
            if (!isCatalogMixNameAvailable(name, catalog)) null
            else MixGenerationPlan(name, emptyList(), catalog.revision, MixCatalogMutation.CREATE)
        }
        is MixGenerationTarget.ReplaceActive -> {
            if (target.expectedRevision != catalog.revision) null
            else active.firstOrNull { it.name == target.name }?.let { entry ->
                if (entry.programs != target.programsBaseline) null
                else MixGenerationPlan(entry.name, target.programsBaseline, catalog.revision, MixCatalogMutation.REPLACE)
            }
        }
    }
}

fun isCatalogMixNameAvailable(name: String, catalog: MixCatalogSnapshot): Boolean =
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

/**
 * Converts one resolved catalog intent into the durable actions consumed by
 * [MixOperationCoordinator]. Catalog replacement/creation is deliberately first so PGM edits
 * cannot be submitted against a catalog that has not accepted the new membership.
 */
fun buildManageCodeActions(
    job: String,
    material: String,
    plan: MixGenerationPlan,
    change: ManageCodeChange,
    requestId: String = ""
): List<ManageCodeOperationAction> = buildList {
    if (change.orderOrMembershipChanged) {
        add(
            when (plan.mutation) {
                MixCatalogMutation.CREATE -> ManageCodeOperationAction.catalogCreate(
                    job = job,
                    material = material,
                    name = plan.name,
                    programs = change.programs,
                    expectedRevision = plan.expectedRevision,
                )
                MixCatalogMutation.REPLACE -> ManageCodeOperationAction.catalogReplace(
                    job = job,
                    material = material,
                    name = plan.name,
                    programs = change.programs,
                    expectedRevision = plan.expectedRevision,
                )
            }
        )
    }
    if (change.editRows.isNotEmpty()) {
        add(ManageCodeOperationAction.pgmEdits(material, requestId, change.editRows))
    }
}

/** Plans a permanent delete for exactly one still-external catalog filename. */
fun buildExternalDeleteAction(
    job: String,
    material: String,
    catalog: MixCatalogSnapshot,
    filename: String
): ManageCodeOperationAction? = catalog.entries.firstOrNull {
    it.lifecycle == MixLifecycle.EXTERNAL && it.mixFilename == filename
}?.let {
    ManageCodeOperationAction.externalDelete(
        job = job,
        material = material,
        externalMixFilename = it.mixFilename,
        expectedRevision = catalog.revision,
    )
}

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
