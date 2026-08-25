package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.SheetStatus

fun isRowLocked(status: SheetStatus): Boolean =
    status == SheetStatus.COMPLETE || status == SheetStatus.RE_NESTED

data class ManageCodeRowSelection(
    val mix: Boolean = false,
    val removePUnload: Boolean = false,
    val secondPass: Boolean = false,
    val superPass: Boolean = false
)

fun deriveRowSelection(
    editablePgm: String,
    mixPrograms: List<String>,
    hasExistingMix: Boolean,
    editHistory: PgmEditHistoryView?
): ManageCodeRowSelection {
    val current = editHistory?.files?.get(editablePgm)?.current
    val mode = current?.mode ?: "none"
    return ManageCodeRowSelection(
        mix = if (hasExistingMix) mixPrograms.contains(editablePgm) else true,
        removePUnload = current?.punloadRemoved ?: false,
        secondPass = mode == "standard" || mode == "super",
        superPass = mode == "super"
    )
}

fun toggleSecondPass(selection: ManageCodeRowSelection, checked: Boolean): ManageCodeRowSelection =
    selection.copy(secondPass = checked, superPass = if (checked) selection.superPass else false)

fun toggleSuperPass(selection: ManageCodeRowSelection, checked: Boolean): ManageCodeRowSelection =
    selection.copy(superPass = checked, secondPass = if (checked) true else selection.secondPass)
