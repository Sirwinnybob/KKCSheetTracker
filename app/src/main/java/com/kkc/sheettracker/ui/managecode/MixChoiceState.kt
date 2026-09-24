package com.kkc.sheettracker.ui.managecode

import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.applyExistingOrder

internal sealed interface MixCheckOutcome {
    /** Apply the MIX change to these rows immediately. */
    data class Apply(val pgms: Set<String>, val checked: Boolean) : MixCheckOutcome
    /** Ask Replace / Additional / Cancel before checking [pgms]. */
    data class Prompt(val pgms: Set<String>) : MixCheckOutcome
}

/** The prompt fires once per material: on the first MIX check while an active mix exists. */
internal fun mixCheckOutcome(
    state: ManageCodeMaterialState,
    pgms: Set<String>,
    checked: Boolean,
    hasChoice: Boolean,
): MixCheckOutcome =
    if (checked && state.activeMixes.isNotEmpty() && !hasChoice) MixCheckOutcome.Prompt(pgms)
    else MixCheckOutcome.Apply(pgms, checked)

internal fun applyMixChecks(state: ManageCodeMaterialState, pgms: Set<String>, checked: Boolean): ManageCodeMaterialState =
    updateMixLayoutSelections(
        state,
        state.selections.mapValues { (pgm, selection) ->
            if (pgm in pgms && pgm !in state.locked) selection.copy(mix = checked) else selection
        },
    )

/**
 * A mix's program baseline is built from every pgmFile of each checked row (see
 * buildManageCodeChange), not just its editablePgm — a 2-file row (e.g. R2A.pgm + R2Z.pgm,
 * editable R2Z.pgm) can appear in the baseline via either stem. So membership must be checked
 * against the whole row, not just editablePgm.
 */
private fun rowInBaseline(row: ManageCodeRow, baseline: Set<String>): Boolean =
    row.pgmFiles.any { it in baseline } || row.editablePgm in baseline

/** Replace: revise the chosen mix — its members (never locked sheets) plus what was tapped. */
internal fun applyReplaceChoice(
    state: ManageCodeMaterialState,
    target: MixGenerationTarget.ReplaceActive,
    tapped: Set<String>,
): ManageCodeMaterialState {
    val baseline = target.programsBaseline.toSet()
    val reordered = updateMixLayoutRows(state, applyExistingOrder(state.rows, target.programsBaseline))
    val memberPgms = reordered.rows.filter { rowInBaseline(it, baseline) }.map { it.editablePgm }.toSet()
    val include = (memberPgms + tapped) - state.locked
    return updateMixLayoutSelections(
        reordered,
        reordered.selections.mapValues { (pgm, selection) -> selection.copy(mix = pgm in include) },
    ).copy(mixLayoutDirty = true)
}

/** Additional: a new mix starting from only what was tapped. */
internal fun applyAdditionalChoice(state: ManageCodeMaterialState, tapped: Set<String>): ManageCodeMaterialState =
    applyMixChecks(state, tapped, checked = true).copy(mixLayoutDirty = true)

internal fun shouldClearMixChoice(state: ManageCodeMaterialState): Boolean =
    state.selections.none { (pgm, selection) -> selection.mix && pgm !in state.locked }

internal fun mixMembershipByPgm(activeMixes: List<MixCatalogEntry>): Map<String, String> =
    activeMixes.flatMap { entry -> entry.programs.map { it to entry.name } }.toMap()
