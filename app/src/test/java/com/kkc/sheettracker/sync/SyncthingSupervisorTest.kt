package com.kkc.sheettracker.sync

import com.kkc.sheettracker.data.IdlePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncthingSupervisorTest {

    @Test
    fun `auto-start is triggered when check fails`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = false)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(
                    intervalMs = 10_000L,
                    autoStartOnFailure = true,
                    restartVerificationDelayMs = 10L,
                    failureConfirmationWindowMs = 10L
                )
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { controller.startCalls > 0 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.START_FAILED }

        assertTrue(controller.startCalls > 0)
        assertEquals(SyncthingServiceStatus.START_FAILED, supervisor.status.value.status)

        supervisor.close()
    }

    @Test
    fun `watchdog performs repeated checks based on interval`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = true)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(
                    intervalMs = 40L,
                    autoStartOnFailure = true,
                    restartVerificationDelayMs = 10L
                )
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { controller.checkCalls >= 2 }

        assertTrue(controller.checkCalls >= 2)
        assertEquals(SyncthingServiceStatus.RUNNING, supervisor.status.value.status)

        supervisor.close()
    }

    @Test
    fun `missing key produces api key required status`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("")
        val controller = FakeSyncController(running = true)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.API_KEY_REQUIRED }

        assertEquals(SyncthingServiceStatus.API_KEY_REQUIRED, supervisor.status.value.status)
        assertEquals(0, controller.checkCalls)

        supervisor.close()
    }

    @Test
    fun `idle phase pause and resume call syncthing once per transition`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.ACTIVE)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(intervalMs = 10_000L, autoStartOnFailure = false)
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.observeIdlePhase(phase)

        phase.value = IdlePhase.SYNC_PAUSED
        waitUntil(2_000L) { controller.pauseCalls == 1 }
        assertEquals(1, controller.pauseCalls)

        phase.value = IdlePhase.SYNC_PAUSED // repeat, already paused — must not double-fire
        delay(50L)
        assertEquals(1, controller.pauseCalls)

        phase.value = IdlePhase.ACTIVE
        waitUntil(2_000L) { controller.resumeCalls == 1 }
        assertEquals(1, controller.resumeCalls)

        supervisor.close()
    }
}

private class FakeSyncthingPreferencesStore(
    initialApiKey: String
) : SyncthingPreferencesStore {
    private val state = MutableStateFlow(SyncthingUserSettings(initialApiKey))

    override val settings: Flow<SyncthingUserSettings> = state

    override suspend fun saveApiKey(apiKey: String) {
        state.value = SyncthingUserSettings(apiKey)
    }
}

private class FakeSyncController(
    private val running: Boolean
) : SyncController {
    var checkCalls: Int = 0
    var startCalls: Int = 0
    var pauseCalls: Int = 0
    var resumeCalls: Int = 0

    override suspend fun isServiceRunning(): Boolean {
        checkCalls++
        return running
    }

    override fun startService() {
        startCalls++
    }

    override fun stopService() = Unit

    override suspend fun pauseSync(): Boolean {
        pauseCalls++
        return true
    }

    override suspend fun resumeSync(): Boolean {
        resumeCalls++
        return true
    }
}

private suspend fun waitUntil(
    timeoutMs: Long,
    predicate: () -> Boolean
) {
    val startAt = System.currentTimeMillis()
    while (!predicate()) {
        if (System.currentTimeMillis() - startAt >= timeoutMs) {
            throw AssertionError("Condition not met within ${timeoutMs}ms")
        }
        delay(20L)
    }
}
