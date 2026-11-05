package com.kkc.sheettracker.data.cnc

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MixReloadRemindersTest {
    private val blue = CncMachines.scmNew
    private val red = CncMachines.scm

    @Test fun anyProgramOfTheMaterialInTheLoadedMixNeedsAReload() {
        assertTrue(needsMixReload(listOf("R340305Z", "R9"), setOf("R340301A.pgm", "r340305z")))
        assertTrue(needsMixReload(listOf("R340305Z"), setOf("C:\\NEW CNC\\715\\M\\R340305Z.PGM")))
        assertFalse(needsMixReload(listOf("R9", "R10"), setOf("R340305Z.pgm")))
        assertFalse("nothing loaded", needsMixReload(emptyList(), setOf("R340305Z.pgm")))
    }

    @Test fun unknownLoadedMixOrProgramsStillReminds() {
        assertTrue("CNC unreachable or older service", needsMixReload(null, setOf("R1.pgm")))
        assertTrue("material programs unknown", needsMixReload(listOf("R1"), null))
        assertTrue("material programs unknown", needsMixReload(listOf("R1"), emptySet()))
    }

    private class FakeState(var result: Result<MachineState>) : MachineStateSource {
        var fetches = 0
        override suspend fun fetch(): Result<MachineState> { fetches++; return result }
    }

    private fun TestScope.reminders(state: FakeState, programs: Set<String>?) = MixReloadReminders(
        backgroundScope,
        loadedMixSource = { id -> if (id == blue.id) state else null },
        materialPrograms = { _, _, _ -> programs },
        newId = { "r-${state.fetches}" },
    )

    @Test fun blueChangeWithTheMaterialLoadedRemindsOnceUntilDismissed() = runTest {
        val state = FakeState(Result.success(MachineState(maestroLoadedPrograms = listOf("R340305Z"))))
        val reminders = reminders(state, setOf("R340305Z.pgm", "R340306A.pgm"))
        reminders.filesChanged(blue.id, "715 - PRODEN", "1_2 Baltic Birch")
        reminders.filesChanged(blue.id, "715 - PRODEN", "1_2 Baltic Birch")
        runCurrent()
        val reminder = reminders.reminders.value.single()
        assertEquals("Blue SCM", reminder.machine)
        assertEquals("715 - PRODEN", reminder.job)
        assertEquals("1_2 Baltic Birch", reminder.material)
        reminders.dismiss(reminder.id)
        assertTrue(reminders.reminders.value.isEmpty())
    }

    @Test fun materialNotLoadedOrRedMachineDoesNotRemind() = runTest {
        val state = FakeState(Result.success(MachineState(maestroLoadedPrograms = listOf("R9"))))
        val reminders = reminders(state, setOf("R340305Z.pgm"))
        reminders.filesChanged(blue.id, "715", "M")
        reminders.filesChanged(red.id, "715", "M")
        runCurrent()
        assertTrue(reminders.reminders.value.isEmpty())
        assertEquals("Red has no cached mix to check", 1, state.fetches)
    }

    @Test fun unreachableBlueStillReminds() = runTest {
        val reminders = reminders(FakeState(Result.failure(java.io.IOException("offline"))), setOf("R1.pgm"))
        reminders.filesChanged(blue.id, "715", "M")
        runCurrent()
        assertEquals(1, reminders.reminders.value.size)
    }

    @Test fun programsAreReadWhenTheChangeIsReportedNotAfterTheFetch() = runTest {
        var programs: Set<String>? = setOf("R1.pgm")
        val state = FakeState(Result.success(MachineState(maestroLoadedPrograms = listOf("R9"))))
        val reminders = MixReloadReminders(backgroundScope, { state }, { _, _, _ -> programs }, newId = { "r" })
        reminders.filesChanged(blue.id, "715", "M")
        programs = setOf("R9.pgm") // The catalog changes after the report (e.g. a refresh after a move).
        runCurrent()
        assertTrue(reminders.reminders.value.isEmpty())
    }
}
