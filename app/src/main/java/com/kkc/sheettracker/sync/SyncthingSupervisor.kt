package com.kkc.sheettracker.sync

import android.content.Context
import com.kkc.sheettracker.data.IdlePhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SyncthingServiceStatus {
    CHECKING,
    RUNNING,
    PAUSED,
    NOT_RUNNING,
    START_FAILED,
    API_KEY_REQUIRED
}

data class SyncthingStatusUiState(
    val status: SyncthingServiceStatus = SyncthingServiceStatus.API_KEY_REQUIRED,
    val isMonitoring: Boolean = false,
    val lastCheckedAtMs: Long? = null,
    val lastStartAttemptAtMs: Long? = null,
    val hasApiKey: Boolean = false
)

class SyncthingSupervisor(
    private val context: Context?,
    private val runtimeConfig: SyncthingRuntimeConfig,
    private val preferencesStore: SyncthingPreferencesStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val managerFactory: (String) -> SyncController = { apiKey ->
        SyncthingManager(
            context = requireNotNull(context) {
                "Context is required when using the default Syncthing manager factory."
            },
            config = runtimeConfig.copy(apiKey = apiKey)
        )
    }
) {

    private val checkMutex = Mutex()
    private var settingsObserverJob: Job? = null
    private var watchdogJob: Job? = null
    private var idlePhaseObserverJob: Job? = null
    private val idleReconcileMutex = Mutex()
    @Volatile
    private var idlePhaseSource: StateFlow<IdlePhase>? = null
    private var observedIdlePhase: IdlePhase? = null
    private val appIsForeground = AtomicBoolean(false)
    private val closeStarted = AtomicBoolean(false)
    private var idlePauseDesired = false
    private var idlePauseActual: Boolean? = null

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _status = MutableStateFlow(
        SyncthingStatusUiState(
            status = SyncthingServiceStatus.API_KEY_REQUIRED,
            isMonitoring = false,
            hasApiKey = false
        )
    )
    val status: StateFlow<SyncthingStatusUiState> = _status.asStateFlow()

    fun startMonitoring() {
        if (settingsObserverJob?.isActive == true) return

        settingsObserverJob = scope.launch {
            var previousKey = ""
            preferencesStore.settings.collect { settings ->
                val normalizedApiKey = settings.apiKey.trim()
                _apiKey.value = normalizedApiKey

                if (normalizedApiKey.isBlank()) {
                    idleReconcileMutex.withLock {
                        idlePauseActual = null
                    }
                    watchdogJob?.cancel()
                    watchdogJob = null
                    _status.value = _status.value.copy(
                        status = SyncthingServiceStatus.API_KEY_REQUIRED,
                        isMonitoring = true,
                        hasApiKey = false
                    )
                    previousKey = normalizedApiKey
                    return@collect
                }

                _status.value = _status.value.copy(
                    isMonitoring = true,
                    hasApiKey = true
                )

                if (previousKey != normalizedApiKey) {
                    idleReconcileMutex.withLock {
                        idlePauseActual = null
                    }
                }

                if (previousKey != normalizedApiKey || watchdogJob?.isActive != true) {
                    watchdogJob?.cancel()
                    watchdogJob = launchWatchdog(normalizedApiKey)
                }
                previousKey = normalizedApiKey
            }
        }
    }

    fun stopMonitoring() {
        settingsObserverJob?.cancel()
        watchdogJob?.cancel()
        settingsObserverJob = null
        watchdogJob = null
        _status.value = _status.value.copy(isMonitoring = false)
    }

    fun checkNow() {
        val currentApiKey = _apiKey.value.trim()
        if (currentApiKey.isBlank()) {
            _status.value = _status.value.copy(
                status = SyncthingServiceStatus.API_KEY_REQUIRED,
                hasApiKey = false
            )
            return
        }
        scope.launch {
            runHealthCheck(
                apiKey = currentApiKey,
                allowAutoStart = false,
                isAutomaticMonitoring = false
            )
        }
    }

    fun startNow() {
        val currentApiKey = _apiKey.value.trim()
        if (currentApiKey.isBlank()) {
            _status.value = _status.value.copy(
                status = SyncthingServiceStatus.API_KEY_REQUIRED,
                hasApiKey = false
            )
            return
        }
        scope.launch {
            val controller = managerFactory(currentApiKey)
            controller.startService()
            val startedAt = System.currentTimeMillis()
            _status.value = _status.value.copy(
                status = SyncthingServiceStatus.CHECKING,
                lastStartAttemptAtMs = startedAt,
                hasApiKey = true,
                isMonitoring = true
            )
            delay(runtimeConfig.watchdog.restartVerificationDelayMs)
            val isRunning = withContext(ioDispatcher) {
                controller.isServiceRunning()
            }
            if (isRunning) {
                reconcileIdleSync(currentApiKey, controller)
            }
            _status.value = _status.value.copy(
                status = statusForService(isRunning),
                hasApiKey = true,
                isMonitoring = true,
                lastCheckedAtMs = System.currentTimeMillis(),
                lastStartAttemptAtMs = startedAt
            )
        }
    }

    fun observeIdlePhase(phase: StateFlow<IdlePhase>) {
        idlePhaseSource = phase
        idlePhaseObserverJob?.cancel()
        idlePhaseObserverJob = scope.launch {
            phase.collect { p ->
                idleReconcileMutex.withLock {
                    observedIdlePhase = p
                    idlePauseDesired = appIsForeground.get() && p == IdlePhase.SYNC_PAUSED
                }
                val currentApiKey = _apiKey.value.trim()
                if (currentApiKey.isBlank()) return@collect
                reconcileIdleSync(currentApiKey)
                publishIdleStatusIfKnown()
            }
        }
    }

    fun setAppForeground(isForeground: Boolean) {
        appIsForeground.set(isForeground)
        scope.launch {
            idleReconcileMutex.withLock {
                idlePhaseSource?.value?.let { currentPhase ->
                    observedIdlePhase = currentPhase
                }
                idlePauseDesired = appIsForeground.get() && observedIdlePhase == IdlePhase.SYNC_PAUSED
            }
            val currentApiKey = _apiKey.value.trim()
            if (currentApiKey.isBlank()) return@launch
            reconcileIdleSync(currentApiKey)
            publishIdleStatusIfKnown()
        }
    }

    suspend fun saveApiKey(apiKey: String) {
        preferencesStore.saveApiKey(apiKey)
    }

    fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        appIsForeground.set(false)
        stopMonitoring()
        idlePhaseObserverJob?.cancel()
        scope.cancel()

        val currentApiKey = _apiKey.value.trim()
        val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        cleanupScope.launch {
            try {
                withTimeoutOrNull(CLOSE_RESUME_TIMEOUT_MS) {
                    val resumed = idleReconcileMutex.withLock {
                        idlePauseDesired = false
                        if (currentApiKey.isBlank() || idlePauseActual != true) {
                            return@withLock false
                        }
                        val succeeded = managerFactory(currentApiKey).resumeSync()
                        if (succeeded) {
                            idlePauseActual = false
                        }
                        succeeded
                    }
                    if (resumed && _status.value.status == SyncthingServiceStatus.PAUSED) {
                        _status.value = _status.value.copy(status = SyncthingServiceStatus.RUNNING)
                    }
                }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private fun launchWatchdog(apiKey: String): Job = scope.launch {
        runHealthCheck(
            apiKey = apiKey,
            allowAutoStart = runtimeConfig.watchdog.autoStartOnFailure,
            isAutomaticMonitoring = true
        )
        while (true) {
            delay(runtimeConfig.watchdog.intervalMs)
            runHealthCheck(
                apiKey = apiKey,
                allowAutoStart = runtimeConfig.watchdog.autoStartOnFailure,
                isAutomaticMonitoring = true
            )
        }
    }

    private suspend fun runHealthCheck(
        apiKey: String,
        allowAutoStart: Boolean,
        isAutomaticMonitoring: Boolean
    ) {
        data class PendingFailure(
            val status: SyncthingServiceStatus,
            val attemptedAt: Long?
        )

        val pendingFailure = checkMutex.withLock {
            _status.value = _status.value.copy(
                status = SyncthingServiceStatus.CHECKING,
                isMonitoring = true,
                hasApiKey = true
            )

            val controller = managerFactory(apiKey)
            val isRunning = withContext(ioDispatcher) {
                controller.isServiceRunning()
            }
            if (isRunning) {
                reconcileIdleSync(apiKey, controller)
            }
            val checkedAt = System.currentTimeMillis()

            if (isRunning) {
                _status.value = _status.value.copy(
                    status = statusForService(isRunning),
                    lastCheckedAtMs = checkedAt,
                    hasApiKey = true,
                    isMonitoring = true
                )
                return
            }

            if (allowAutoStart) {
                val attemptedAt = System.currentTimeMillis()
                controller.startService()
                delay(runtimeConfig.watchdog.restartVerificationDelayMs)
                val isRunningAfterStart = withContext(ioDispatcher) {
                    controller.isServiceRunning()
                }
                if (isRunningAfterStart) {
                    reconcileIdleSync(apiKey, controller)
                    _status.value = _status.value.copy(
                        status = statusForService(isRunningAfterStart),
                        lastCheckedAtMs = System.currentTimeMillis(),
                        lastStartAttemptAtMs = attemptedAt,
                        hasApiKey = true,
                        isMonitoring = true
                    )
                    null
                } else if (isAutomaticMonitoring) {
                    PendingFailure(
                        status = SyncthingServiceStatus.START_FAILED,
                        attemptedAt = attemptedAt
                    )
                } else {
                    _status.value = _status.value.copy(
                        status = SyncthingServiceStatus.START_FAILED,
                        lastCheckedAtMs = System.currentTimeMillis(),
                        lastStartAttemptAtMs = attemptedAt,
                        hasApiKey = true,
                        isMonitoring = true
                    )
                    null
                }
            } else {
                if (isAutomaticMonitoring) {
                    PendingFailure(
                        status = SyncthingServiceStatus.NOT_RUNNING,
                        attemptedAt = null
                    )
                } else {
                    _status.value = _status.value.copy(
                        status = SyncthingServiceStatus.NOT_RUNNING,
                        lastCheckedAtMs = checkedAt,
                        hasApiKey = true,
                        isMonitoring = true
                    )
                    null
                }
            }
        }

        if (pendingFailure != null) {
            // Automatic monitoring waits before surfacing failure to avoid transient false negatives.
            delay(runtimeConfig.watchdog.failureConfirmationWindowMs)
            val controller = managerFactory(apiKey)
            val recovered = withContext(ioDispatcher) {
                controller.isServiceRunning()
            }
            if (recovered) {
                reconcileIdleSync(apiKey, controller)
            }
            val recoveredStatus = statusForService(recovered)
            checkMutex.withLock {
                _status.value = _status.value.copy(
                    status = if (recovered) recoveredStatus else pendingFailure.status,
                    lastCheckedAtMs = System.currentTimeMillis(),
                    lastStartAttemptAtMs = pendingFailure.attemptedAt,
                    hasApiKey = true,
                    isMonitoring = true
                )
            }
        }
    }

    private suspend fun reconcileIdleSync(
        apiKey: String,
        controller: SyncController = managerFactory(apiKey)
    ) {
        idleReconcileMutex.withLock {
            val phase = idlePhaseSource?.value ?: observedIdlePhase ?: return@withLock
            observedIdlePhase = phase
            val shouldPause = appIsForeground.get() && phase == IdlePhase.SYNC_PAUSED
            idlePauseDesired = shouldPause
            val actual = idlePauseActual
            if ((shouldPause && actual == true) || (!shouldPause && actual == false)) {
                return@withLock
            }

            val succeeded = if (shouldPause) {
                controller.pauseSync()
            } else {
                controller.resumeSync()
            }
            if (succeeded) {
                idlePauseActual = shouldPause
            }
        }
    }

    private suspend fun publishIdleStatusIfKnown() {
        val (desired, actual) = idleReconcileMutex.withLock {
            idlePauseDesired to idlePauseActual
        }
        if (desired && actual == true) {
            _status.value = _status.value.copy(status = SyncthingServiceStatus.PAUSED)
        } else if (!desired && actual == false && _status.value.status == SyncthingServiceStatus.PAUSED) {
            _status.value = _status.value.copy(status = SyncthingServiceStatus.RUNNING)
        }
    }

    private suspend fun statusForService(isRunning: Boolean): SyncthingServiceStatus {
        if (!isRunning) return SyncthingServiceStatus.NOT_RUNNING
        val actual = idleReconcileMutex.withLock { idlePauseActual }
        return if (actual == true) {
            SyncthingServiceStatus.PAUSED
        } else {
            SyncthingServiceStatus.RUNNING
        }
    }

    private companion object {
        const val CLOSE_RESUME_TIMEOUT_MS = 2_000L
    }
}
