package com.kkc.sheettracker.sync

import com.kkc.sheettracker.data.IdlePhase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)

        phase.value = IdlePhase.SYNC_PAUSED
        waitUntil(2_000L) { controller.pauseCalls == 1 }
        assertEquals(1, controller.pauseCalls)

        phase.value = IdlePhase.SYNC_PAUSED // repeat, already paused — must not double-fire
        delay(50L)
        assertEquals(1, controller.pauseCalls)

        val resumeCallsBeforeActive = controller.resumeCalls
        phase.value = IdlePhase.ACTIVE
        waitUntil(2_000L) { controller.resumeCalls == resumeCallsBeforeActive + 1 }
        assertEquals(resumeCallsBeforeActive + 1, controller.resumeCalls)

        supervisor.close()
    }

    @Test
    fun `successful idle pause is exposed as paused and watchdog preserves it`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(
                    intervalMs = 40L,
                    autoStartOnFailure = false
                )
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)

        waitUntil(2_000L) { controller.pauseCalls == 1 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.PAUSED }
        delay(150L)

        assertEquals(SyncthingServiceStatus.PAUSED, supervisor.status.value.status)
        assertEquals(1, controller.pauseCalls)
        supervisor.close()
    }

    @Test
    fun `failed idle pause remains desired and retries on the next health check`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = true, pauseResults = ArrayDeque(listOf(false, true)))
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(
                    intervalMs = 40L,
                    autoStartOnFailure = false
                )
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)

        waitUntil(2_000L) { controller.pauseCalls >= 2 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.PAUSED }
        assertEquals(2, controller.pauseCalls)
        supervisor.close()
    }

    @Test
    fun `active phase reconciles a previously idle-paused service and check now reports running`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.ACTIVE)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(
                    intervalMs = 10_000L,
                    autoStartOnFailure = false
                )
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)
        waitUntil(2_000L) { controller.resumeCalls == 1 }

        supervisor.checkNow()
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.RUNNING }

        assertEquals(1, controller.resumeCalls)
        assertEquals(SyncthingServiceStatus.RUNNING, supervisor.status.value.status)
        supervisor.close()
    }

    @Test
    fun `failed active resume keeps paused status until a retry succeeds`() = runBlocking {
        val store = FakeSyncthingPreferencesStore("api-key")
        val controller = FakeSyncController(
            running = true,
            resumeResults = ArrayDeque(listOf(false, false, true))
        )
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val supervisor = SyncthingSupervisor(
            context = null,
            runtimeConfig = SyncthingRuntimeConfig(
                watchdog = SyncthingWatchdogConfig(
                    intervalMs = 10_000L,
                    autoStartOnFailure = false
                )
            ),
            preferencesStore = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            managerFactory = { controller }
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)
        waitUntil(2_000L) { controller.pauseCalls == 1 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.PAUSED }

        phase.value = IdlePhase.ACTIVE
        waitUntil(2_000L) { controller.resumeCalls == 1 }

        supervisor.checkNow()
        waitUntil(2_000L) { controller.resumeCalls == 2 }
        waitUntil(2_000L) { supervisor.status.value.lastCheckedAtMs != null }
        assertEquals(SyncthingServiceStatus.PAUSED, supervisor.status.value.status)

        supervisor.checkNow()
        waitUntil(2_000L) { controller.resumeCalls == 3 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.RUNNING }

        supervisor.close()
    }

    @Test
    fun `background idle phase does not pause syncthing`() = runBlocking {
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.ACTIVE)
        val supervisor = foregroundTestSupervisor(controller)

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(false)
        supervisor.observeIdlePhase(phase)
        phase.value = IdlePhase.SYNC_PAUSED
        delay(100L)

        assertEquals(0, controller.pauseCalls)
        supervisor.close()
    }

    @Test
    fun `background resumes an idle-paused syncthing service`() = runBlocking {
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val supervisor = foregroundTestSupervisor(controller)

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)
        waitUntil(2_000L) { controller.pauseCalls == 1 }

        supervisor.setAppForeground(false)
        waitUntil(2_000L) { controller.resumeCalls == 1 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.RUNNING }

        assertEquals(SyncthingServiceStatus.RUNNING, supervisor.status.value.status)
        supervisor.close()
    }

    @Test
    fun `foreground reentry pauses only when current phase remains idle`() = runBlocking {
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val supervisor = foregroundTestSupervisor(controller)

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(false)
        supervisor.observeIdlePhase(phase)
        waitUntil(2_000L) { controller.resumeCalls == 1 }
        supervisor.setAppForeground(true)
        waitUntil(2_000L) { controller.pauseCalls == 1 }

        supervisor.setAppForeground(false)
        waitUntil(2_000L) { controller.resumeCalls == 2 }
        phase.value = IdlePhase.ACTIVE
        supervisor.setAppForeground(true)
        delay(100L)

        assertEquals(1, controller.pauseCalls)
        supervisor.close()
    }

    @Test
    fun `close drains a pending background resume`() = runBlocking {
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val dispatcher = PausingCoroutineDispatcher()
        val supervisor = foregroundTestSupervisor(
            controller = controller,
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)
        waitUntil(2_000L) { controller.pauseCalls == 1 }

        dispatcher.pause()
        supervisor.setAppForeground(false)
        supervisor.close()
        dispatcher.resumeNewestFirst()

        waitUntil(2_000L) { controller.resumeCalls == 1 }
    }

    @Test
    fun `foreground check reconciles the current idle phase`() = runBlocking {
        val controller = FakeSyncController(running = true)
        val phase = MutableStateFlow(IdlePhase.SYNC_PAUSED)
        val dispatcher = PausingCoroutineDispatcher()
        val supervisor = foregroundTestSupervisor(
            controller = controller,
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        )

        supervisor.startMonitoring()
        waitUntil(2_000L) { supervisor.apiKey.value == "api-key" }
        supervisor.setAppForeground(true)
        supervisor.observeIdlePhase(phase)
        waitUntil(2_000L) { controller.pauseCalls == 1 }
        supervisor.setAppForeground(false)
        waitUntil(2_000L) { controller.resumeCalls == 1 }
        waitUntil(2_000L) { supervisor.status.value.status == SyncthingServiceStatus.RUNNING }

        try {
            dispatcher.pause()
            phase.value = IdlePhase.ACTIVE
            waitUntil(2_000L) { dispatcher.queuedCount() == 1 }
            supervisor.setAppForeground(true)
            supervisor.checkNow()

            dispatcher.runNewest()
            waitUntil(2_000L) { dispatcher.queuedCount() == 3 }
            dispatcher.runNewest()

            assertEquals(1, controller.pauseCalls)
        } finally {
            dispatcher.resumeNewestFirst()
            supervisor.close()
        }
    }
}

