# Mix + Second Pass Queue and Existing-Mix Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Manage Code submission creates/replaces a mix and then applies the 2ND/SUP/PUNLOAD edits; materials with an existing mix show it, start with MIX unchecked, and prompt Replace / Additional / Cancel on first MIX check.

**Architecture:** The durable `ManageCodeSession` queue (catalog action → `pgm_edits`) already exists. The queue stalls because the catalog step's result fails to parse (Gson `Any?` turns `revision` into a `Double`, re-serialized as `2.933316141E9`, which `longValue` rejects) so the completed mix is recorded as a failure and the edit step never runs. Fix the parser, then add pure planning/selection functions (unit-tested) and wire them into `ManageCodeScreen`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Gson, kotlinx.coroutines, JUnit4. Spec: `docs/superpowers/specs/2026-09-24-mix-and-second-pass-queue-design.md`.

**Commands** (run from `C:\Scripts\KKCSheetTracker`, PowerShell):
- Single test class: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest"`
- All mix tests: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.*" --tests "com.kkc.sheettracker.ui.managecode.*"`
- Known env-only failure: one PdfMarkup MotionEvent unit test fails off-device; ignore it.

**Working tree note:** `KKCSlidingPill.kt` and `SheetViewerScreen.kt` have unrelated uncommitted edits. Never `git add -A`; add only the files each task lists.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt` | Modify `longValue` | Accept integral numbers written in double/exponent form |
| `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelection.kt` | Modify `deriveRowSelection` | MIX unchecked when a mix already exists |
| `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt` | Add `MaterialSubmission`, `planMaterialSubmission`, `nextAdditionalMixName`; make `buildManageCodeActions` accept nullable plan | Pure per-material submission planning |
| `app/src/main/java/com/kkc/sheettracker/ui/managecode/MixChoiceState.kt` | Create | Pure prompt/selection reducers for the first-MIX-check prompt |
| `app/src/main/java/com/kkc/sheettracker/ui/managecode/ExistingMixChoiceDialog.kt` | Create | Replace / Additional / Cancel dialog |
| `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt` | Modify | `activeMixes` in state, header + row tags, prompt wiring, `planMaterialSubmission` in `buildSession`, step list |
| Tests under `app/src/test/java/com/kkc/sheettracker/...` | Modify/Create | One per unit above |

---

### Task 1: Fix catalog mutation result parsing (root cause of dropped second pass)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt:139-143`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogCacheTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinatorTest.kt`

- [ ] **Step 1: Write failing parser test** — append to `MixCatalogCacheTest`:

```kotlin
    @Test
    fun `parseMutationResult accepts a result that Gson decoded into Any with double numbers`() {
        // Mirrors MixServiceClient: operation.result is Any?, so Gson yields maps of Double.
        val wire = """
            {"ok": true, "catalog": {"revision": 2933316141, "entries": [
              {"name": "19mm Pre_FinishedMix", "mixFilename": "19mm Pre_FinishedMix.mix",
               "lifecycle": "active", "programs": ["R1.pgm"], "status": "compiled",
               "lastCompileOk": true, "compiledProgramMtimes": [1789662729769373900]}
            ]}}
        """.trimIndent()
        val decoded: Any? = Gson().fromJson(wire, Any::class.java)

        val snapshot = MixCatalogJson.parseMutationResult(decoded, "592 - GIELISH", "19mm Pre_Finished")

        assertEquals(2933316141L, snapshot?.revision)
        assertEquals(listOf("R1.pgm"), snapshot?.entries?.single()?.programs)
    }

    @Test
    fun `longValue still rejects fractional and non-positive revisions`() {
        val decoded: Any? = Gson().fromJson("""{"ok": true, "catalog": {"revision": 7.5, "entries": []}}""", Any::class.java)
        assertNull(MixCatalogJson.parseMutationResult(decoded, "J", "M"))
        val zero: Any? = Gson().fromJson("""{"ok": true, "catalog": {"revision": 0, "entries": []}}""", Any::class.java)
        assertNull(MixCatalogJson.parseMutationResult(zero, "J", "M"))
    }
```

- [ ] **Step 2: Run, verify first test fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest"`
Expected: `parseMutationResult accepts a result...` FAILS (`expected:<2933316141> but was:<null>`). Second test passes already.

