package com.kkc.sheettracker.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kkc.sheettracker.KKCApplication
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.MachineAccess
import com.kkc.sheettracker.data.cnc.machineAccess
import com.kkc.sheettracker.data.mixfiles.MixFilesClient
import com.kkc.sheettracker.data.mixfiles.SecondPassDrafts
import com.kkc.sheettracker.data.mixfiles.SecondPassField
import com.kkc.sheettracker.data.mixfiles.SecondPassOutcome
import com.kkc.sheettracker.data.mixfiles.SecondPassRevisionSnapshot
import com.kkc.sheettracker.data.mixfiles.SecondPassRevisionSupport
import com.kkc.sheettracker.data.mixfiles.SecondPassRunner
import com.kkc.sheettracker.data.mixfiles.SecondPassStep
import com.kkc.sheettracker.data.mixfiles.TriState
import com.kkc.sheettracker.data.mixfiles.canEditPgm
import com.kkc.sheettracker.data.mixfiles.canonicalPgms
import com.kkc.sheettracker.data.mixfiles.planSecondPass
import com.kkc.sheettracker.data.mixfiles.pgmIdentity
import com.kkc.sheettracker.data.mixfiles.secondPassRequestIds
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import com.kkc.sheettracker.data.mixservice.validPgmHash
import com.kkc.sheettracker.ui.mixes.tabletDeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the CNC reported for this material; null fields mean the read failed. */
internal data class SheetSecondPassSnapshot(
    val access: MachineAccess?,
    val revertSupported: Boolean,
    /** PGM names in the CNC material folder; null when unreadable or empty. */
    val inventory: List<String>?,
    val history: PgmEditHistoryView?,
)

internal enum class SheetSecondPassDisplay { LOADING, APPLYING, ON, SUPER, PARTIAL, OFF, UNKNOWN }

internal data class SheetSecondPassUi(
    val display: SheetSecondPassDisplay,
    val statusText: String,
    /** The action a tap offers: true turns 2nd pass on, false turns it off. */
    val turnOn: Boolean,
    /** Why a tap can't change anything; null when the toggle is allowed. */
    val blockedReason: String?,
    /** Machining-editable programs of this sheet (A-sides never are). */
    val pgms: List<String>,
) {
    val canToggle: Boolean get() = blockedReason == null
}

/** Sheet programs the 2nd-pass toggle acts on. A-sides stay untouched, matching Mixes. */
internal fun sheetSecondPassPgms(sheetPgms: List<String>): List<String> = canonicalPgms(sheetPgms).filter(::canEditPgm)

/**
 * Status and toggle availability for one sheet. Mirrors the Mixes rules: completed (locked) sheets are not
 * edited, an unknown CNC ledger is never shown as clean, and turning off needs the service's revert support.
 */
internal fun sheetSecondPassUi(
    sheetPgms: List<String>,
    snapshot: SheetSecondPassSnapshot?,
    machineName: String,
    loading: Boolean,
    applying: Boolean,
    locked: Boolean,
    readOnly: Boolean,
    otherBlock: String?,
    punloadFixed: Boolean,
): SheetSecondPassUi {
    val pgms = sheetSecondPassPgms(sheetPgms)
    fun unknown(text: String, reason: String) = SheetSecondPassUi(SheetSecondPassDisplay.UNKNOWN, text, true, reason, pgms)
    if (pgms.isEmpty()) return unknown("N/A", "This sheet has no program that can take a 2nd pass.")
    if (applying) return SheetSecondPassUi(SheetSecondPassDisplay.APPLYING, "SAVING", true,
        "A 2nd pass change is being sent to $machineName.", pgms)
    if (snapshot == null) {
        return if (loading) SheetSecondPassUi(SheetSecondPassDisplay.LOADING, "…", true, "Checking $machineName…", pgms)
        else unknown("?", "Can't reach $machineName. Try again in a moment.")
    }
    val history = snapshot.history
    if (history == null || !history.canEdit) return unknown("?", "2nd pass state is unknown on $machineName. Open Mixes to review it.")
    val inventory = snapshot.inventory ?: return unknown("?", "Can't read the programs on $machineName. Try again in a moment.")
    val onCnc = inventory.map(::pgmIdentity).toSet()
    if (pgms.any { pgmIdentity(it) !in onCnc }) return unknown("NOT ON CNC", "This sheet's program isn't on $machineName.")

    val draft = SecondPassDrafts.from(history, pgms, punloadFixed)
    val second = SecondPassDrafts.tri(draft, pgms, SecondPassField.SECOND)
    val superPass = SecondPassDrafts.tri(draft, pgms, SecondPassField.SUPER)
    val (display, text) = when (second) {
        TriState.ON -> if (superPass == TriState.ON) SheetSecondPassDisplay.SUPER to "SUPER" else SheetSecondPassDisplay.ON to "ON"
        TriState.OFF -> SheetSecondPassDisplay.OFF to "OFF"
        is TriState.PARTIAL -> SheetSecondPassDisplay.PARTIAL to "${second.count} OF ${pgms.size}"
    }
    val turnOn = second != TriState.ON
    val reason = when {
        readOnly -> "View-only mode. 2nd pass can't be changed here."
        snapshot.access?.secondPass != true -> snapshot.access?.reason ?: "2nd pass editing isn't available on $machineName."
        otherBlock != null -> otherBlock
        locked -> "This sheet is completed, so its program is locked."
        !turnOn && !snapshot.revertSupported -> "Turning 2nd pass off needs a CNC service update."
        else -> null
    }
    return SheetSecondPassUi(display, text, turnOn, reason, pgms)
}

