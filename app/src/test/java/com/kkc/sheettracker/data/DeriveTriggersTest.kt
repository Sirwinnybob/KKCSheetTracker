package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AppUiState
import com.kkc.sheettracker.data.models.ScanSnapshot
import com.kkc.sheettracker.data.models.ScanSnapshotState
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rescan flips the scan status LOADING -> READY (and bumps the generation only if something
 * changed). Only a new generation or progress version carries new data, so only those may trigger
 * a full app-state derive (~35-60 ms and ~20 MB of garbage each). A status flip on its own used to
 * derive twice per refresh — once on LOADING with the old data, once on READY.
 */
class DeriveTriggersTest {

    private fun state(status: ScanStatus, generation: Long, error: String? = null) =
        ScanSnapshotState(status = status, snapshot = ScanSnapshot(generation = generation), errorMessage = error)

    private fun <T> withTriggers(
        scan: MutableStateFlow<ScanSnapshotState>,
        progress: MutableStateFlow<Long> = MutableStateFlow(0L),
        block: suspend (derives: MutableList<DeriveInput>) -> T
    ): T = runBlocking {
        val recompute = MutableSharedFlow<Long>(replay = 1).also { it.tryEmit(0L) }
        val derives = java.util.Collections.synchronizedList(mutableListOf<DeriveInput>())
        val job = deriveTriggers(scan, progress, recompute, progressDebounceMs = { 0L })
            .onEach { derives += it }
            .launchIn(CoroutineScope(Dispatchers.Default))
        try {
            delay(150)
            block(derives)
        } finally {
            job.cancel()
        }
    }

    private suspend fun settle() = delay(150)

    @Test
    fun startingUpDerivesOnce() {
        withTriggers(MutableStateFlow(state(ScanStatus.READY, 5))) { derives ->
            assertEquals(1, derives.size)
        }
    }

    @Test
    fun aRefreshThatChangesDataDerivesOnceNotTwice() {
        val scan = MutableStateFlow(state(ScanStatus.READY, 5))
        withTriggers(scan) { derives ->
            scan.value = state(ScanStatus.LOADING, 5)
            settle()
            assertEquals("LOADING alone carries no new data", 1, derives.size)
            scan.value = state(ScanStatus.READY, 6)
            settle()
            assertEquals(2, derives.size)
            assertEquals(6L, derives.last().scanState.snapshot.generation)
        }
    }

    @Test
    fun aRefreshThatFindsNothingNewNeverDerives() {
        val scan = MutableStateFlow(state(ScanStatus.READY, 5))
        withTriggers(scan) { derives ->
            repeat(3) {
                scan.value = state(ScanStatus.LOADING, 5)
                settle()
                scan.value = state(ScanStatus.READY, 5)
                settle()
            }
            assertEquals(1, derives.size)
        }
    }

    @Test
    fun aNewProgressVersionDerives() {
        val scan = MutableStateFlow(state(ScanStatus.READY, 5))
        val progress = MutableStateFlow(1L)
        withTriggers(scan, progress) { derives ->
            progress.value = 2L
            settle()
            assertEquals(2, derives.size)
            assertEquals(2L, derives.last().progressVersion)
        }
    }

    @Test
    fun statusAndErrorFlipsUpdateTheUiFlagsWithoutADerive() {
        val loading = AppUiState(isRefreshing = false).withScanStatus(ScanStatus.LOADING, null)
        assertTrue(loading.isRefreshing)
        assertNull(loading.errorMessage)

        val ready = loading.withScanStatus(ScanStatus.READY, null)
        assertFalse(ready.isRefreshing)

        val failed = ready.withScanStatus(ScanStatus.ERROR, "boom")
        assertFalse(failed.isRefreshing)
        assertEquals("boom", failed.errorMessage)
    }
}