- [ ] **Step 3: Fix `longValue`** in `MixCatalogCache.kt` (inside `object MixCatalogJson`):

```kotlin
    fun JsonObject.longValue(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        // Operation results arrive as Any? and Gson decodes every number as Double, so an
        // integral revision re-serializes as "2.933316141E9". Accept it only if it is exact.
        return runCatching { value.asBigDecimal.longValueExact() }.getOrNull()?.takeIf { it > 0L }
    }
```

- [ ] **Step 4: Run, verify both pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest"`
Expected: PASS.

- [ ] **Step 5: Coordinator regression test** — append to `MixOperationCoordinatorTest`, reusing its existing `CatalogService`, `InMemorySessionStore`, `session(...)` helpers. First read `CatalogService` (search `private class CatalogService` / `class CatalogService`) to see how it builds the completed catalog operation's `result`; add a constructor flag or a sibling fake so the result is passed through `Gson().fromJson(Gson().toJson(result), Any::class.java)` (the same shape `MixServiceClient` produces). Test:

```kotlin
    @Test
    fun `gson-decoded catalog result advances to the queued pgm edits`() = runBlocking {
        val store = InMemorySessionStore()
        val service = CatalogService(gsonRoundTripResults = true)
        val coordinator = MixOperationCoordinator(service, store, pollIntervalMillis = 1)
        val edits = listOf(PgmEditRow(name = "R2.pgm", secondPass = "standard", removePUnload = false))

        coordinator.restore()
        withTimeout(1_000) { coordinator.restoreState.first { it == MixOperationRestoreState.Ready } }
        coordinator.start(
            session(
                actions = listOf(
                    ManageCodeOperationAction.catalogCreate("M", "MMix", listOf("R2.pgm"), 7L),
                    ManageCodeOperationAction.pgmEdits("M", "request", edits),
                )
            )
        )
        withTimeout(1_000) { coordinator.sessions.first { it["648"]?.isCompletedSuccessfully == true } }

        assertEquals(listOf("catalog_create", "pgm_edits"), service.submissionKinds)
    }
```

If `CatalogService` has no `gsonRoundTripResults` parameter, add it (default `false`) and apply the round trip where it sets the completed operation's `result`.

- [ ] **Step 6: Verify regression test fails without Step 3, passes with it**

Run: `git stash push app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt` then `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.MixOperationCoordinatorTest"` — expect the new test to time out/fail. Then `git stash pop` and rerun — expect PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogCacheTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinatorTest.kt
git commit -m "fix(mix): parse catalog operation results decoded as doubles so queued 2nd pass runs"
```

---

### Task 2: MIX starts unchecked when a mix already exists

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelection.kt:15-29`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelectionTest.kt:29-35`

- [ ] **Step 1: Replace the membership test** (lines 29-35) with:

```kotlin
    @Test
    fun `deriveRowSelection leaves MIX unchecked when material already has a mix`() {
        val inMix = deriveRowSelection("R1.pgm", mixPrograms = listOf("R1.pgm"), hasExistingMix = true, editHistory = null)
        val notInMix = deriveRowSelection("R2.pgm", mixPrograms = listOf("R1.pgm"), hasExistingMix = true, editHistory = null)
        assertFalse(inMix.mix)
        assertFalse(notInMix.mix)
    }
```

- [ ] **Step 2: Run, verify fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.ManageCodeSelectionTest"`
Expected: FAIL on `inMix.mix`.

- [ ] **Step 3: Implement** — in `deriveRowSelection`, replace the `mix =` line:

```kotlin
        // Existing mix membership is shown as a tag; MIX means "include in a new/replaced mix".
        mix = !hasExistingMix,
```

`mixPrograms` is now unused in the body; keep the parameter (callers pass it) but prefix a `@Suppress("UNUSED_PARAMETER")` on the function.

- [ ] **Step 4: Run, verify pass** (same command). Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelection.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeSelectionTest.kt
git commit -m "feat(mix): MIX boxes start unchecked when the material already has a mix"
```

---

### Task 3: Pure per-material submission planning (edits-only allowed on existing mix)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt`

- [ ] **Step 1: Write failing tests** — append to `ManageCodeOrchestratorTest` (uses its `rows`, `catalog`, `active`, `external` helpers):

