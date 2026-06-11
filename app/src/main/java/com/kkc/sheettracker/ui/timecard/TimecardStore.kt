package com.kkc.sheettracker.ui.timecard

import com.kkc.sheettracker.data.EmployeeInfo
import com.kkc.sheettracker.data.PunchStatus
import com.kkc.sheettracker.data.TimecardDiscovery
import com.kkc.sheettracker.data.TimecardRepository
import com.kkc.sheettracker.data.TimecardServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class TimecardUiState {
    object Searching : TimecardUiState()
    object NotFound : TimecardUiState()
    data class Ready(
        val pin: String = "",
        val employees: List<EmployeeInfo> = emptyList(),
        val matchedEmployee: EmployeeInfo? = null,
        val punchStatus: PunchStatus? = null,
        val isLoading: Boolean = false,
        val resultMessage: String? = null,
        val resultIsClockIn: Boolean = true
    ) : TimecardUiState()
}

class TimecardStore(
    private val config: TimecardServerConfig,
    private val discovery: TimecardDiscovery
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<TimecardUiState>(TimecardUiState.Searching)
    val state: StateFlow<TimecardUiState> = _state.asStateFlow()

    private var repo: TimecardRepository? = null

    init {
        scope.launch { initialize() }
    }

    private suspend fun initialize() {
        val manualIp = config.getManualIp()

        // Manual IP overrides everything — no discovery needed
        if (!manualIp.isNullOrBlank()) {
            val serverUrl = "http://${manualIp.trim()}:8765"
            connectToServer(serverUrl, clearCacheOnFailure = false)
            return
        }

        // Try cached URL first (fast path — avoids 1.5s mDNS wait)
        val cachedUrl = config.getCachedUrl()
        if (cachedUrl != null) {
            val succeeded = connectToServer(cachedUrl, clearCacheOnFailure = true)
            if (succeeded) return
            // Cached URL failed — fall through to discovery
        }

        // mDNS discovery
        val discoveredUrl = discovery.discover(1500L)
        if (discoveredUrl == null) {
            _state.value = TimecardUiState.NotFound
            return
        }
        config.setCachedUrl(discoveredUrl)
        connectToServer(discoveredUrl, clearCacheOnFailure = false)
    }

    // Returns true if the server was reachable and employees loaded (even if empty)
    private suspend fun connectToServer(serverUrl: String, clearCacheOnFailure: Boolean): Boolean {
        repo = TimecardRepository(serverUrl)
        return try {
            val employees = repo!!.getEmployees()
            _state.value = TimecardUiState.Ready(employees = employees)
            true
        } catch (e: Exception) {
            if (clearCacheOnFailure) config.setCachedUrl(null)
            repo = null
            false
        }
    }

    fun digitPressed(digit: String) {
        val current = _state.value as? TimecardUiState.Ready ?: return
        if (current.isLoading || current.resultMessage != null) return
        if (current.pin.length >= 3) return
        val newPin = current.pin + digit
        val matched = if (newPin.length == 3) current.employees.find { it.pin == newPin } else null
        _state.value = current.copy(
            pin = newPin,
            matchedEmployee = matched,
            punchStatus = null
        )
        if (newPin.length == 3) {
            scope.launch { fetchStatus(newPin) }
        }
    }

    fun backspacePressed() {
        val current = _state.value as? TimecardUiState.Ready ?: return
        if (current.isLoading || current.resultMessage != null) return
        if (current.pin.isEmpty()) return
        _state.value = current.copy(
            pin = current.pin.dropLast(1),
            matchedEmployee = null,
            punchStatus = null
        )
    }

    private suspend fun fetchStatus(pin: String) {
        val current = _state.value as? TimecardUiState.Ready ?: return
        _state.value = current.copy(isLoading = true)
        try {
            val status = repo?.getStatus(pin)
            (_state.value as? TimecardUiState.Ready)?.let {
                _state.value = it.copy(punchStatus = status, isLoading = false)
            }
        } catch (e: Exception) {
            (_state.value as? TimecardUiState.Ready)?.let {
                _state.value = it.copy(isLoading = false)
            }
        }
    }

    fun punchPressed() {
        val current = _state.value as? TimecardUiState.Ready ?: return
        val pin = current.pin
        if (pin.length != 3 || current.punchStatus?.found != true || current.isLoading || current.resultMessage != null) return
        scope.launch {
            val snapshot = _state.value as? TimecardUiState.Ready ?: return@launch
            _state.value = snapshot.copy(isLoading = true)
            try {
                val result = repo?.punch(pin) ?: return@launch
                val isClockIn = result.action == "in"
                val message = if (isClockIn) {
                    "Clocked in! Have a great shift."
                } else {
                    result.hoursWorked?.let { "You worked ${"%.1f".format(it)} hrs today. See you!" }
                        ?: "Clocked out."
                }
                (_state.value as? TimecardUiState.Ready)?.let {
                    _state.value = it.copy(
                        isLoading = false,
                        resultMessage = message,
                        resultIsClockIn = isClockIn
                    )
                }
                delay(2500)
                (_state.value as? TimecardUiState.Ready)?.let {
                    _state.value = it.copy(
                        pin = "",
                        matchedEmployee = null,
                        punchStatus = null,
                        resultMessage = null
                    )
                }
            } catch (e: Exception) {
                (_state.value as? TimecardUiState.Ready)?.let {
                    _state.value = it.copy(
                        isLoading = false,
                        resultMessage = "Error — please try again."
                    )
                }
                delay(2500)
                (_state.value as? TimecardUiState.Ready)?.let {
                    _state.value = it.copy(resultMessage = null)
                }
            }
        }
    }

    fun cancel() {
        scope.cancel()
    }
}
