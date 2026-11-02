package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.mixservice.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SecondPassRevisionsTest {
    private val old="a".repeat(64)
    private val base="b".repeat(64)
    private val proposed="c".repeat(64)
    private val none=SecondPassState(false,false,false)
    private val standard=SecondPassState(true,false,false)
    private val superState=SecondPassState(true,true,false)
    private fun preview()=PgmPreviewFile("P.pgm",old,base,proposed,"old","new","diff",
        PgmPreviewIntent("standard",false,true),PgmEditCurrentState("super",false),
        PgmEditCurrentState("standard",false),PgmConversion("rust","rust",emptyList()),emptyList())
    private fun completed(ok: Boolean=true)=MixServiceOperation(id="op",state="completed",result=mapOf(
        "ok" to ok,"files" to listOf(mapOf("status" to if(ok) "succeeded" else "refused"))))
    private class Api(val submit: suspend (List<PgmEditRow>)->MixServiceOperation):MixOperationService {
        override suspend fun submitPgmEdits(job:String,material:String,requestId:String,files:List<PgmEditRow>)=submit(files)
        override suspend fun getOperation(id:String)=error("already completed")
    }

    @Test fun previewOfSuperToStandardUsesOriginal() {
        val draft=SecondPassDraft(mapOf("P.pgm" to standard),mapOf("P.pgm" to superState))
        val row=previewRequest(draft,"p.pgm",old).files.single()
        assertTrue(row.restoreOriginal)
        assertEquals("standard",row.secondPass)
        assertEquals(old,row.expectedSha256)
    }

    @Test fun additiveApplyUsesCapturedRevisionWithoutRebinding() = runTest {
        val rows=mutableListOf<PgmEditRow>()
        val support=SecondPassRevisionSupport(SecondPassRevisionSnapshot(mapOf("P.pgm" to old)),
            refresh={error("must not refresh and silently replace captured revision")},revert={_,_,_,_->error("not reverting")})
        val runner=SecondPassRunner(Api { rows+=it; completed() },{_,_,_,_->error("legacy revert")},{"id"},revisions=support)
        assertEquals(SecondPassOutcome.Done,runner.apply("J","M",listOf(SecondPassStep.Edit(listOf(PgmEditRow("P.pgm","standard",false))))) {})
        assertEquals(old,rows.single().expectedSha256)
    }

    @Test fun hashesRefreshAfterRestoreAndPreviewBaseIsChecked() = runTest {
        val edits=mutableListOf<PgmEditRow>(); val reverts=mutableListOf<PgmRevertRow>()
        val support=SecondPassRevisionSupport(SecondPassRevisionSnapshot(mapOf("P.pgm" to old),mapOf("P.pgm" to preview())),
            refresh={mapOf("P.pgm" to base)},revert={_,_,_,rows->reverts+=rows;completed()})
        val runner=SecondPassRunner(Api { edits+=it;completed() },{_,_,_,_->error("legacy revert")},{"id"},revisions=support)
        val plan=planSecondPass(SecondPassDraft(mapOf("P.pgm" to standard),mapOf("P.pgm" to superState)))
        assertEquals(SecondPassOutcome.Done,runner.apply("J","M",plan) {})
        assertEquals(old,reverts.single().expectedSha256)
        assertEquals(base,edits.single().expectedSha256)
        assertEquals(proposed,edits.single().expectedSourceSha256)
    }

    @Test fun postRestoreHashMismatchPreventsFollowingEdit() = runTest {
        var edits=0
        val support=SecondPassRevisionSupport(SecondPassRevisionSnapshot(mapOf("P.pgm" to old),mapOf("P.pgm" to preview())),
            refresh={mapOf("P.pgm" to "d".repeat(64))},revert={_,_,_,_->completed()})
        val runner=SecondPassRunner(Api { edits++;completed() },{_,_,_,_->error("legacy revert")},{"id"},revisions=support)
        val plan=planSecondPass(SecondPassDraft(mapOf("P.pgm" to standard),mapOf("P.pgm" to superState)))
        val result=runner.apply("J","M",plan) {} as SecondPassOutcome.Failed
        assertTrue(result.partial); assertEquals(0,edits)
    }

    @Test fun missingHashesRefuseBeforeAnyMutation() = runTest {
        var writes=0
        val support=SecondPassRevisionSupport(SecondPassRevisionSnapshot(emptyMap()),refresh={emptyMap()},revert={_,_,_,_->writes++;completed()})
        val runner=SecondPassRunner(Api { writes++;completed() },{_,_,_,_->error("legacy revert")},{"id"},revisions=support)
        val result=runner.apply("J","M",planSecondPass(SecondPassDraft(mapOf("P.pgm" to none),mapOf("P.pgm" to superState)))) {} as SecondPassOutcome.Failed
        assertEquals(0,writes)
        assertEquals(SecondPassMutation.REFUSED_BEFORE_MUTATION,result.mutation)
    }

    @Test fun partialRevertPreventsFollowingEdit() = runTest {
        var edits=0; var refreshes=0
        val support=SecondPassRevisionSupport(SecondPassRevisionSnapshot(mapOf("P.pgm" to old)),
            refresh={refreshes++;mapOf("P.pgm" to base)},revert={_,_,_,_->completed(false)})
        val runner=SecondPassRunner(Api { edits++;completed() },{_,_,_,_->error("legacy revert")},{"id"},revisions=support)
        val result=runner.apply("J","M",planSecondPass(SecondPassDraft(mapOf("P.pgm" to standard),mapOf("P.pgm" to superState)))) {} as SecondPassOutcome.Failed
        assertTrue(result.partial); assertEquals(0,edits); assertEquals(0,refreshes)
    }
}