```kotlin
    @Test
    fun `edits only on a material with an active mix needs no mix target`() {
        val selections = mapOf("R1.pgm" to ManageCodeRowSelection(secondPass = true))
        val result = planMaterialSubmission(
            rows = rows, selections = selections, locked = emptySet(),
            catalog = catalog(listOf(active("19mmMix", "R1.pgm"))), materialName = "19mm",
            selectedTarget = null, automaticTarget = null,
        )
        val ready = result as MaterialSubmission.Ready
        assertEquals(null, ready.plan)
        assertEquals(emptyList<String>(), ready.change.programs)
        assertEquals(listOf("R1.pgm"), ready.change.editRows.map { it.name })

        val actions = buildManageCodeActions("100 - Alpha", "19mm", ready.plan, ready.change, "req")
        assertEquals(listOf(ManageCodeOperationAction.PGM_EDITS), actions.map { it.kind })
    }

    @Test
    fun `mix plus second pass on a fresh material queues create then edits`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true, secondPass = true),
            "R2Z.pgm" to ManageCodeRowSelection(mix = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true),
        )
        val result = planMaterialSubmission(
            rows = rows, selections = selections, locked = emptySet(),
            catalog = catalog(emptyList()), materialName = "19mm",
            selectedTarget = null, automaticTarget = MixGenerationTarget.FirstDefault,
        ) as MaterialSubmission.Ready

        val actions = buildManageCodeActions("100 - Alpha", "19mm", result.plan, result.change, "req")
        assertEquals(
            listOf(ManageCodeOperationAction.CATALOG_CREATE, ManageCodeOperationAction.PGM_EDITS),
            actions.map { it.kind },
        )
    }

    @Test
    fun `mix checked on existing-mix material without a chosen target needs a target`() {
        val selections = mapOf("R1.pgm" to ManageCodeRowSelection(mix = true))
        val result = planMaterialSubmission(
            rows = rows, selections = selections, locked = emptySet(),
            catalog = catalog(listOf(active("19mmMix", "R1.pgm"))), materialName = "19mm",
            selectedTarget = null, automaticTarget = null,
        )
        assertEquals(MaterialSubmission.NeedsTarget, result)
    }

    @Test
    fun `stale chosen target reports stale`() {
        val selections = mapOf("R1.pgm" to ManageCodeRowSelection(mix = true))
        val result = planMaterialSubmission(
            rows = rows, selections = selections, locked = emptySet(),
            catalog = catalog(listOf(active("19mmMix", "R1.pgm"))), materialName = "19mm",
            selectedTarget = MixGenerationTarget.ReplaceActive("19mmMix", expectedRevision = 6L, programsBaseline = listOf("R1.pgm")),
            automaticTarget = null,
        )
        assertEquals(MaterialSubmission.StaleTarget, result)
    }

    @Test
    fun `nextAdditionalMixName skips taken names`() {
        val taken = catalog(listOf(active("19mmMix"), active("19mmMix 2")))
        assertEquals("19mmMix 3", nextAdditionalMixName("19mm", taken))
    }
```

- [ ] **Step 2: Run, verify compile failure** (`planMaterialSubmission`, `MaterialSubmission`, `nextAdditionalMixName` unresolved).

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest"`

- [ ] **Step 3: Implement** — in `ManageCodeOrchestrator.kt`:

Change `buildManageCodeActions` signature and guard:

```kotlin
fun buildManageCodeActions(
    job: String,
    material: String,
    plan: MixGenerationPlan?,
    change: ManageCodeChange,
    requestId: String = ""
): List<ManageCodeOperationAction> = buildList {
    if (plan != null && change.orderOrMembershipChanged) {
```

(rest of the body unchanged). Then add below `defaultMixName`:

