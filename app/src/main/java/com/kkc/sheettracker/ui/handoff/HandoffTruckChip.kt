package com.kkc.sheettracker.ui.handoff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.HandoffFlagsChangeSignal
import com.kkc.sheettracker.data.HandoffFlagsClient
import com.kkc.sheettracker.data.HandoffFlagsService
import com.kkc.sheettracker.data.HandoffFlagsSnapshot
import com.kkc.sheettracker.data.HandoffTruckDisplay
import com.kkc.sheettracker.data.handoffTruckDisplay
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import com.kkc.sheettracker.data.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

data class HandoffItemUi(
    val display: HandoffTruckDisplay,
    /** Null on read-only screens. */
    val onSelectTruck: ((HandoffTruck?) -> Unit)?
)

class HandoffFlagsUiState(
    private val snapshot: HandoffFlagsSnapshot,
    private val editable: Boolean,
    /** One-shot user-facing text for a refused tap (null when nothing to show). */
    val message: String? = null,
    private val onConsumeMessage: () -> Unit = {},
    private val onSelect: (HandoffFlagRef, HandoffTruck?) -> Unit
) {
    fun uiFor(ref: HandoffFlagRef): HandoffItemUi = HandoffItemUi(
        display = handoffTruckDisplay(ref, snapshot),
        onSelectTruck = if (editable) ({ truck -> onSelect(ref, truck) }) else null
    )

    fun consumeMessage() = onConsumeMessage()
}

/** REST writes outlive the screen; the sidecar already guarantees delivery either way. */
private val handoffRestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Minimum gap between truck-list GETs per screen (HT applies pending requests on each GET). */
private const val TRUCK_LIST_MIN_INTERVAL_MS = 15_000L

@Composable
fun rememberHandoffFlags(basePath: String, jobFolderName: String, tabletId: String, editable: Boolean): HandoffFlagsUiState {
    val context = LocalContext.current
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val service = remember(basePath, tabletId) {
        HandoffFlagsService(
            baseDir = File(basePath),
            tabletId = tabletId,
            clientProvider = { adminSyncConfig.getServerUrl()?.let(::HandoffFlagsClient) },
            restScope = handoffRestScope
        )
    }
    val signal by HandoffFlagsChangeSignal.version.collectAsState()
    // Pending entries expire after 120 s; tick so an expired one stops showing without a file change.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    var truckList by remember(jobFolderName) { mutableStateOf<Map<HandoffFlagRef, HandoffTruckListEntry>?>(null) }
    var lastFetchMs by remember(jobFolderName) { mutableLongStateOf(0L) }
    LaunchedEffect(jobFolderName, signal) {
        val wait = TRUCK_LIST_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFetchMs)
        if (wait > 0) delay(wait)
        lastFetchMs = System.currentTimeMillis()
        // Keep the last good list when HT is unreachable rather than flipping every Auto back to unknown.
        service.fetchTruckList(jobFolderName)?.let { truckList = it }
    }
    val local by produceState(HandoffFlagsSnapshot(), basePath, jobFolderName, signal, tick) {
        value = service.loadLocal(jobFolderName)
    }
    var message by remember(jobFolderName) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    return remember(local, truckList, editable, service, jobFolderName, message) {
        HandoffFlagsUiState(
            snapshot = local.copy(truckList = truckList),
            editable = editable,
            message = message,
            onConsumeMessage = { message = null }
        ) { ref, truck ->
            scope.launch(Dispatchers.IO) {
                service.setTruck(jobFolderName, ref, truck).userMessage()?.let { message = it }
            }
        }
    }
}

private val TRUCK_MENU: List<Pair<HandoffTruck?, String>> =
    listOf(null to "Auto (office rules)") + HandoffTruck.entries.map { it to it.label }

@Composable
fun HandoffTruckChip(ui: HandoffItemUi, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val display = ui.display
    val text = buildString {
        append(display.label)
        if (display.isPending) append(" · pending")
        display.countLabel?.let { append(" · ").append(it) }
    }
    Box(modifier) {
        AssistChip(
            onClick = { if (ui.onSelectTruck != null) menuOpen = true },
            label = { Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = "Delivery handoff", modifier = Modifier.size(16.dp)) }
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TRUCK_MENU.forEach { (truck, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menuOpen = false; ui.onSelectTruck?.invoke(truck) },
                    trailingIcon = if (truck == display.selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                )
            }
        }
    }
}
