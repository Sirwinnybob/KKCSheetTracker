package com.kkc.sheettracker.data.cnc

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MachineStatusStoreTest {
    private val loaded = MachineState(currentProgram = LoadedProgram("R82010BN", false, "2026-10-23T17:00:00"))
    private val failure = Result.failure<MachineState>(java.io.IOException("offline"))

    @Test fun oneMachineFailingDoesNotHideOtherMachine() = runTest {
        var blue = Result.success(loaded)
        val redStore = MachineStatusStore(MachineStateSource { Result.success(loaded) }, backgroundScope, { testScheduler.currentTime })
        val blueStore = MachineStatusStore(MachineStateSource { blue }, backgroundScope, { testScheduler.currentTime }, offlineGraceMs = 15_000L)
        val redLease = redStore.acquire(); val blueLease = blueStore.acquire(); runCurrent()
        blue = failure
        advanceTimeBy(15_000); runCurrent()
        assertTrue(redStore.status.value is MachineStatus.Live)
        assertEquals(MachineStatus.Unavailable, blueStore.status.value)
        redLease.close(); blueLease.close()
    }
    @Test fun redOfflineDoesNotHideBlueWithIdenticalProgramNames() = runTest {
        var red = Result.success(loaded)
        val redStore = MachineStatusStore(MachineStateSource { red }, backgroundScope, { testScheduler.currentTime }, offlineGraceMs = 15_000L)
        val blueStore = MachineStatusStore(MachineStateSource { Result.success(loaded) }, backgroundScope, { testScheduler.currentTime }, offlineGraceMs = 15_000L)
        val leases = listOf(redStore.acquire(), blueStore.acquire()); runCurrent()
        red = failure
        advanceTimeBy(15_000); runCurrent()
        assertEquals(MachineStatus.Unavailable, redStore.status.value)
        assertEquals("R82010BN", (blueStore.status.value as MachineStatus.Live).loaded?.name)
        leases.forEach { it.close() }
    }
    @Test fun blueFeedExpiresIndependentlyAfterFifteenSeconds() = runTest {
        var response = Result.success(loaded)
        val store = MachineStatusStore(MachineStateSource { response }, backgroundScope, { testScheduler.currentTime }, offlineGraceMs = 15_000L)
        val handle = store.acquire(); runCurrent()
        response = failure
        advanceTimeBy(15_000); runCurrent()
        assertEquals(MachineStatus.Unavailable, store.status.value)
        handle.close()
    }
    @Test fun startsUnavailableAndDoesNotPollWithoutObservers() = runTest {
        var requests = 0
        val store = MachineStatusStore(MachineStateSource { requests++; Result.success(loaded) }, backgroundScope, { testScheduler.currentTime })
        advanceTimeBy(200_000); runCurrent()
        assertEquals(0, requests)
        assertEquals(MachineStatus.Unavailable, store.status.value)
    }
    @Test fun twoObserversSharePollerAndPollAtFiveSecondIntervals() = runTest {
        var requests = 0
        var inflight = 0
        var maximumConcurrentRequests = 0
        val store = MachineStatusStore(MachineStateSource {
            requests++; inflight++; maximumConcurrentRequests = maxOf(maximumConcurrentRequests, inflight)
            delay(10); inflight--; Result.success(loaded)
        }, backgroundScope, { testScheduler.currentTime })
        val a = store.acquire(); val b = store.acquire()
        runCurrent(); assertEquals(1, requests)
        advanceTimeBy(4_999); runCurrent(); assertEquals(1, requests)
        advanceTimeBy(1); runCurrent(); assertEquals(2, requests)
        a.close(); a.close()
        advanceTimeBy(5_000); runCurrent(); assertEquals(3, requests)
        b.close(); advanceTimeBy(10_000); runCurrent(); assertEquals(3, requests)
        assertEquals(1, maximumConcurrentRequests)
    }
    @Test fun failureRetainsSnapshotUntilDeadline() = runTest {
        var response = Result.success(loaded)
        val store = MachineStatusStore(MachineStateSource { response }, backgroundScope, { testScheduler.currentTime })
        val handle = store.acquire(); runCurrent()
        val live = store.status.value
        response = failure
        advanceTimeBy(179_999); runCurrent(); assertEquals(live, store.status.value)
        advanceTimeBy(1); runCurrent(); assertEquals(MachineStatus.Unavailable, store.status.value)
        response = Result.success(loaded)
        advanceTimeBy(5_000); runCurrent(); assertTrue(store.status.value is MachineStatus.Live)
        handle.close()
    }
    @Test fun successResetsDeadlineAndLogsAvailabilityTransitionsOnly() = runTest {
        val transitions = mutableListOf<Boolean>()
        var response = Result.success(loaded)
        val store = MachineStatusStore(MachineStateSource { response }, backgroundScope, { testScheduler.currentTime }, onAvailabilityChanged = transitions::add)
        val handle = store.acquire(); runCurrent()
        advanceTimeBy(5_000); runCurrent() // second success at 5000
        response = failure
        advanceTimeBy(179_999); runCurrent(); assertTrue(store.status.value is MachineStatus.Live)
        advanceTimeBy(1); runCurrent(); assertEquals(MachineStatus.Unavailable, store.status.value)
        assertEquals(listOf(true, false), transitions)
        handle.close()
    }
    @Test fun validNullClearsImmediatelyAndNoReportStillAllowsOldLoadedProgram() = runTest {
        var response = loaded.copy(haveReport = false, lastProgram = FinishedProgram("R2", end = "07:04:00"))
        val store = MachineStatusStore(MachineStateSource { Result.success(response) }, backgroundScope, { testScheduler.currentTime })
        val handle = store.acquire(); runCurrent()
        val live = store.status.value as MachineStatus.Live
        assertEquals("R82010BN", live.loaded?.name)
        assertNull(live.lastFinished)
        response = MachineState()
        advanceTimeBy(5_000); runCurrent()
        assertNull((store.status.value as MachineStatus.Live).loaded)
        handle.close()
    }
    @Test fun lastObserverCancelsInflightRequestAndDeadlineDoesNotWaitForFetch() = runTest {
        var requests = 0
        var cancelled = false
        val store = MachineStatusStore(MachineStateSource {
            if (++requests == 1) Result.success(loaded) else try { awaitCancellation() } finally { cancelled = true }
        }, backgroundScope, { testScheduler.currentTime })
        val handle = store.acquire(); runCurrent()
        advanceTimeBy(180_000); runCurrent()
        assertEquals(MachineStatus.Unavailable, store.status.value)
        assertFalse(cancelled)
        handle.close(); runCurrent(); assertTrue(cancelled)
    }
    @Test fun expiredStateIsHiddenBeforeResumeFetchCompletes() = runTest {
        var requests = 0
        val store = MachineStatusStore(MachineStateSource {
            if (++requests == 1) Result.success(loaded) else awaitCancellation()
        }, backgroundScope, { testScheduler.currentTime })
        store.acquire().also { runCurrent(); it.close() }
        advanceTimeBy(500_000); runCurrent()
        val handle = store.acquire()
        assertEquals(MachineStatus.Unavailable, store.status.value)
        runCurrent(); handle.close()
    }
    @Test fun oldGenerationCannotPublishAfterRestart() = runTest {
        var requests = 0
        val store = MachineStatusStore(MachineStateSource {
            if (++requests == 1) withContext(NonCancellable) { delay(100); Result.success(loaded) }
            else failure
        }, backgroundScope, { testScheduler.currentTime })
        val old = store.acquire(); runCurrent(); old.close()
        val new = store.acquire(); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(MachineStatus.Unavailable, store.status.value)
        new.close()
    }
}