```kotlin
/** Next free "<Material>Mix N" name for an additional mix, starting at 2. */
fun nextAdditionalMixName(materialName: String, catalog: MixCatalogSnapshot): String {
    val base = defaultMixName(materialName)
    return (2..999).asSequence().map { "$base $it" }.first { isCatalogMixNameAvailable(it, catalog) }
}

sealed interface MaterialSubmission {
    /** MIX is checked but no create/replace choice exists yet — prompt the operator. */
    data object NeedsTarget : MaterialSubmission
    /** The chosen target no longer matches the catalog revision — re-prompt. */
    data object StaleTarget : MaterialSubmission
    /** [plan] is null for an edits-only submission that leaves the existing mix untouched. */
    data class Ready(val plan: MixGenerationPlan?, val change: ManageCodeChange) : MaterialSubmission
}

/**
 * Plans one material. With an active mix and no MIX box checked, the submission is edits-only:
 * unchecked MIX means "do not touch the mix", not "remove from the mix".
 */
fun planMaterialSubmission(
    rows: List<ManageCodeRow>,
    selections: Map<String, ManageCodeRowSelection>,
    locked: Set<String>,
    catalog: MixCatalogSnapshot,
    materialName: String,
    selectedTarget: MixGenerationTarget?,
    automaticTarget: MixGenerationTarget?,
): MaterialSubmission {
    val hasActive = catalog.entries.any { it.lifecycle == MixLifecycle.ACTIVE }
    val anyMixChecked = rows.any { it.editablePgm !in locked && selections[it.editablePgm]?.mix == true }
    if (hasActive && selectedTarget == null && !anyMixChecked) {
        val change = buildManageCodeChange(rows, selections, locked, originalPrograms = emptyList())
        return MaterialSubmission.Ready(null, change.copy(orderOrMembershipChanged = false))
    }
    val target = selectedTarget ?: automaticTarget ?: return MaterialSubmission.NeedsTarget
    val plan = resolveMixGenerationTarget(target, catalog, materialName) ?: return MaterialSubmission.StaleTarget
    return MaterialSubmission.Ready(
        plan,
        buildManageCodeChange(rows, selections, locked, originalPrograms = plan.programsBaseline),
    )
}
```

- [ ] **Step 4: Run, verify pass** (same command, plus `--tests "com.kkc.sheettracker.ui.managecode.*"` since `ManageCodeOperationUiStateTest` calls `buildManageCodeActions`). Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt
git commit -m "feat(mix): plan edits-only submissions without a mix target"
```

---

### Task 4: Prompt/selection reducers for first MIX check

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt:72-81` (add `activeMixes` to `ManageCodeMaterialState`)
- Create: `app/src/main/java/com/kkc/sheettracker/ui/managecode/MixChoiceState.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/managecode/MixChoiceStateTest.kt`

- [ ] **Step 1: Add field** to `ManageCodeMaterialState` (after `mixConflict`):

```kotlin
    /** Active catalog mixes for display (header line + per-row "in <name>" tag). */
    val activeMixes: List<com.kkc.sheettracker.data.mixservice.MixCatalogEntry> = emptyList(),
```

- [ ] **Step 2: Write failing tests** — create `MixChoiceStateTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.managecode

import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixChoiceStateTest {
    private val rows = listOf("R1.pgm", "R2.pgm", "R3.pgm", "R4.pgm").mapIndexed { i, pgm ->
        ManageCodeRow(pageNumber = i + 1, pgmFiles = listOf(pgm), editablePgm = pgm, thumbnailPath = null)
    }
    private val mix = MixCatalogEntry(name = "MMix", mixFilename = "MMix.mix", lifecycle = MixLifecycle.ACTIVE, programs = listOf("R1.pgm", "R2.pgm"))

    private fun state(activeMixes: List<MixCatalogEntry> = listOf(mix), locked: Set<String> = emptySet()) =
        ManageCodeMaterialState(
            materialName = "M",
            hasPgmsOnThisCnc = true,
            rows = rows,
            locked = locked,
            selections = rows.associate { it.editablePgm to ManageCodeRowSelection() },
            activeMixes = activeMixes,
        )

    @Test
    fun `first MIX check on existing-mix material prompts instead of applying`() {
        val outcome = mixCheckOutcome(state(), setOf("R3.pgm"), checked = true, hasChoice = false)
        assertEquals(MixCheckOutcome.Prompt(setOf("R3.pgm")), outcome)
    }

    @Test
    fun `MIX check applies directly once a choice exists or no mix exists`() {
        assertTrue(mixCheckOutcome(state(), setOf("R3.pgm"), checked = true, hasChoice = true) is MixCheckOutcome.Apply)
        assertTrue(mixCheckOutcome(state(emptyList()), setOf("R3.pgm"), checked = true, hasChoice = false) is MixCheckOutcome.Apply)
        assertTrue(mixCheckOutcome(state(), setOf("R3.pgm"), checked = false, hasChoice = false) is MixCheckOutcome.Apply)
    }

    @Test
    fun `replace choice checks mix members except locked rows plus tapped`() {
        val target = MixGenerationTarget.ReplaceActive("MMix", 7L, listOf("R1.pgm", "R2.pgm"))
        val updated = applyReplaceChoice(state(locked = setOf("R2.pgm")), target, tapped = setOf("R4.pgm"))
        val checked = updated.selections.filterValues { it.mix }.keys
        assertEquals(setOf("R1.pgm", "R4.pgm"), checked)
        assertTrue(updated.mixLayoutDirty)
    }

    @Test
    fun `additional choice checks only tapped rows`() {
        val updated = applyAdditionalChoice(state(), tapped = setOf("R3.pgm"))
        assertEquals(setOf("R3.pgm"), updated.selections.filterValues { it.mix }.keys)
    }

    @Test
    fun `choice clears when no MIX box remains checked`() {
        assertTrue(shouldClearMixChoice(state()))
        assertFalse(shouldClearMixChoice(applyAdditionalChoice(state(), setOf("R3.pgm"))))
    }

    @Test
    fun `mix membership tag maps each pgm to its active mix`() {
        assertEquals(mapOf("R1.pgm" to "MMix", "R2.pgm" to "MMix"), mixMembershipByPgm(listOf(mix)))
    }
}
```

