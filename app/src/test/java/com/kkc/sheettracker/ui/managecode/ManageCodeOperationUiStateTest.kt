package com.kkc.sheettracker.ui.managecode

import com.kkc.sheettracker.data.mixservice.ManageCodeOperationAction
import com.kkc.sheettracker.data.mixservice.ManageCodeSession
import com.kkc.sheettracker.data.mixservice.MixOperationWarning
import com.kkc.sheettracker.data.mixservice.MixOperationRestoreState
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeOperationUiStateTest {

    @Test
    fun `compiling operation maps to indeterminate button state only for its job`() {
        val session = session(stage = "compiling")

        assertTrue(ManageCodeOperationUiState.from(session, "648") is ManageCodeOperationUiState.Compiling)
        assertEquals(ManageCodeOperationUiState.Idle, ManageCodeOperationUiState.from(session, "649"))
    }

    @Test
    fun `preparing operation maps real program counts to determinate progress`() {
        val state = ManageCodeOperationUiState.from(
            session(stage = "preparing", completedPrograms = 3, totalPrograms = 8),
            "648",
        )

        assertEquals(ManageCodeOperationUiState.Preparing(3f / 8f), state)
    }

    @Test
    fun `syncing operation includes completed material count`() {
        val state = ManageCodeOperationUiState.from(session(stage = "syncing", completedMaterials = 1), "648")

        assertEquals(ManageCodeOperationUiState.Syncing(1, 2), state)
    }

    @Test
    fun `queued and submitting stages have honest distinct states and copy`() {
        val queuedSession = session(state = "queued", stage = "queued")
        val submittingSession = session(state = "submitting", stage = "submitting")

        val queued = ManageCodeOperationUiState.from(queuedSession, "648")
        val submitting = ManageCodeOperationUiState.from(submittingSession, "648")

        assertEquals(ManageCodeOperationUiState.Queued, queued)
        assertEquals("0 / 2 — Queued", manageCodeOperationLabel(queued, queuedSession))
        assertEquals(ManageCodeOperationUiState.Submitting, submitting)
        assertEquals("0 / 2 — Submitting", manageCodeOperationLabel(submitting, submittingSession))
    }

    @Test
    fun `unreachable service keeps persisted job session state visible`() {
        val persisted = session(stage = "compiling")

        val presentation = manageCodeScreenPresentation(
            reachable = false,
            restoreState = MixOperationRestoreState.Ready,
            session = persisted,
            job = "648",
        )

        assertTrue(presentation.showContent)
        assertTrue(presentation.showUnreachableBanner)
        assertEquals(ManageCodeOperationUiState.Compiling, presentation.operationState)
        assertEquals(false, presentation.actionEnabled)
    }

    @Test
    fun `generate gate changes from disabled restoring to enabled ready`() {
        val restoring = manageCodeScreenPresentation(
            reachable = true,
            restoreState = MixOperationRestoreState.Restoring,
            session = null,
            job = "648",
        )
        val ready = manageCodeScreenPresentation(
            reachable = true,
            restoreState = MixOperationRestoreState.Ready,
            session = null,
            job = "648",
        )

        assertEquals(false, restoring.actionEnabled)
        assertEquals(true, ready.actionEnabled)
    }

    @Test
    fun `restore failure exposes retry without pretending generation is ready`() {
        val presentation = manageCodeScreenPresentation(
            reachable = true,
            restoreState = MixOperationRestoreState.Failed("stored sessions could not be read"),
            session = null,
            job = "648",
        )

        assertTrue(presentation.canRetryRestore)
        assertTrue(presentation.actionEnabled)
        assertEquals("stored sessions could not be read", presentation.restoreError)
        assertEquals(ManageCodeOperationUiState.Idle, presentation.operationState)
    }

    @Test
    fun `completed warning is terminal success rather than retryable failure`() {
        val state = ManageCodeOperationUiState.from(
            session(
                state = "completed",
                stage = "completed",
                warning = MixOperationWarning("history", "History sync delayed"),
                currentActionIndex = 2,
            ),
            "648",
        )

        assertTrue(state is ManageCodeOperationUiState.Completed)
    }

    @Test
    fun `earlier action warning remains visible in final multi-action copy`() {
        val warning = MixOperationWarning("history", "History sync delayed")
        val completed = session(
            state = "completed",
            stage = "completed",
            currentActionIndex = 2,
            warnings = listOf(warning),
        )

        val state = ManageCodeOperationUiState.from(completed, "648")

        assertEquals(ManageCodeOperationUiState.Completed, state)
        assertEquals("0 / 2 — Finished with warning", manageCodeOperationLabel(state, completed))
    }

    @Test
    fun `completed first action for one material never maps to intermediate Finished`() {
        val betweenActions = session(
            state = "completed",
            stage = "completed",
            currentActionIndex = 1,
            actions = listOf(
                ManageCodeOperationAction.mix("Walnut", "WalnutMix", listOf("A.pgm")),
                ManageCodeOperationAction.pgmEdits("Walnut", "request", emptyList()),
            ),
        )

        val state = ManageCodeOperationUiState.from(betweenActions, "648")

        assertTrue(state !is ManageCodeOperationUiState.Completed)
        assertTrue(!manageCodeOperationLabel(state, betweenActions).contains("Finished"))
    }

    @Test
    fun `completed successful session keeps result visible and enables Generate again`() {
        val completed = session(
            state = "completed",
            stage = "completed",
            currentActionIndex = 2,
        )

        val presentation = manageCodeScreenPresentation(
            reachable = true,
            restoreState = MixOperationRestoreState.Ready,
            session = completed,
            job = "648",
        )

        assertEquals(ManageCodeOperationUiState.Completed, presentation.operationState)
        assertTrue(presentation.actionEnabled)
    }

    @Test
    fun `failed and interrupted operations are retryable failure states`() {
        val failed = ManageCodeOperationUiState.from(
            session(state = "failed", stage = "failed", error = "WINXISO timed out"),
            "648",
        )
        val interrupted = ManageCodeOperationUiState.from(
            session(state = "interrupted", stage = "interrupted", error = "Tablet restarted"),
            "648",
        )

        assertEquals(
            ManageCodeOperationUiState.Failed("Operation failed: WINXISO timed out"),
            failed,
        )
        assertEquals(
            ManageCodeOperationUiState.Failed("Operation interrupted: Tablet restarted"),
            interrupted,
        )
    }

    @Test
    fun `conflicted material is skipped while eligible material actions proceed`() {
        val walnutAction = ManageCodeOperationAction.mix("Walnut", "WalnutMix", listOf("W.pgm"))
        val mapleAction = ManageCodeOperationAction.mix("Maple", "MapleMix", listOf("M.pgm"))

        val result = manageCodeConflictGateDecision(
            listOf(
                ManageCodeMaterialActionCandidate("Walnut", hasMixConflict = true, actions = listOf(walnutAction)),
                ManageCodeMaterialActionCandidate("Maple", hasMixConflict = false, actions = listOf(mapleAction)),
            ),
        )

        assertEquals(listOf(mapleAction), result.actions)
        assertEquals(null, result.conflictBlockMessage)
    }

    @Test
    fun `all conflicted materials produce a named blocker`() {
        val result = manageCodeConflictGateDecision(
            listOf(
                ManageCodeMaterialActionCandidate("Walnut", hasMixConflict = true, actions = emptyList()),
                ManageCodeMaterialActionCandidate("Maple", hasMixConflict = true, actions = emptyList()),
            ),
        )

        assertEquals(
            "Multiple mixes already exist for Walnut, Maple — resolve on the CNC first",
            result.conflictBlockMessage,
        )
    }

    @Test
    fun `nonconflicted material without changes does not suppress conflict blocker`() {
        val result = manageCodeConflictGateDecision(
            listOf(
                ManageCodeMaterialActionCandidate("Walnut", hasMixConflict = true, actions = emptyList()),
                ManageCodeMaterialActionCandidate("Maple", hasMixConflict = false, actions = emptyList()),
            ),
        )

        assertEquals(
            "Multiple mixes already exist for Walnut — resolve on the CNC first",
            result.conflictBlockMessage,
        )
    }

    private fun session(
        state: String = "running",
        stage: String = "queued",
        completedPrograms: Int = 0,
        totalPrograms: Int = 0,
        completedMaterials: Int = 0,
        warning: MixOperationWarning? = null,
        error: String? = null,
        currentActionIndex: Int = 0,
        warnings: List<MixOperationWarning> = emptyList(),
        actions: List<ManageCodeOperationAction> = listOf(
            ManageCodeOperationAction.mix("Walnut", "WalnutMix", listOf("A.pgm")),
            ManageCodeOperationAction.pgmEdits("Maple", "request", emptyList()),
        ),
    ) = ManageCodeSession(
        job = "648",
        actions = actions,
        completedMaterials = completedMaterials,
        currentActionIndex = currentActionIndex,
        warnings = warnings,
        current = MixServiceOperation(
            job = "648",
            material = "Walnut",
            state = state,
            stage = stage,
            completedPrograms = completedPrograms,
            totalPrograms = totalPrograms,
            warning = warning,
            error = error,
        ),
    )
}
