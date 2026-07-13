package com.kkc.sheettracker.ui.timecard

import com.kkc.sheettracker.data.EmployeeInfo
import com.kkc.sheettracker.data.PunchStatus
import com.kkc.sheettracker.data.TimecardDiscovery
import com.kkc.sheettracker.data.TimecardRepository
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.TimeclockMessagesRepository
import java.io.File
import java.time.LocalTime
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
    private val discovery: TimecardDiscovery,
    private val messagesRepo: TimeclockMessagesRepository,
    private val baseDir: File
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<TimecardUiState>(TimecardUiState.Searching)
    val state: StateFlow<TimecardUiState> = _state.asStateFlow()

    // Monotonically increments on each SUCCESSFUL punch (clock in/out). Observers (e.g. the
    // clock-before-update overlay) watch this to react to a completed punch without inspecting
    // the transient resultMessage (which is also set on errors).
    private val _punchCompletions = MutableStateFlow(0)
    val punchCompletions: StateFlow<Int> = _punchCompletions.asStateFlow()

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
            val resolvedEmployees = employees.map { emp ->
                emp.copy(
                    displayName = resolveDisplayOverride(getCustomDisplayName(emp.pin), emp.displayName)
                )
            }
            _state.value = TimecardUiState.Ready(employees = resolvedEmployees)
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
                // If local employee list was empty at startup, fall back to name from server status
                val matched = it.matchedEmployee
                    ?: if (status?.found == true && status.name != null)
                        EmployeeInfo(pin = pin, name = status.name, displayName = status.displayName ?: "")
                    else null
                val finalMatched = matched?.let { emp ->
                    emp.copy(
                        displayName = resolveDisplayOverride(getCustomDisplayName(emp.pin), emp.displayName)
                    )
                }
                _state.value = it.copy(punchStatus = status, isLoading = false, matchedEmployee = finalMatched)
            }
            if (status?.found == false) {
                delay(1000)
                (_state.value as? TimecardUiState.Ready)?.let {
                    if (it.pin == pin) _state.value = it.copy(pin = "", matchedEmployee = null, punchStatus = null)
                }
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
                val isLunch = isLunchTime()
                val message = if (isClockIn) {
                    messagesRepo.clockInMessage(isLunch)
                } else {
                    messagesRepo.clockOutMessage(isLunch, result.hoursWorked)
                }
                (_state.value as? TimecardUiState.Ready)?.let {
                    _state.value = it.copy(
                        isLoading = false,
                        resultMessage = message,
                        resultIsClockIn = isClockIn
                    )
                }
                _punchCompletions.value += 1
                delay(5500)
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
                delay(5500)
                (_state.value as? TimecardUiState.Ready)?.let {
                    _state.value = it.copy(resultMessage = null)
                }
            }
        }
    }

    private fun isLunchTime(): Boolean {
        val now = LocalTime.now()
        val minutes = now.hour * 60 + now.minute
        return minutes in 675..795  // 11:15 AM to 1:15 PM
    }

    fun reset() {
        val current = _state.value as? TimecardUiState.Ready ?: return
        _state.value = current.copy(
            pin = "",
            matchedEmployee = null,
            punchStatus = null,
            isLoading = false,
            resultMessage = null
        )
    }

    fun autoFill(pin: String) {
        if (pin.length != 3) return
        scope.launch {
            // Wait up to 2s for Ready state (server may still be discovering)
            repeat(20) {
                if (_state.value is TimecardUiState.Ready) return@repeat
                delay(100)
            }
            val current = _state.value as? TimecardUiState.Ready ?: return@launch
            if (current.pin.isNotEmpty() || current.isLoading || current.resultMessage != null) return@launch
            delay(350)
            for (digit in pin) {
                digitPressed(digit.toString())
                delay(130)
            }
        }
    }

    fun cancel() {
        scope.cancel()
    }

    companion object {
        /**
         * AUD-11 display-name precedence. Returns the override that should replace the real
         * name, or "" when the real name should be shown. Order: Hours custom name, then the
         * hub effective name, then no override (fall back to the real name). Blank/whitespace
         * overrides are ignored at each level.
         */
        fun resolveDisplayOverride(customName: String?, hubDisplayName: String?): String {
            customName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            hubDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return ""
        }
    }

    private fun parseName(fullName: String): String {
        val parts = fullName.split(",").map { it.trim() }
        return if (parts.size == 2) {
            "${parts[1]} ${parts[0]}"
        } else {
            fullName
        }
    }

    /**
     * Resolves the custom display name locally on the tablet.
     * Checks if 'feature_display_name' exists in the employee's inventory.
     */
    private fun getCustomDisplayName(pin: String): String? {
        val timeCardsDir = File(baseDir, ".time_cards")
        if (!timeCardsDir.exists() || !timeCardsDir.isDirectory) return null

        val employeesFile = File(timeCardsDir, "employees.json")
        if (!employeesFile.exists() || !employeesFile.isFile) return null

        return try {
            val employeesJson = employeesFile.readText()
            val jsonArray = org.json.JSONArray(employeesJson)
            var employeeName: String? = null
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("id") == pin) {
                    employeeName = obj.optString("name")
                    break
                }
            }
            if (employeeName == null) return null

            val folderName = parseName(employeeName)
            val profileFile = File(File(timeCardsDir, folderName), "profile.json")
            if (!profileFile.exists() || !profileFile.isFile) return null

            val profileJson = profileFile.readText()
            val profileObj = org.json.JSONObject(profileJson)
            val inventory = profileObj.optJSONArray("inventory")
            val displayName = profileObj.optString("displayName", "")

            var hasFeature = false
            if (inventory != null) {
                for (j in 0 until inventory.length()) {
                    if (inventory.optString(j) == "feature_display_name") {
                        hasFeature = true
                        break
                    }
                }
            }

            if (hasFeature && displayName.isNotBlank()) displayName.trim() else null
        } catch (e: Exception) {
            null
        }
    }
}