- [ ] **Step 3: Run, verify compile failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.managecode.MixChoiceStateTest"`

- [ ] **Step 4: Implement** — create `MixChoiceState.kt`:

```kotlin
package com.kkc.sheettracker.ui.managecode

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

/** Replace: revise the chosen mix — its members (never locked sheets) plus what was tapped. */
internal fun applyReplaceChoice(
    state: ManageCodeMaterialState,
    target: MixGenerationTarget.ReplaceActive,
    tapped: Set<String>,
): ManageCodeMaterialState {
    val include = (target.programsBaseline.toSet() + tapped) - state.locked
    val reordered = updateMixLayoutRows(state, applyExistingOrder(state.rows, target.programsBaseline))
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
```

If `applyExistingOrder` is not a top-level function in `com.kkc.sheettracker.data.mixservice` (ManageCodeScreen calls it fully qualified), `grep -rn "fun applyExistingOrder" app/src/main` and fix the import.

- [ ] **Step 5: Run, verify pass** (same command). Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/MixChoiceState.kt app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt app/src/test/java/com/kkc/sheettracker/ui/managecode/MixChoiceStateTest.kt
git commit -m "feat(mix): reducers for Replace/Additional choice on first MIX check"
```

---

### Task 5: Replace / Additional / Cancel dialog

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ExistingMixChoiceDialog.kt`

No unit test (pure UI); logic it calls (`replacementTarget`, `nextAdditionalMixName`, `isCatalogMixNameAvailable`) is already tested. Verified in Task 7 manual pass.

- [ ] **Step 1: Create file**

