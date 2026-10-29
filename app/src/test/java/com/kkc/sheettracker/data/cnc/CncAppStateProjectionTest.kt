package com.kkc.sheettracker.data.cnc

import org.junit.Assert.*
import org.junit.Test

class CncAppStateProjectionTest {
    @Test fun hintsIgnorePollingNoiseAndSelectOnlyUniqueActiveJobs() {
        val job = cncTestJob()
        val status = MachineStatus.Live(LoadedProgram("R82010BN", jobFolder = job.folderName), null)
        assertEquals(cncProgramHints(status), cncProgramHints(status.copy(
            asOf = "tomorrow", loaded = status.loaded!!.copy(running = true, loadedAt = "old"))))
        assertEquals(setOf(job.folderName), cncHydrationFolders(cncProgramHints(status), listOf(job.cncInfo(), cncTestJob("888 - OTHER").cncInfo())))
        val fallback = status.copy(loaded = status.loaded!!.copy(jobFolder = "709 - OLD"))
        assertTrue(cncHydrationFolders(cncProgramHints(fallback), listOf(job.cncInfo(), cncTestJob("709 - DUPLICATE").cncInfo())).isEmpty())
        assertEquals(CncProgramHints.EMPTY, cncProgramHints(MachineStatus.Unavailable))
    }
    @Test fun ambiguousAndFinishedHintsAreDeduplicated() {
        val job = cncTestJob()
        val candidate = ProgramCandidate(jobFolder = job.folderName)
        val status = MachineStatus.Live(LoadedProgram("R1", ambiguous = true, candidates = listOf(candidate, candidate)),
            FinishedProgram("R2", path = "C:\\jobs\\888 - OTHER\\R2.pgm"))
        assertEquals(2, cncProgramHints(status).programs.size)
    }
}

