package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixfiles.SecondPassDraft
import com.kkc.sheettracker.data.mixfiles.SecondPassState
import com.kkc.sheettracker.data.mixservice.PgmInventoryItem
import org.junit.Assert.*
import org.junit.Test

class PgmPreviewStateTest {
    private val hash="a".repeat(64)
    private val intent=SecondPassState(true,false,false)
    private fun key(pgm:String="P.pgm")=PgmPreviewKey("scm","job","material",pgm,hash,intent,false)

    @Test fun lateResponseCannotReplaceAnotherFile() {
        val state=PgmPreviewState()
        state.start(key())
        state.start(key("other.pgm"))
        assertFalse(state.fail(key(),"late response"))
        assertTrue(state.loading)
        assertTrue(state.fail(key("other.pgm"),"unavailable"))
        assertEquals("unavailable",state.error)
    }

    @Test fun draftChangeInvalidatesPendingPreview() {
        val state=PgmPreviewState(); state.start(key()); state.invalidate("p.pgm")
        assertFalse(state.loading)
        assertFalse(state.fail(key(),"old response"))
    }

    @Test fun dirtyDraftDoesNotSilentlyAdoptNewRevisions() {
        val state=PgmRevisionState()
        state.observe(listOf(PgmInventoryItem("P.pgm",sha256=hash)),dirty=false,explicit=false)
        state.observe(listOf(PgmInventoryItem("P.pgm",sha256="b".repeat(64))),dirty=true,explicit=false)
        assertEquals(hash,state.hashes["P.pgm"])
        assertTrue(state.needsReview)
        val draft=SecondPassDraft(mapOf("P.pgm" to intent),mapOf("P.pgm" to SecondPassState(false,false,false)))
        assertFalse(state.canApply(draft))
        state.observe(listOf(PgmInventoryItem("P.pgm",sha256="b".repeat(64))),dirty=true,explicit=true)
        assertTrue(state.canApply(draft))
    }

    @Test fun offlineRevisionStateCannotAuthorizeApply() {
        val state=PgmRevisionState()
        state.observe(listOf(PgmInventoryItem("P.pgm",sha256=hash)),dirty=false,explicit=false)
        state.unavailable("Reconnect and refresh CNC revisions")
        assertTrue(state.needsReview)
        assertEquals(hash,state.hashes["P.pgm"])
        assertFalse(state.canApply(SecondPassDraft(mapOf("P.pgm" to intent),emptyMap())))
    }
}