```kotlin
package com.kkc.sheettracker.ui.managecode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import com.kkc.sheettracker.data.mixservice.isCatalogMixNameAvailable
import com.kkc.sheettracker.data.mixservice.nextAdditionalMixName

private enum class ChoiceStage { MAIN, PICK_REPLACE, CONFIRM_REPLACE, NAME_ADDITIONAL }

/** Shown on the first MIX check for a material that already has an active mix. */
@Composable
fun ExistingMixChoiceDialog(
    catalog: MixCatalogSnapshot,
    materialName: String,
    onReplace: (MixGenerationTarget.ReplaceActive) -> Unit,
    onAdditional: (MixGenerationTarget.CreateAdditional) -> Unit,
    onCancel: () -> Unit,
) {
    val active = remember(catalog) { catalog.entries.filter { it.lifecycle == MixLifecycle.ACTIVE } }
    var stage by remember(catalog) { mutableStateOf(ChoiceStage.MAIN) }
    var replacing by remember(catalog) { mutableStateOf<MixCatalogEntry?>(active.singleOrNull()) }
    var name by remember(catalog) { mutableStateOf(nextAdditionalMixName(materialName, catalog)) }

    when (stage) {
        ChoiceStage.MAIN -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("$materialName already has a mix") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    active.forEach { Text("${it.name} — ${it.programs.size} PGMs") }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = {
                        stage = if (active.size > 1) ChoiceStage.PICK_REPLACE else ChoiceStage.CONFIRM_REPLACE
                    }) { Text("Replace Mix") }
                    TextButton(onClick = { stage = ChoiceStage.NAME_ADDITIONAL }) { Text("Additional Mix") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            },
        )
        ChoiceStage.PICK_REPLACE -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Replace which mix?") },
            text = {
                Column {
                    active.forEach { entry ->
                        TextButton(onClick = { replacing = entry; stage = ChoiceStage.CONFIRM_REPLACE }) {
                            Text("${entry.name} — ${entry.programs.size} PGMs")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        ChoiceStage.CONFIRM_REPLACE -> {
            val entry = replacing ?: return
            val archivedAt = entry.updatedAt ?: entry.createdAt ?: "an unknown time"
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("Replace ${entry.name}?") },
                text = { Text("The current ${entry.name} version from $archivedAt will be archived before the replacement is compiled.") },
                confirmButton = {
                    TextButton(onClick = { onReplace(replacementTarget(entry, catalog)) }) { Text("Replace and archive") }
                },
                dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        }
        ChoiceStage.NAME_ADDITIONAL -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Additional mix") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mix name") },
                    supportingText = { Text("Must be unique") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAdditional(MixGenerationTarget.CreateAdditional(name)) },
                    enabled = isCatalogMixNameAvailable(name, catalog),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }
}
```

(`replacementTarget` is the existing top-level function in `MixActionDialog.kt`, same package.)

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ExistingMixChoiceDialog.kt
git commit -m "feat(mix): Replace / Additional / Cancel dialog for existing mixes"
```

---

### Task 6: Wire into ManageCodeScreen (state, header, tags, prompt, planning)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt`

- [ ] **Step 1: Populate `activeMixes`** in `loadMaterialState` (~line 691), inside the `ManageCodeMaterialState(...)` call:

```kotlin
            activeMixes = catalog?.entries
                ?.filter { it.lifecycle == com.kkc.sheettracker.data.mixservice.MixLifecycle.ACTIVE }
                .orEmpty(),
```

Also: the existing `existingMix` lookup uses `.singleOrNull()`; with MIX now unchecked it only drives row order — leave it.

- [ ] **Step 2: Keep `activeMixes` fresh through merges** — in `mergeCatalogRefreshMaterialState` (~line 182) the dirty branch copies from `hydrated`, which already carries the new `activeMixes`; no change needed. Confirm by reading.

- [ ] **Step 3: Header line + row tag** in `ManageCodeMaterialCard`. After the `mixConflict` block (~line 293) add:

```kotlin
            state.activeMixes.forEach { entry ->
                val compile = when (entry.lastCompileOk) {
                    true -> "compiled ${entry.lastCompiledAt.orEmpty()}".trim()
                    false -> "compile failed"
                    null -> entry.status ?: "not compiled"
                }
                Text(
                    text = "Existing mix: ${entry.name} — ${entry.programs.size} PGMs, $compile",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
```

Compute `val membership = remember(state.activeMixes) { mixMembershipByPgm(state.activeMixes) }` at the top of `ManageCodeMaterialCard`, pass `mixName = membership[row.editablePgm]` into `ManageCodeRowView` (new param `mixName: String?`), and in its text `Column` (~line 396) under the PGM names add:

```kotlin
                mixName?.let {
                    Text("in $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
```

- [ ] **Step 4: Prompt state** — near the other `remember` vars (~line 640) add:

```kotlin
    var pendingMixChoice by remember { mutableStateOf<Pair<String, Set<String>>?>(null) } // material to tapped pgms
```

Add a helper next to `updateSelection`:

```kotlin
    fun requestMixChange(materialName: String, pgms: Set<String>, checked: Boolean) {
        val state = materialStates[materialName] ?: return
        when (val outcome = mixCheckOutcome(state, pgms, checked, hasChoice = materialName in selectedTargets)) {
            is MixCheckOutcome.Prompt -> pendingMixChoice = materialName to outcome.pgms
            is MixCheckOutcome.Apply -> {
                val updated = applyMixChecks(state, outcome.pgms, outcome.checked)
                materialStates = materialStates + (materialName to updated)
                if (state.activeMixes.isNotEmpty() && shouldClearMixChoice(updated)) {
                    selectedTargets = selectedTargets - materialName
                }
            }
        }
    }
```

