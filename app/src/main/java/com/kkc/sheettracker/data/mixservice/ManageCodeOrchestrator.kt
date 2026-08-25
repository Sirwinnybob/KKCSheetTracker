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

fun findCrossMixDuplicates(
    programs: List<String>,
    thisMixName: String,
    otherMixes: List<MixDefinition>
): List<DuplicateMixWarning> = programs.mapNotNull { pgm ->
    otherMixes.firstOrNull { it.name != thisMixName && pgm in it.programs }
        ?.let { owner -> DuplicateMixWarning(pgm, owner.name) }
}