/** The same plan Mixes builds when its 2ND toggle is set for these programs and applied. */
internal fun sheetSecondPassPlan(history: PgmEditHistoryView, sheetPgms: List<String>, on: Boolean, punloadFixed: Boolean): List<SecondPassStep> {
    val pgms = sheetSecondPassPgms(sheetPgms)
    if (pgms.isEmpty()) return emptyList()
    val draft = SecondPassDrafts.from(history, pgms, punloadFixed)
    return planSecondPass(SecondPassDrafts.setAll(draft, pgms, SecondPassField.SECOND, on))
}

private suspend fun loadSheetSecondPass(machine: CncMachine, service: MixServiceClient, client: MixFilesClient,
    job: String, material: String): SheetSecondPassSnapshot? = try {
    val status = client.serviceStatus()
    val inventory = service.listPgms(job, material).map { it.name }.takeIf { it.isNotEmpty() }
    SheetSecondPassSnapshot(machineAccess(machine, status), "pgm-edits-revert" in status?.features.orEmpty(),
        inventory, service.listPgmEdits(job, material))
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

/**
 * 2nd Pass status + toggle for the viewer's sheet header, styled like the sheet name/size indicators.
 * Changes go through the app-scoped CNC operation watcher, like Mixes, so leaving the viewer never abandons one.
 */
@Composable
internal fun SheetSecondPassButton(
    machine: CncMachine,
    jobFolderName: String,
    materialName: String,
    sheetPgms: List<String>,
    locked: Boolean,
    readOnly: Boolean,
    catalogLive: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as KKCApplication
    val scope = rememberCoroutineScope()
    val service = remember(machine) { MixServiceClient(machine) }
    val client = remember(machine) { MixFilesClient(machine) }
    val operations = app.cncOperations
    val mixOperations by operations.mixOperations.records.collectAsState()
    val transfers by app.materialTransfers.records.collectAsState()
    val pending = mixOperations.any { !it.terminal && it.machineId == machine.id && it.job == jobFolderName && it.material == materialName }
    val moving = transfers.any { it.blocksMaterial && it.job == jobFolderName && it.material == materialName }

    var snapshot by remember(machine.id, jobFolderName, materialName) { mutableStateOf<SheetSecondPassSnapshot?>(null) }
    var loading by remember(machine.id, jobFolderName, materialName) { mutableStateOf(true) }
    var applying by remember(machine.id, jobFolderName, materialName) { mutableStateOf(false) }
    var refresh by remember(machine.id, jobFolderName, materialName) { mutableIntStateOf(0) }
    var confirmTurnOn by remember(machine.id, jobFolderName, materialName) { mutableStateOf<Boolean?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Per material, not per page: swiping sheets re-reads nothing. A finished operation (pending -> false) re-reads.
    LaunchedEffect(machine.id, jobFolderName, materialName, refresh, pending) {
        if (applying) return@LaunchedEffect
        loading = true
        snapshot = loadSheetSecondPass(machine, service, client, jobFolderName, materialName) ?: snapshot
        loading = false
    }

    val otherBlock = when {
        pending -> "A CNC change for this material is still being confirmed. Try again when it finishes."
        moving -> "This material is being moved between machines."
        !catalogLive -> "Can't reach ${machine.displayName}. Try again in a moment."
        else -> null
    }
    val ui = sheetSecondPassUi(sheetPgms, snapshot, machine.displayName, loading, applying, locked, readOnly,
        otherBlock, machine.forcesPUnloadRemoval)

    suspend fun apply(on: Boolean): String {
        val programs = ui.pgms
        val names = programs.joinToString { it.removeSuffix(".pgm").removeSuffix(".PGM") }
        if (operations.mixOperations.records.value.any { !it.terminal && it.machineId == machine.id && it.job == jobFolderName && it.material == materialName })
            return "A CNC change for this material is still being confirmed. Try again when it finishes."
        // Fresh reads at the action boundary: what was on screen may be minutes old.
        val status = client.serviceStatus()
        val access = machineAccess(machine, status, requireStatus = true)
        if (!access.secondPass) return access.reason ?: "2nd pass editing isn't available on ${machine.displayName}."
        val features = status?.features.orEmpty()
        if (!on && "pgm-edits-revert" !in features) return "Turning 2nd pass off needs a CNC service update."
        val history = service.listPgmEdits(jobFolderName, materialName)
        if (history?.canEdit != true) return "2nd pass state is unknown on ${machine.displayName}. Open Mixes to review it."
        val steps = sheetSecondPassPlan(history, programs, on, machine.forcesPUnloadRemoval)
        if (steps.isEmpty()) return if (on) "2nd pass is already on for $names." else "2nd pass is already off for $names."
        val readHashes: suspend () -> Map<String, String> = {
            service.listPgms(jobFolderName, materialName, withSha256 = true).filter { validPgmHash(it.sha256) }.associate { it.name to it.sha256!! }
        }
        val revisions = if ("pgm-edit-revisions" in features) SecondPassRevisionSupport(
            SecondPassRevisionSnapshot(readHashes()), refresh = readHashes, revert = client::submitPgmEditRevertRows) else null
        val requestIds = secondPassRequestIds(tabletDeviceId(context))
        val outcome = operations.runSecondPass(machine, jobFolderName, materialName) { tracker ->
            SecondPassRunner(service, client::submitPgmEditRevert, requestIds, revisions = revisions, tracker = tracker)
                .apply(jobFolderName, materialName, steps) { }
        }
        return when (outcome) {
            SecondPassOutcome.Done -> if (on) "2nd pass turned on for $names." else "2nd pass turned off for $names."
            is SecondPassOutcome.Failed -> outcome.message
        }
    }

    SheetSecondPassPill(ui, modifier) {
        when {
            ui.display == SheetSecondPassDisplay.LOADING || ui.display == SheetSecondPassDisplay.APPLYING -> Unit
            !ui.canToggle -> ui.blockedReason?.let(onMessage)
            else -> confirmTurnOn = ui.turnOn
        }
    }

    confirmTurnOn?.let { on ->
        val names = ui.pgms.joinToString { it.removeSuffix(".pgm").removeSuffix(".PGM") }
        AlertDialog(
            onDismissRequest = { confirmTurnOn = null },
            title = { Text(if (on) "Turn on 2nd pass?" else "Turn off 2nd pass?") },
            text = {
                Text(
                    if (on) "Adds a 2nd pass to $names on ${machine.displayName}. The CNC program changes right away."
                    else "Removes the ${if (ui.display == SheetSecondPassDisplay.SUPER) "super pass" else "2nd pass"} from $names on " +
                        "${machine.displayName} by restoring its original program. The CNC program changes right away."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTurnOn = null
                    if (applying) return@TextButton
                    applying = true
                    scope.launch {
                        val message = try { apply(on) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { failure.message ?: "Couldn't confirm the 2nd pass change. Check Mixes before trying again." }
                        finally { applying = false }
                        onMessage(message)
                        refresh++
                    }
                }) { Text(if (on) "Turn on" else "Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmTurnOn = null }) { Text("Cancel") } },
        )
    }
}

/** Two segments like the sheet name/size indicator: a "2nd Pass" label and a colored status. */
@Composable
internal fun SheetSecondPassPill(ui: SheetSecondPassUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = when (ui.display) {
        SheetSecondPassDisplay.ON, SheetSecondPassDisplay.SUPER -> scheme.primary to scheme.onPrimary
        SheetSecondPassDisplay.PARTIAL -> scheme.primaryContainer to scheme.onPrimaryContainer
        SheetSecondPassDisplay.OFF -> scheme.surfaceVariant to scheme.onSurfaceVariant
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val description = when (ui.display) {
        SheetSecondPassDisplay.LOADING -> "2nd pass: checking"
        SheetSecondPassDisplay.APPLYING -> "2nd pass: saving change"
        else -> "2nd pass: ${ui.statusText.lowercase()}" +
            if (ui.canToggle) ". Tap to turn ${if (ui.turnOn) "on" else "off"}" else ""
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 2.dp,
        modifier = modifier
            .height(36.dp)
            .testTag("sheet-second-pass")
            .semantics { contentDescription = description; role = Role.Button }
    ) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxHeight().background(scheme.secondaryContainer).padding(horizontal = 12.dp)
            ) {
                Text("2nd", style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer, maxLines = 1)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.onSecondaryContainer.copy(alpha = 0.24f)))
            Row(
                modifier = Modifier.fillMaxHeight().background(fill).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                StatusGlyph(ui.display, ink)
                Text(ui.statusText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusGlyph(display: SheetSecondPassDisplay, ink: Color) {
    when (display) {
        SheetSecondPassDisplay.LOADING, SheetSecondPassDisplay.APPLYING ->
            CircularProgressIndicator(Modifier.size(14.dp), color = ink, strokeWidth = 2.dp)
        SheetSecondPassDisplay.ON, SheetSecondPassDisplay.SUPER -> Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = ink)
        SheetSecondPassDisplay.PARTIAL -> Icon(Icons.Default.Remove, null, Modifier.size(16.dp), tint = ink)
        SheetSecondPassDisplay.OFF -> Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = ink)
        SheetSecondPassDisplay.UNKNOWN -> Unit
    }
}