- [ ] **Step 5: Route MIX toggles through it.** In `ManageCodeMaterialCard` call site (~line 974):

```kotlin
                        onSelectionChanged = { pgm, sel ->
                            val previous = state.selections[pgm] ?: ManageCodeRowSelection()
                            if (sel.mix != previous.mix) requestMixChange(material.materialName, setOf(pgm), sel.mix)
                            else updateSelection(material.materialName, pgm, sel)
                        },
```

and in `onSelectAll`, handle `"MIX"` first:

```kotlin
                        onSelectAll = { field, checked ->
                            if (field == "MIX") {
                                val unlocked = state.rows.map { it.editablePgm }.filter { it !in state.locked }.toSet()
                                requestMixChange(material.materialName, unlocked, checked)
                                return@ManageCodeMaterialCard
                            }
                            // existing mapValues body unchanged for PUNLOAD / 2ND / SUPER
```

(If the `return@` label does not resolve, wrap the non-MIX branch in `else { ... }` instead.)

- [ ] **Step 6: Render the dialog** after the `pendingMixAction?.let { ... }` block (~line 1087):

```kotlin
        pendingMixChoice?.let { (materialName, tapped) ->
            val catalog = materialCatalogs[materialName]
            if (catalog == null) { pendingMixChoice = null; return@let }
            ExistingMixChoiceDialog(
                catalog = catalog,
                materialName = materialName,
                onReplace = { target ->
                    pendingMixChoice = null
                    selectedTargets = selectedTargets + (materialName to target)
                    materialStates[materialName]?.let { current ->
                        materialStates = materialStates + (materialName to applyReplaceChoice(current, target, tapped))
                    }
                },
                onAdditional = { target ->
                    pendingMixChoice = null
                    selectedTargets = selectedTargets + (materialName to target)
                    materialStates[materialName]?.let { current ->
                        materialStates = materialStates + (materialName to applyAdditionalChoice(current, tapped))
                    }
                },
                onCancel = { pendingMixChoice = null },
            )
        }
```

Cancel changes nothing, so the box stays unchecked and the next check prompts again.

- [ ] **Step 7: Use `planMaterialSubmission` in `buildSession`.** Replace lines ~769-781 (from the EXTERNAL check through `val change = buildManageCodeChange(...)`) with:

```kotlin
            if (catalog.entries.any { it.lifecycle == com.kkc.sheettracker.data.mixservice.MixLifecycle.EXTERNAL }) {
                return ManageCodeSessionPreparation.SelectAction(PendingMixAction(materialName, catalog))
            }
            val submission = planMaterialSubmission(
                rows = state.rows,
                selections = state.selections,
                locked = state.locked,
                catalog = catalog,
                materialName = materialName,
                selectedTarget = selectedDecision.target,
                automaticTarget = mixActionDialogContent(catalog).automaticTarget,
            )
            val (plan, change) = when (submission) {
                MaterialSubmission.NeedsTarget ->
                    return ManageCodeSessionPreparation.SelectAction(PendingMixAction(materialName, catalog))
                MaterialSubmission.StaleTarget ->
                    return ManageCodeSessionPreparation.Blocked("Mix catalog changed — choose an action again")
                is MaterialSubmission.Ready -> submission.plan to submission.change
            }
            if (!change.orderOrMembershipChanged && change.editRows.isEmpty()) continue
```

Then in the duplicate check block replace `plan.name` with `plan!!.name` is NOT acceptable — instead guard: `if (plan != null && change.orderOrMembershipChanged) { ... exclude = plan.name ... thisMixName = plan.name ... }`. Keep `target` usages: the `Duplicate(materialName, target, duplicates)` needs a target; use `selectedDecision.target ?: MixGenerationTarget.FirstDefault` there (a duplicate warning only happens when `plan != null`, which implies one of those). `buildManageCodeActions(..., plan = plan, ...)` already accepts nullable.

Add imports: `com.kkc.sheettracker.data.mixservice.MaterialSubmission`, `com.kkc.sheettracker.data.mixservice.planMaterialSubmission`.

- [ ] **Step 8: Compile + run all mix tests**

