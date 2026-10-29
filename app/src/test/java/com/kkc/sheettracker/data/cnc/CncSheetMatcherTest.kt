package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import org.junit.Assert.*
import org.junit.Test

internal fun cncTestJob(folder: String = "709 - BRAD TAYLOR", stem: String = "R82010BN") =
    Job(folder, folder.substringBefore(" - "), "Test", listOf(Material(
        "709 - 19mm Pre_Finished.pdf", "19mm Pre_Finished", 11, fileFingerprint = "fp1", metadata = MaterialMetadata(
            pages = listOf(PageMetadata(pageNumber = 11, sheetId = stem, sheetFiles = listOf(stem), thumbnailPath = "11.png"))))))
internal fun Job.cncInfo() = UnifiedJobInfo(folderName, jobNumber, jobName)

class CncSheetMatcherTest {
    private val job = cncTestJob()
    private val expected = CncSheetRef(job.folderName, job.materials.single().pdfFilename, 11)
    private fun index(jobs: List<Job> = listOf(job), roster: List<UnifiedJobInfo> = jobs.map { it.cncInfo() }) =
        buildCncProgramIndex(roster, jobs)
    private fun match(program: LoadedProgram, idx: CncProgramIndex = index()) =
        matchCncSheets(MachineStatus.Live(program, null), idx)
    private fun loaded(program: LoadedProgram = LoadedProgram("R82010BN", jobFolder = job.folderName), idx: CncProgramIndex = index()) =
        (match(program, idx).loaded as? CncLoadedMark.Matched)?.sheet

    @Test fun realBareStemMatchesPage11AndNormalizesCaseAndExtension() {
        assertEquals(expected, loaded())
        assertEquals(expected, loaded(LoadedProgram("r82010bn.PgM", jobFolder = "709 - brad taylor")))
    }
    @Test fun exactFolderBeatsDuplicateNumberButUnloadedDuplicateBlocksFallback() {
        val duplicate = cncTestJob("709 - OTHER")
        val idx = index(roster = listOf(job.cncInfo(), duplicate.cncInfo()))
        assertEquals(expected, loaded(idx = idx))
        assertNull(loaded(LoadedProgram("R82010BN", jobFolder = "709 - renamed"), idx))
    }
    @Test fun uniqueNumberFallbackPreservesLetter() {
        val lettered = cncTestJob("530a - NAME")
        val idx = index(listOf(lettered))
        assertEquals("530a - NAME", loaded(LoadedProgram("R82010BN", jobFolder = "530A - OLD"), idx)?.jobFolderName)
        assertNull(loaded(LoadedProgram("R82010BN", jobFolder = "530 - OLD"), idx))
    }
    @Test fun windowsFinishedPathFindsExactFolderOrUniqueNumberFallback() {
        listOf("709 - BRAD TAYLOR", "709 - renamed").forEach { folder ->
            val marks = matchCncSheets(MachineStatus.Live(null,
                FinishedProgram("R82010BN.PGM", "C:\\jobs\\$folder\\19mm\\R82010BN.pgm", end = "07:04:59")), index())
            assertEquals(expected, marks.lastFinished?.sheet)
            assertEquals("7:04", marks.lastFinished?.endLabel)
        }
    }
    @Test fun noJobHintNeverGuessesByStemAndMultiplePathHintsDoNotGuess() {
        assertTrue(match(LoadedProgram("R82010BN")).loaded is CncLoadedMark.Unmatched)
        assertNull(loaded(LoadedProgram("R82010BN", path = "C:/709 - BRAD TAYLOR/530 - OTHER/R82010BN.pgm"),
            index(roster = listOf(job.cncInfo(), cncTestJob("530 - OTHER").cncInfo()))))
    }
    @Test fun ambiguousNeedsExactlyOneResolvingCandidateAndIgnoresTopLevelHint() {
        val one = ProgramCandidate(jobFolder = job.folderName)
        val unknown = ProgramCandidate(jobFolder = "999 - unknown")
        fun ambiguous(candidates: List<ProgramCandidate>) = LoadedProgram("R82010BN", jobFolder = job.folderName,
            ambiguous = true, candidates = candidates)
        assertNull(loaded(ambiguous(listOf(unknown))))
        assertEquals(expected, loaded(ambiguous(listOf(one, unknown))))
        assertEquals(expected, loaded(ambiguous(listOf(one, one))))
        val other = cncTestJob("530 - OTHER")
        assertNull(loaded(ambiguous(listOf(one, ProgramCandidate(jobFolder = other.folderName))), index(listOf(job, other))))
    }
    @Test fun zaPairAndContinuationShareEligibleHead() {
        val material = job.materials.single().copy(metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 10, sheetId = "R1Z", thumbnailPath = "head.png"),
            PageMetadata(pageNumber = 11, sheetId = "R1A", hiddenInApp = true,
                isPartListContinuation = true, continuationHeadPage = 10))))
        val pair = job.copy(materials = listOf(material))
        for (stem in listOf("R1Z", "R1A")) {
            assertEquals(expected.copy(page = 10), loaded(LoadedProgram(stem, jobFolder = job.folderName), index(listOf(pair))))
        }
        val hidden = pair.copy(materials = listOf(material.copy(metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 10, sheetId = "R1Z", trackingExcluded = true))))))
        assertNull(loaded(LoadedProgram("R1Z", jobFolder = job.folderName), index(listOf(hidden))))
    }
    @Test fun duplicateStemAcrossMaterialsDoesNotGuess() {
        val conflict = job.copy(materials = job.materials + job.materials.single().copy(pdfFilename = "other.pdf"))
        assertNull(loaded(idx = index(listOf(conflict))))
    }
    @Test fun missingNamesBadTimesAndUnavailableAreSafe() {
        assertNull(match(LoadedProgram()).loaded)
        assertEquals("7:04", formatCncEndTime("07:04:59"))
        assertEquals("0:04", formatCncEndTime("00:04"))
        assertNull(formatCncEndTime("25:99:00"))
        assertNull(formatCncEndTime("07:04:99"))
        assertEquals(CncSheetMarks(), matchCncSheets(MachineStatus.Unavailable, index()))
        assertNull(matchCncSheets(MachineStatus.Live(null, FinishedProgram("R82010BN", end = "oops")), index()).lastFinished)
    }
    @Test fun indexReplacementAndActiveRosterRemovalInvalidateMatches() {
        assertNull(loaded(idx = index(listOf(job), emptyList())))
        assertNull(loaded(idx = index(listOf(cncTestJob(stem = "NEW")))))
    }
}

