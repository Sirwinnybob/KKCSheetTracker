package com.kkc.sheettracker.ui.handoff

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File

data class HandoffItemUi(
    val display: HandoffTruckDisplay,
    /** Null on read-only screens. */
    val onSelectTruck: ((HandoffTruck?) -> Unit)?
)

/**
 * Refused-tap messages waiting to be shown, oldest first. Backed by Compose state so hosts that
 * read [head] recompose when it changes; safe to [offer] from any thread (a tap's result is
 * posted from the app-lifetime REST scope).
 */
class HandoffMessageQueue {
    private var items by mutableStateOf<List<String>>(emptyList())

    /** The message to show now, or null. */
    val head: String? get() = items.firstOrNull()

    /**
     * Appends [text]. An identical message already waiting at the tail is dropped: it would add
     * nothing, and equal neighbours would keep the host's `LaunchedEffect(message)` key unchanged.
     */
    @Synchronized
    fun offer(text: String) {
        if (items.lastOrNull() != text) items = items + text
    }

    /** Removes the head (the message the host just finished showing). */
    @Synchronized
    fun consume() {
        if (items.isNotEmpty()) items = items.drop(1)
    }
}

/**
 * One select callback per ref, shared by every [HandoffFlagsUiState] built for a screen, so a row's
 * [HandoffItemUi] stays equal across snapshot refreshes and Compose can skip recomposing it.
 */
internal class HandoffSelectorCache(private val onSelect: (HandoffFlagRef, HandoffTruck?) -> Unit) {
    private val selectors = HashMap<HandoffFlagRef, (HandoffTruck?) -> Unit>()

    fun forRef(ref: HandoffFlagRef): (HandoffTruck?) -> Unit =
        selectors.getOrPut(ref) { { truck -> onSelect(ref, truck) } }
}

class HandoffFlagsUiState internal constructor(
    private val snapshot: HandoffFlagsSnapshot,
    private val editable: Boolean,
    private val messages: HandoffMessageQueue,
    private val selectors: HandoffSelectorCache
) {
    private val cache = HashMap<HandoffFlagRef, HandoffItemUi>()

    /** The oldest unshown refused-tap message (null when nothing to show); see [consumeMessage]. */
    val message: String? get() = messages.head

    /** One [HandoffItemUi] per ref for this snapshot (call from composition only). */
    fun uiFor(ref: HandoffFlagRef): HandoffItemUi = cache.getOrPut(ref) {
        HandoffItemUi(
            display = handoffTruckDisplay(ref, snapshot),
            onSelectTruck = if (editable) selectors.forRef(ref) else null
        )
    }

    /** Drops the head message so the next queued one (if any) becomes [message]. */
    fun consumeMessage() = messages.consume()
}

/** REST writes outlive the screen; the sidecar already guarantees delivery either way. */
private val handoffRestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Minimum gap between truck-list GETs per (screen, job) (HT applies pending requests on each GET). */
private const val TRUCK_LIST_MIN_INTERVAL_MS = 15_000L

/** Re-read the local snapshot this often so request/setAt changes show without a file event. */
private const val LOCAL_REFRESH_INTERVAL_MS = 30_000L

private const val TAG = "HandoffTruckChip"

@Composable
fun rememberHandoffFlags(basePath: String, jobFolderName: String, tabletId: String, editable: Boolean): HandoffFlagsUiState {
    val context = LocalContext.current
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val service = remember(basePath, tabletId, editable) {
        HandoffFlagsService(
            baseDir = File(basePath),
            tabletId = tabletId,
            clientProvider = { adminSyncConfig.getServerUrl()?.let(::HandoffFlagsClient) },
            restScope = handoffRestScope,
            readOnly = !editable
        )
    }
    // Key the whole screen/job state lifetime, since produceState keys alone restart the producer
    // but retain its last value. A new owner must begin without the previous owner's truck list,
    // local snapshot, messages, or selectors.
    return key(service, jobFolderName) {
        // ONE long-lived collector per owner: a bump never cancels an in-flight GET, bumps that
        // arrive meanwhile (or during the cool-down) collapse into one more fetch. The signal and
        // the clock are read here, not in composition, so the host screen never recomposes for them.
        val truckList by produceState<Map<HandoffFlagRef, HandoffTruckListEntry>?>(null, service, jobFolderName) {
            // StateFlow is already conflated: bumps during a fetch or the cool-down collapse into one.
            HandoffFlagsChangeSignal.version.collect {
                // Keep the last good list when HT is unreachable rather than flipping every Auto back to unknown.
                service.fetchTruckList(jobFolderName)?.let { value = it }
                delay(TRUCK_LIST_MIN_INTERVAL_MS)
            }
        }

        // Pending entries can expire (24 h backstop) or be superseded by HT; the ticker re-reads
        // the local files periodically without any composition-level state.
        val local by produceState(HandoffFlagsSnapshot(), service, jobFolderName) {
            val ticker = flow { while (true) { emit(Unit); delay(LOCAL_REFRESH_INTERVAL_MS) } }
            combine(HandoffFlagsChangeSignal.version, ticker) { _, _ -> }
                .collectLatest { value = service.loadLocal(jobFolderName) }
        }
        val messages = remember { HandoffMessageQueue() }
        val selectors = remember(messages) {
            HandoffSelectorCache { ref, truck ->
                // App-lifetime scope: leaving the screen mid-tap cannot cancel the write; the result
                // is posted to this screen's queue (dropped with the screen if it is already gone).
                handoffRestScope.launch {
                    try {
                        service.setTruck(jobFolderName, ref, truck).userMessage()?.let(messages::offer)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Truck flag tap failed for ${ref.key} in $jobFolderName", e)
                        messages.offer("Couldn't save this change.")
                    }
                }
            }
        }
        remember(local, truckList, editable, selectors, messages) {
            HandoffFlagsUiState(
                snapshot = local.copy(truckList = truckList),
                editable = editable,
                messages = messages,
                selectors = selectors
            )
        }
    }
}

private val TRUCK_MENU: List<Pair<HandoffTruck?, String>> =
    listOf(null to "Auto (office rules)") + HandoffTruck.entries.map { it to it.label }

private fun chipText(display: HandoffTruckDisplay): String = buildString {
    append(display.label)
    if (display.isPending) append(" · pending")
    display.countLabel?.let { append(" · ").append(it) }
}

@Composable
fun HandoffTruckChip(ui: HandoffItemUi, modifier: Modifier = Modifier) {
    val display = ui.display
    val text = chipText(display)
    if (ui.onSelectTruck == null) {
        ReadOnlyHandoffChip(text, modifier)
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            label = { Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = "Delivery handoff", modifier = Modifier.size(16.dp)) }
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TRUCK_MENU.forEach { (truck, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menuOpen = false; ui.onSelectTruck.invoke(truck) },
                    trailingIcon = if (truck == display.selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                )
            }
        }
    }
}

/**
 * Read-only screens (archive): a plain outlined label, not a disabled chip, so it has no ripple,
 * no click semantics and keeps full-contrast text. No shadow or translucent fill involved.
 */
@Composable
private fun ReadOnlyHandoffChip(text: String, modifier: Modifier) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.LocalShipping,
            contentDescription = "Delivery handoff",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