Run: `.\gradlew.bat :app:compileDebugKotlin` then `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixservice.*" --tests "com.kkc.sheettracker.ui.managecode.*"`
Expected: BUILD SUCCESSFUL, all PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt
git commit -m "feat(mix): show existing mixes and prompt Replace/Additional on first MIX check"
```

---

### Task 7: Step list under the action button + device verification

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/managecode/ManageCodeOperationUiStateTest.kt`

- [ ] **Step 1: Failing test** — append to `ManageCodeOperationUiStateTest`:

```kotlin
    @Test
    fun `step lines mark done, running, and queued actions`() {
        val session = ManageCodeSession(
            job = "648",
            actions = listOf(
                ManageCodeOperationAction.catalogCreate("19mm", "19mmMix", listOf("R1.pgm"), 7L),
                ManageCodeOperationAction.pgmEdits("19mm", "req", listOf(PgmEditRow("R1.pgm", "standard", false))),
            ),
            currentActionIndex = 1,
            current = MixServiceOperation(job = "648", kind = "pgm_edit", state = "running", stage = "compiling"),
        )
        assertEquals(
            listOf("✓ Create mix 19mmMix — 19mm", "… 2nd pass / PUNLOAD (1 PGM) — 19mm"),
            manageCodeStepLines(session),
        )
    }
```

Add missing imports (`ManageCodeSession`, `ManageCodeOperationAction`, `PgmEditRow`, `MixServiceOperation`) if the file lacks them.

- [ ] **Step 2: Run, verify compile failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.managecode.ManageCodeOperationUiStateTest"`

- [ ] **Step 3: Implement** — add near `manageCodeOperationLabel`:

```kotlin
internal fun manageCodeStepLines(session: ManageCodeSession?): List<String> {
    if (session == null || session.actions.size < 2) return emptyList()
    val failed = session.current.state in setOf("failed", "interrupted")
    return session.actions.mapIndexed { index, action ->
        val label = when (action.kind) {
            ManageCodeOperationAction.CATALOG_CREATE -> "Create mix ${action.name}"
            ManageCodeOperationAction.CATALOG_REPLACE -> "Replace mix ${action.name}"
            ManageCodeOperationAction.PGM_EDITS -> {
                val n = action.editRows.size
                "2nd pass / PUNLOAD ($n PGM${if (n == 1) "" else "s"})"
            }
            ManageCodeOperationAction.EXTERNAL_DELETE -> "Delete ${action.externalMixFilename}"
            else -> "Mix ${action.name}"
        }
        val mark = when {
            index < session.currentActionIndex -> "✓"
            index == session.currentActionIndex && failed -> "✗"
            index == session.currentActionIndex -> "…"
            else -> "○"
        }
        "$mark $label — ${action.material}"
    }
}
```

Render under the failure texts in the button `item { ... }` (~line 1037):

```kotlin
                    manageCodeStepLines(operationSession?.takeIf { it.job == jobFolderName }).forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                    }
```

- [ ] **Step 4: Run, verify pass** (same command + full mix suite from Task 6 Step 8). Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt app/src/test/java/com/kkc/sheettracker/ui/managecode/ManageCodeOperationUiStateTest.kt
git commit -m "feat(mix): list queued mix and 2nd-pass steps with status"
```

- [ ] **Step 6: Release build + install on tablet** (memory: run gradle + adb directly; the release script's unicode breaks under `powershell -File`):

```powershell
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 7: Manual verification** — ask the user to navigate the tablet to Manage Code for a test job (do not adb-tap through the app), then check:
  1. Fresh material: MIX (default all) + 2ND on some rows → submit → step list shows `✓ Create mix`, then `✓ 2nd pass`. Confirm on the CNC:
     `curl -s "http://192.168.20.4:8477/jobs/<job url-encoded>/operations"` shows a completed `mix_write` followed by a completed `pgm_edit`.
  2. Existing-mix material: header line + `in <name>` tags visible; all MIX boxes unchecked.
  3. 2ND only on existing-mix material → submits edits only, no dialog.
  4. Check one MIX box → prompt. Cancel → box stays unchecked; check again → prompt again.
  5. Replace → mix members auto-checked except COMPLETE/RE_NESTED sheets; confirm archive; submit.
  6. Additional → name pre-filled `<Material>Mix 2`; only tapped box checked; submit creates second mix.