private fun foregroundTestSupervisor(
    controller: FakeSyncController,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) = SyncthingSupervisor(
    context = null,
    runtimeConfig = SyncthingRuntimeConfig(
        watchdog = SyncthingWatchdogConfig(intervalMs = 10_000L, autoStartOnFailure = false)
    ),
    preferencesStore = FakeSyncthingPreferencesStore("api-key"),
    scope = scope,
    managerFactory = { controller }
)

private class PausingCoroutineDispatcher(
    private val delegate: CoroutineDispatcher = Dispatchers.Default
) : CoroutineDispatcher() {
    private val lock = Any()
    private val queued = ArrayDeque<Pair<CoroutineContext, Runnable>>()
    private var paused = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val dispatchImmediately = synchronized(lock) {
            if (paused) {
                queued.addLast(context to block)
                false
            } else {
                true
            }
        }
        if (dispatchImmediately) {
            delegate.dispatch(context, block)
        }
    }

    fun pause() {
        synchronized(lock) {
            paused = true
        }
    }

    fun queuedCount(): Int = synchronized(lock) { queued.size }

    fun runNewest() {
        val task = synchronized(lock) {
            queued.removeLastOrNull()
        } ?: error("No queued coroutine task")
        task.second.run()
    }

    fun resumeNewestFirst() {
        val pending = synchronized(lock) {
            paused = false
            buildList {
                while (queued.isNotEmpty()) {
                    add(queued.removeLast())
                }
            }
        }
        pending.forEach { (context, block) ->
            delegate.dispatch(context, block)
        }
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
    private val running: Boolean,
    private val pauseResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val resumeResults: ArrayDeque<Boolean> = ArrayDeque()
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
        return pauseResults.removeFirstOrNull() ?: true
    }

    override suspend fun resumeSync(): Boolean {
        resumeCalls++
        return resumeResults.removeFirstOrNull() ?: true
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
