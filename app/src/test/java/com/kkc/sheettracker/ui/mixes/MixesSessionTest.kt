package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.kkc.sheettracker.data.mixorder.*
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.data.models.SheetPlacement
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MixesSessionTest {
    @Test fun stagedSplitCompletionRacePreventsRequestWithoutChangingEitherDraft() = runTest {
        for (interrupted in listOf(false, true)) {
            val session = MixesSession()
            val template = catalog("split").files.single()
            val entries = listOf("AzA.pgm", "AzZ.pgm", "AzA.pgm", "other.pgm").map { pgm ->
                template.entries.single().copy(pgm = pgm, path = "Z:\\$pgm", remaining = if (interrupted) 1 else null)
            }
            val mix = template.copy(entries = entries, companion = if (interrupted) "MapleMix.FRZ" else null)
            val frz = mix.copy(file = "MapleMix.FRZ", kind = MixFileKind.FRZ, revision = "frz", companion = mix.file)
            val catalog = catalog("split").copy(files = if (interrupted) listOf(mix, frz) else listOf(mix))
            session.onCatalog(catalog)
            val group = group(catalog)
            session.reloadHistory(listOf("other.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
            session.updateSecond(listOf("other.pgm"), SecondPassField.SECOND, true, emptyList())
            session.edit(group, emptyList()) { MixDrafts.splitOff(it, setOf(1, 2, 3), "Other.mix") }
            val beforeMix = session.draft
            val beforeSecond = session.secondPass
            // The UI was eligible when staged, but ProgressStore locks changed before the actual submit.
            val currentLocks = listOf("AZA.PGM", "azz.pgm")
            var requests = 0
            val result = session.saveMix({ currentLocks }) { submitted ->
                buildWrite(submitted, group, submitted.completedPgms.toList(), emptyMap())
                requests++
                MixFilesResult.Ok(catalog)
            }
            assertNull(result)
            assertEquals(0, requests)
            assertFalse(session.applying)
            assertEquals("Completed sheets are staged for a new mix: AzA.pgm, AzZ.pgm. Discard the mix changes and split only pending sheets.", session.mixSaveBlockedReason)
            assertEquals(beforeMix, session.draft)
            assertEquals(beforeSecond, session.secondPass)
            assertTrue(session.discardMix(catalog))
            assertNull(session.mixSaveBlockedReason)
            assertEquals(beforeSecond, session.secondPass)
        }
    }
    @Test fun splitSaveUsesOneCurrentCompletionSnapshotForValidationAndWrite() = runTest {
        val session = session()
        val group = group(session.catalog!!)
        session.edit(group, emptyList()) { MixDrafts.splitOff(it, setOf(1), "Other.mix") }
        var reads = 0
        var requests = 0
        val saved = session.catalog!!
        val result = session.saveMix({ reads++; listOf("OtherA.pgm", "OtherZ.pgm") }) { submitted ->
            assertEquals(setOf("othera.pgm", "otherz.pgm"), submitted.completedPgms)
            val write = buildWrite(submitted, group, submitted.completedPgms.toList(), emptyMap()) as MixWrite.Split
            assertEquals("a.pgm", (write.newMixes.single().entries.single() as EntrySpec.Existing).path.substringAfterLast('\\'))
            requests++
            MixFilesResult.Ok(saved)
        }
        assertTrue(result is MixFilesResult.Ok)
        assertEquals(1, reads)
        assertEquals(1, requests)
        assertNull(session.mixSaveBlockedReason)
    }
    @Test fun tabAndNewDiscardOnlyMixAndKeepMaterialSecondPassDraft() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "draft.pgm") }
        val wanted = session.secondPass
        val latest = catalog("new", "other.pgm")
        session.onCatalog(latest)
        assertTrue(session.discardMix(latest))
        assertEquals("new", session.catalog!!.folderRevision)
        assertEquals(wanted, session.secondPass)
        session.startNew("Maple", pages("a.pgm"), emptyList())
        assertEquals(wanted, session.secondPass)
    }
    @Test fun newNameAvoidsCaseInsensitiveMixAndFrzStems() {
        val session = session()
        val mix = session.catalog!!.files.single()
        session.onCatalog(catalog("names").copy(files = listOf(mix, mix.copy(file = "MAPLEMIX 2.FRZ", kind = MixFileKind.FRZ))))
        session.startNew("Maple", pages("new.pgm"), emptyList())
        assertEquals("MapleMix 3.mix", session.draft!!.newFile)
    }
    @Test fun namingNewDraftKeepsEntriesAndSecondPassAndDoesNotNeedRemoteRename() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.startNew("Maple", pages("new.pgm"), emptyList())
        val before = session.draft!!
        session.renameNew("Custom Mix.mix")
        assertEquals("Custom Mix.mix", session.draft!!.newFile)
        assertEquals("custom mix.mix", session.selectedTab)
        assertEquals(before.entries, session.draft!!.entries)
        assertEquals(1, session.secondPass!!.changeCount)
    }
    @Test fun revisionRestoreAndCatalogMutationFreezeOnlyMixDraft() = runTest {
        val session = session()
        val group = group(session.catalog!!)
        session.stageRevision(group, group.primary, listOf("a.pgm"))
        assertEquals(setOf("a.pgm"), session.draft!!.completedPgms)
        val response = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val write = launch { session.mutateCatalog { response.await() } }
        runCurrent()
        val before = session.draft
        session.stageRevision(group, group.primary, emptyList())
        assertEquals(before, session.draft)
        assertFalse(session.discardMix(session.catalog))
        response.complete(MixFilesResult.Ok(catalog("renamed")))
        write.join()
        assertNull(session.draft)
        assertEquals("renamed", session.catalog!!.folderRevision)
    }
    @Test fun suspendedMixSaveFreezesOnlyMixDraftAndDiscard() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm", "b.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "b.pgm") }
        val submitted = session.draft
        val response = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val save = launch { session.saveMix { response.await() } }
        runCurrent()
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "lost.pgm") }
        assertFalse(session.discardMix(session.catalog))
        assertFalse(session.abandonAll(session.catalog))
        assertEquals(submitted, session.draft)
        session.updateSecond(listOf("b.pgm"), SecondPassField.SECOND, true, emptyList())
        assertEquals(1, session.secondPass!!.changeCount)
        response.complete(MixFilesResult.Ok(catalog("saved")))
        save.join()
        assertNull(session.draft)
        assertEquals(1, session.secondPass!!.changeCount)
    }
    @Test fun suspendedSecondSaveFreezesSecondDraftButMixRemainsEditable() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm", "b.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("b.pgm"), SecondPassField.SECOND, true, emptyList())
        val response = CompletableDeferred<SecondPassOutcome>()
        val save = launch { session.saveSecond({ emptyList() }, { response.await() }, {
            com.kkc.sheettracker.data.mixservice.PgmEditHistoryView(mapOf("b.pgm" to
                com.kkc.sheettracker.data.mixservice.PgmEditFileHistory(com.kkc.sheettracker.data.mixservice.PgmEditCurrentState("standard", false), stateSource = "ledger")), ok = true, stateSource = "ledger")
        }) }
        runCurrent()
        session.updateSecond(listOf("a.pgm"), SecondPassField.SUPER, true, emptyList())
        assertFalse(session.discardSecond())
        assertEquals(1, session.secondPass!!.changeCount)
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "independent.pgm") }
        response.complete(SecondPassOutcome.Done)
        save.join()
        assertEquals("independent.pgm", session.draft!!.entries.last().newPgm)
        assertEquals(0, session.secondPass!!.changeCount)
    }
    @Test fun confirmedTabAndNewAbandonmentAcceptDeferredCatalogBeforeChoosingOrSeeding() {
        val session = session()
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "draft.pgm") }
        val fresh = catalog("fresh", "b.pgm")
        session.onCatalog(fresh)
        assertTrue(session.conflict)
        assertTrue(session.abandonAll(fresh))
        session.selectedTab = group(session.catalog!!).key
        assertEquals("b.pgm", group(session.catalog!!).primary.entries.single().pgm)
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "again.pgm") }
        val newest = catalog("newest", "c.pgm")
        session.onCatalog(newest)
        assertTrue(session.abandonAll(newest))
        session.startNew("Maple", pages("a.pgm", "b.pgm", "c.pgm"), emptyList())
        assertEquals(listOf("a.pgm", "b.pgm"), session.draft!!.entries.map { it.newPgm })
        assertEquals("newest", session.draftFolderRevision)
    }
    @Test fun completionChangingAfterStagingDropsBothAzNamesBeforeAnySubmission() = runTest {
        val session = session()
        session.reloadHistory(listOf("pA.pgm", "pZ.pgm", "other.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("pA.pgm", "pZ.pgm"), SecondPassField.SECOND, true, emptyList())
        var attempts = 0
        session.saveSecond({ listOf("PA.PGM", "pz.pgm") }, { attempts++; SecondPassOutcome.Done }, { PgmEditHistoryView(ok = true, stateSource = "ledger") })
        assertEquals(0, attempts)
        assertEquals(0, session.secondPass!!.changeCount)
        session.updateSecond(listOf("pA.pgm", "pZ.pgm", "other.pgm"), SecondPassField.SECOND, true, emptyList())
        session.saveSecond({ listOf("pA.pgm", "pZ.pgm") }, { submitted ->
            assertEquals(listOf("other.pgm"), planSecondPass(submitted).filterIsInstance<SecondPassStep.Edit>().flatMap { it.rows }.map { it.name })
            attempts++; SecondPassOutcome.Done
        }, { PgmEditHistoryView(ok = true, stateSource = "ledger") })
        assertEquals(1, attempts)
    }
    @Test fun failedHistoryRetryKeepsWantedEditsAndOnlyConfirmedRecoveryAllowsApply() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.reloadHistory(listOf("a.pgm")) { null }
        assertFalse(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        var attempts = 0
        session.saveSecond({ emptyList() }, { attempts++; SecondPassOutcome.Done }, { PgmEditHistoryView(ok = true, stateSource = "ledger") })
        assertEquals(0, attempts)
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        assertTrue(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        assertEquals(MixesSession.NO_RECORDED_STATE, session.secondMessage)
    }
    @Test fun sessionKeysIncludeMachineForIdenticalNames() {
        val red = mixesSessionKey(com.kkc.sheettracker.data.cnc.CncMachines.scm, "J", "M")
        val blue = mixesSessionKey(com.kkc.sheettracker.data.cnc.CncMachines.scmNew, "J", "M")
        assertEquals(MaterialKey("scm", "J", "M"), red)
        assertEquals(MaterialKey("scm-new", "J", "M"), blue)
        assertEquals(blue, MixesSession(key = blue).key)
    }
    @Test fun blueSessionLoadsSecondPassWithFixedPunloadRemoval() = runTest {
        val blue = MixesSession(key = MaterialKey("scm-new", "J", "M"), punloadFixed = true)
        blue.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        assertTrue(blue.secondPass!!.punloadFixed)
        assertTrue(blue.secondPass!!.wanted.getValue("a.pgm").removePUnload)
        assertEquals(0, blue.secondPass!!.changeCount)
        blue.updateSecond(listOf("a.pgm"), SecondPassField.PUNLOAD, false, emptyList())
        assertEquals(0, blue.secondPass!!.changeCount)
        val red = MixesSession(key = MaterialKey("scm", "J", "M"))
        red.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        assertFalse(red.secondPass!!.wanted.getValue("a.pgm").removePUnload)
    }
    @Test fun createRemainingMixStagesReviewedNewMixFromUnlockedEntriesOnly() = runTest {
        val session = MixesSession(key = MaterialKey("scm-new", "J", "M"), punloadFixed = true)
        val template = catalog("base").files.single()
        val entries = listOf("a.pgm", "b.pgm", "c.pgm").mapIndexed { i, pgm ->
            template.entries.single().copy(pgm = pgm, path = "C:\\\\NEW CNC\\\\$pgm", quantity = i + 1, parameters = linkedMapOf("R" to "${i + 1}", "V" to "010.0"))
        }
        val c = catalog("base").copy(files = listOf(template.copy(entries = entries)))
        session.onCatalog(c)
        assertTrue(session.startRemaining(group(c), listOf("A.PGM")))
        val draft = session.draft!!
        assertEquals("mapleremaining.mix", draft.groupKey)
        assertEquals("MapleRemaining.mix", draft.newFile)
        assertEquals(listOf("b.pgm", "c.pgm"), draft.entries.map { it.source!!.pgm })
        assertEquals(listOf(2, 3), draft.entries.map { it.quantity })
        assertEquals(listOf("010.0", "010.0"), draft.entries.map { it.parameters!!["V"] })
        assertFalse(draft.isInterrupted)
        val write = buildWrite(draft, null, draft.completedPgms.toList(), emptyMap()) as MixWrite.Write
        assertNull(write.completion) // An ordinary new MIX, never an FRZ resume.
        assertEquals("MapleRemaining.mix", write.file)
        // Nothing left to cut, or an open draft: nothing is staged.
        assertFalse(MixesSession().also { it.onCatalog(c) }.startRemaining(group(c), listOf("a.pgm", "b.pgm", "c.pgm")))
        assertFalse(session.startRemaining(group(c), emptyList()))
    }
    @Test fun inFlightSaveStaysWithItsMachineSessionAfterSelectionChanges() = runTest {
        val red = MixesSession(key = MaterialKey("scm", "J", "M")).also { it.onCatalog(catalog("base")) }
        val blue = MixesSession(key = MaterialKey("scm-new", "J", "M")).also { it.onCatalog(catalog("blue-base")) }
        red.edit(group(catalog("base")), emptyList()) { MixDrafts.remove(it, it.entries.single().id) }
        val gate = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val submittedTo = mutableListOf<String>()
        val pending = launch { red.saveMix { submittedTo += red.key!!.machineId; gate.await() } }
        runCurrent()
        // The user switches to Blue and starts editing the identically named material.
        blue.edit(group(catalog("blue-base")), emptyList()) { MixDrafts.remove(it, it.entries.single().id) }
        gate.complete(MixFilesResult.Offline) // Uncertain: never resubmitted, never moved to Blue.
        pending.join()
        assertEquals(listOf("scm"), submittedTo)
        assertEquals(MixFilesResult.Offline, red.applyResult)
        assertNull(blue.applyResult)
        assertNotNull(blue.draft)
        assertEquals("blue-base", blue.catalog!!.folderRevision)
        runCurrent()
        assertEquals(1, submittedTo.size)
    }
    // ---- Smart mix order ----
    private fun placed(width: Double, length: Double, x: Double, y: Double) = Part(width = width, length = length, sheetPlacement = SheetPlacement(x, y, 0.0))
    private val smallEdgePart = placed(4.0, 20.0, 0.0, 10.0)
    private val bigPart = placed(30.0, 30.0, 20.0, 10.0)
    private fun meta(n: Int, parts: List<Part>, files: List<String> = listOf("R$n"), continuationOf: Int? = null) = PageMetadata(
        pageNumber = n, sheetFiles = files, sheetDimensions = listOf(48.0, 96.0),
        isPartListContinuation = continuationOf != null, continuationHeadPage = continuationOf, parts = parts)
    // Prior scores: sheet 1 = 0, sheet 2 = 1, sheet 3 = 2 small-edge parts, so the smart order is 3, 2, 1.
    private fun qualifying() = listOf(meta(1, listOf(bigPart)), meta(2, listOf(bigPart, smallEdgePart)), meta(3, listOf(smallEdgePart, smallEdgePart)))
    private fun MixesSession.startSmart(metadata: List<PageMetadata> = qualifying(), locked: List<String> = emptyList()) =
        startNew("Maple", sheetPages(metadata), locked, metadata)
    private fun MixesSession.order() = draft!!.entries.map { it.newPgm }

    @Test fun startNewQualifyingJobIsSmartSorted() {
        val session = MixesSession()
        session.startSmart()
        assertEquals(listOf("R3.pgm", "R2.pgm", "R1.pgm"), session.order())
        val smart = session.smart!!
        assertEquals(MixOrderMode.SMART, smart.mode)
        assertEquals(listOf("1|R1.pgm", "2|R2.pgm", "3|R3.pgm"), smart.defaultOrder)
        assertEquals(listOf("3|R3.pgm", "2|R2.pgm", "1|R1.pgm"), smart.suggestedOrder)
        assertEquals(PRIOR_WEIGHTS, smart.weights)
        assertEquals(listOf("1|R1.pgm", "2|R2.pgm", "3|R3.pgm"), smart.sheets.map { it.key })
    }

    @Test fun backgroundSeedUsesWorkerDispatcherAndKeepsNewerDraft() = runTest {
        val session = MixesSession()
        val metadata = qualifying()
        val worker = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val seed = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.startNewInBackground("Maple", sheetPages(metadata), emptyList(), metadata, worker)
        }
        assertNull(session.draft)
        runCurrent()
        seed.join()
        assertEquals(listOf("R3.pgm", "R2.pgm", "R1.pgm"), session.order())
        session.discardMix(null)
        val stale = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.startNewInBackground("Maple", sheetPages(metadata), emptyList(), metadata, worker)
        }
        session.startNew("Newer", pages("new.pgm"), emptyList())
        val newer = session.draft
        runCurrent()
        stale.join()
        assertSame(newer, session.draft)
    }

    @Test fun openingMixesFlushesOfflineRecordsBeforeTraining() {
        val folder = java.nio.file.Files.createTempDirectory("mix-open").toFile()
        try {
            val base = java.io.File(folder, "share").apply { mkdirs() }
            val pending = java.io.File(folder, "pending")
            val store = MixOrderLearningStore(pending)
            val session = MixesSession().also { it.startSmart() }
            val record = buildMixOrderRecord(session.draft!!, session.smart, true, emptyList(), "J", "M", "t", "1", 0L)!!
            store.record(null, record)
            assertEquals(1, pending.listFiles()!!.size)
            assertEquals(PRIOR_WEIGHTS, loadSmartWeights(store, base))
            assertTrue(pending.listFiles()!!.isEmpty())
            assertEquals(listOf(record.recordId), store.readAll(base).map { it.recordId })
        } finally { folder.deleteRecursively() }
    }
    @Test fun startNewUsesSessionWeightsAndKeepsLockedSlots() {
        val session = MixesSession()
        // Interior-only weighting: no sheet here has interior small parts, so every score ties and Default order wins.
        val tie = MixOrderWeights(listOf(0.0, 1.0, 0.0, 0.0, 0.0))
        session.smartWeights = tie
        session.startSmart()
        assertEquals(tie, session.smart!!.weights)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm"), session.order())
        // A completed sheet keeps its slot: the locked R3 stays last while the others reorder around it.
        val locked = MixesSession()
        locked.startSmart(locked = listOf("R3.pgm"))
        assertEquals(listOf("R2.pgm", "R1.pgm", "R3.pgm"), locked.order())
    }
    @Test fun startNewKeepsAzPairTogetherInOrder() {
        val metadata = listOf(meta(1, listOf(bigPart)), meta(2, listOf(smallEdgePart), files = listOf("R2A", "R2Z")))
        val session = MixesSession()
        session.startSmart(metadata)
        assertEquals(listOf("R2A.pgm", "R2Z.pgm", "R1.pgm"), session.order())
    }
    @Test fun startNewNonQualifyingHasNoSmart() {
        val metadata = qualifying() + meta(4, listOf(Part(width = 10.0, length = 10.0)))
        val session = MixesSession()
        session.startSmart(metadata)
        assertNull(session.smart)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm", "R4.pgm"), session.order())
        // No metadata at all (the legacy call shape) is the same as today.
        val legacy = MixesSession()
        legacy.startNew("Maple", pages("a.pgm", "b.pgm"), emptyList())
        assertNull(legacy.smart)
        assertEquals(listOf("a.pgm", "b.pgm"), legacy.order())
    }
    @Test fun startNewCoverageMismatchHasNoSmart() {
        // A continuation page with its own PGM is in the draft, but its sheet folds into head page 1.
        val continuation = qualifying() + meta(4, listOf(bigPart), files = listOf("R4"), continuationOf = 1)
        val withContinuation = MixesSession()
        withContinuation.startSmart(continuation)
        assertNull(withContinuation.smart)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm", "R4.pgm"), withContinuation.order())
        // A draft PGM that no metadata page explains.
        val metadata = qualifying()
        val orphan = MixesSession()
        orphan.startNew("Maple", sheetPages(metadata) + SheetPage(9, listOf("X.pgm"), false, false, null), emptyList(), metadata)
        assertNull(orphan.smart)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm", "X.pgm"), orphan.order())
    }
    @Test fun setSmartModeDefaultRestoresPageOrder() {
        val session = MixesSession()
        session.startSmart()
        val changeCount = session.draft!!.changeCount
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm"), session.order())
        assertEquals(MixOrderMode.DEFAULT, session.smart!!.mode)
        assertEquals(changeCount, session.draft!!.changeCount)
        session.setSmartMode(MixOrderMode.SMART)
        assertEquals(listOf("R3.pgm", "R2.pgm", "R1.pgm"), session.order())
        assertEquals(MixOrderMode.SMART, session.smart!!.mode)
    }
    @Test fun setSmartModeDiscardsDragsButSameModeKeepsThem() {
        val session = MixesSession()
        session.startSmart()
        val first = session.draft!!.entries.first().id
        session.edit(null, emptyList()) { MixDrafts.move(it, first, 2) }
        val dragged = session.order()
        assertEquals(listOf("R2.pgm", "R1.pgm", "R3.pgm"), dragged)
        session.setSmartMode(MixOrderMode.SMART) // already smart: not a re-sort
        assertEquals(dragged, session.order())
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm"), session.order())
    }
    @Test fun setSmartModeKeepsRenameAndAddedEntries() {
        val session = MixesSession()
        session.startSmart()
        session.renameNew("Cherry.mix")
        session.edit(null, emptyList()) { MixDrafts.add(it, "extra.pgm") }
        val changeCount = session.draft!!.changeCount
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertEquals("Cherry.mix", session.draft!!.newFile)
        assertEquals(listOf("R1.pgm", "R2.pgm", "R3.pgm", "extra.pgm"), session.order())
        assertEquals(changeCount, session.draft!!.changeCount)
        session.setSmartMode(MixOrderMode.SMART)
        assertEquals(listOf("R3.pgm", "R2.pgm", "R1.pgm", "extra.pgm"), session.order())
        assertEquals("Cherry.mix", session.draft!!.newFile)
    }
    @Test fun setSmartModeLockedSheetsKeepSlotsAndAzStayTogether() {
        val metadata = listOf(meta(1, listOf(bigPart)), meta(2, listOf(smallEdgePart), files = listOf("R2A", "R2Z")), meta(3, listOf(bigPart)))
        val session = MixesSession()
        // Default order is R1, R2A, R2Z, R3; the completed R3 holds the last slot in both modes.
        session.startSmart(metadata, locked = listOf("R3.pgm"))
        assertEquals(listOf("R2A.pgm", "R2Z.pgm", "R1.pgm", "R3.pgm"), session.order())
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertEquals(listOf("R1.pgm", "R2A.pgm", "R2Z.pgm", "R3.pgm"), session.order())
        session.setSmartMode(MixOrderMode.SMART)
        assertEquals(listOf("R2A.pgm", "R2Z.pgm", "R1.pgm", "R3.pgm"), session.order())
    }
    @Test fun setSmartModeIgnoredForExistingMix() {
        val session = session()
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "draft.pgm") }
        val before = session.draft
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertNull(session.smart)
        assertEquals(before, session.draft)
        // A new mix that never qualified has no suggestion to toggle.
        val plain = MixesSession()
        plain.startNew("Maple", pages("a.pgm", "b.pgm"), emptyList())
        val plainBefore = plain.draft
        plain.setSmartMode(MixOrderMode.SMART)
        assertNull(plain.smart)
        assertEquals(plainBefore, plain.draft)
    }
    @Test fun setSmartModeIgnoredWhileApplying() = runTest {
        val session = MixesSession()
        session.startSmart()
        val gate = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val pending = launch { session.saveMix({ emptyList() }) { gate.await() } }
        runCurrent()
        assertTrue(session.applying)
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertEquals(MixOrderMode.SMART, session.smart!!.mode)
        assertEquals(listOf("R3.pgm", "R2.pgm", "R1.pgm"), session.order())
        gate.complete(MixFilesResult.Offline)
        pending.join()
    }
    @Test fun smartClearedOnDiscardAndSave() = runTest {
        val session = MixesSession()
        session.startSmart()
        assertNotNull(session.smart)
        assertTrue(session.discardMix(null))
        assertNull(session.smart)
        session.startSmart()
        // A refused save keeps the draft and its suggestion.
        session.saveMix({ emptyList() }) { MixFilesResult.Offline }
        assertNotNull(session.smart)
        val saved = catalog("saved")
        assertTrue(session.saveMix({ emptyList() }) { MixFilesResult.Ok(saved) } is MixFilesResult.Ok)
        assertNull(session.draft)
        assertNull(session.smart)
        session.startSmart()
        session.mutateCatalog { MixFilesResult.Ok(saved) }
        assertNull(session.smart)
    }
    @Test fun saveOkReturnsWhileRecordWriteHangs() = runTest {
        val session = MixesSession()
        session.startSmart()
        val smart = session.smart
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val landed = CountDownLatch(1)
        val written = java.util.concurrent.atomic.AtomicReference<MixOrderRecord?>(null)
        val application = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            // The real composition Apply uses: the submission wrapper launches the record write after an Ok reply.
            val result = session.saveMix({ emptyList() }) { draft ->
                submitMixWithLearning(application,
                    record = { buildMixOrderRecord(draft, smart, true, emptyList(), "J", "M", "tablet", "1.0", 0L) },
                    write = { entered.countDown(); release.await(); written.set(it); landed.countDown() }
                ) { MixFilesResult.Ok(catalog("saved")) }
            }
            // saveMix returned Ok while the shared-dir write is (or is about to be) blocked on its latch.
            assertTrue(result is MixFilesResult.Ok)
            assertNull(session.draft)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(written.get())
            release.countDown()
            assertTrue(landed.await(5, TimeUnit.SECONDS))
            assertEquals("smart", written.get()!!.mode)
            assertEquals(smart!!.suggestedOrder, written.get()!!.finalOrder)
        } finally { application.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }
    @Test fun doubleSidedSheetWithAInAnotherMixStillQualifiesWithoutItsA() {
        val metadata = listOf(meta(1, listOf(bigPart)), meta(2, listOf(smallEdgePart), files = listOf("R2A", "R2Z")), meta(3, listOf(bigPart)))
        val session = MixesSession().also { it.onCatalog(catalog("base", "R2A.pgm")) }
        session.startNew("Maple", sheetPages(metadata), emptyList(), metadata)
        val smart = session.smart!!
        // Z leads at its sheet's smart position; A is in another mix and never enters this draft.
        assertEquals(listOf("R2Z.pgm", "R1.pgm", "R3.pgm"), session.order())
        assertEquals(listOf("2|R2A.pgm+R2Z.pgm", "1|R1.pgm", "3|R3.pgm"), smart.suggestedOrder)
        session.setSmartMode(MixOrderMode.DEFAULT)
        assertEquals(listOf("R1.pgm", "R2Z.pgm", "R3.pgm"), session.order())
        session.setSmartMode(MixOrderMode.SMART)
        val record = buildMixOrderRecord(session.draft!!, session.smart, true, listOf("R3.pgm"), "J", "M", "t", "1", 0L)!!
        assertEquals(smart.suggestedOrder, record.finalOrder)
        assertEquals(listOf("3|R3.pgm"), record.lockedSheets)
        // A completed sheet keeps its slot with the A missing too.
        val locked = MixesSession().also { it.onCatalog(catalog("base", "R2A.pgm")) }
        locked.startNew("Maple", sheetPages(metadata), listOf("R3.pgm"), metadata)
        assertEquals(listOf("R2Z.pgm", "R1.pgm", "R3.pgm"), locked.order())
    }
    @Test fun unplacedPageWhollyInAnotherMixDoesNotDisqualifyDraft() {
        val metadata = qualifying() + meta(4, listOf(Part(width = 10.0, length = 10.0)))
        val session = MixesSession().also { it.onCatalog(catalog("base", "R4.pgm")) }
        session.startNew("Maple", sheetPages(metadata), emptyList(), metadata)
        assertNotNull(session.smart)
        assertEquals(listOf("R3.pgm", "R2.pgm", "R1.pgm"), session.order())
    }
    @Test fun stageRevisionAndStartRemainingClearSmart() {
        val session = session()
        session.startSmart()
        assertNotNull(session.smart)
        // Staging a revision replaces the open new-mix draft; its suggestion must not linger.
        val group = group(session.catalog!!)
        assertTrue(session.stageRevision(group, group.primary, emptyList()))
        assertNull(session.smart)
        // Create-remaining needs no open draft; its freshly staged mix has no suggestion either.
        val template = catalog("base").files.single()
        val entries = listOf("a.pgm", "b.pgm").map { template.entries.single().copy(pgm = it, path = "Z:\\$it") }
        val c = catalog("base").copy(files = listOf(template.copy(entries = entries)))
        val remaining = MixesSession().also { it.onCatalog(c) }
        remaining.startSmart()
        assertFalse(remaining.startRemaining(group(c), listOf("a.pgm"))) // draft open: refused, suggestion kept
        assertNotNull(remaining.smart)
        remaining.discardMix(null)
        assertTrue(remaining.startRemaining(group(c), listOf("a.pgm")))
        assertNull(remaining.smart)
    }
    @Test fun recordWriteErrorsAreSwallowed() = runTest {
        val job = launchMixOrderRecordWrite(this, Dispatchers.IO, record = { error("boom") }) { }
        job.join()
        assertFalse(job.isCancelled)
        val writeFails = launchMixOrderRecordWrite(this, Dispatchers.IO, record = { buildMixOrderRecord(
            MixDrafts.startNew("A.mix", emptyList(), emptySet()), SmartMixContext(MixOrderMode.SMART, emptyList(), emptyList(), emptyList(), PRIOR_WEIGHTS),
            true, emptyList(), "J", "M", "t", "1", 0L) }) { error("disk full") }
        writeFails.join()
        assertFalse(writeFails.isCancelled)
    }
    @Test fun successfulApplyRecordsAfterObserverLeavesButRefusalsDoNot() = runTest {
        val session = MixesSession().also { it.startSmart() }
        val submitted = session.draft!!
        val context = session.smart
        val application = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            var records = 0
            val reply = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
            val operation = application.launch {
                submitMixWithLearning(application,
                    record = { buildMixOrderRecord(submitted, context, true, emptyList(), "J", "M", "t", "1", 0L) },
                    write = { records++ }, dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                ) { reply.await() }
            }
            val observer = launch { operation.join() }
            runCurrent()
            observer.cancel()
            reply.complete(MixFilesResult.Ok(catalog("saved")))
            runCurrent()
            assertEquals(1, records)
            submitMixWithLearning(application, record = { error("failed saves must not build records") }, write = { records++ }) {
                MixFilesResult.Offline
            }
            runCurrent()
            assertEquals(1, records)
        } finally { application.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }
    @Test fun learnedWeightsFallBackToPriorWhenNotFinite() {
        val good = MixOrderWeights(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(good, sanitizedSmartWeights(good))
        assertEquals(PRIOR_WEIGHTS, sanitizedSmartWeights(MixOrderWeights(listOf(1.0, Double.NaN, 0.0, 0.0, 0.0))))
        assertEquals(PRIOR_WEIGHTS, sanitizedSmartWeights(MixOrderWeights(listOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0))))
        assertEquals(PRIOR_WEIGHTS, loadSmartWeights { error("share offline") })
        assertEquals(good, loadSmartWeights { good })
    }

    private fun session() = MixesSession().also { it.onCatalog(catalog("base")) }
    private fun group(c: MixFilesCatalog) = groupMixFiles(c).single()
    private fun catalog(revision: String, pgm: String = "a.pgm") = MixFilesCatalog("J", "M", revision, listOf(
        MixFileRow("MapleMix.mix", MixFileKind.MIX, null, null, revision, null, false, null, null,
            listOf(MixFileEntry("Z:\\$pgm", pgm, linkedMapOf(), null, 1, null, true, false)))))
    private fun pages(vararg pgms: String) = pgms.mapIndexed { i, pgm -> SheetPage(i+1, listOf(pgm), false, false, null) }
}
