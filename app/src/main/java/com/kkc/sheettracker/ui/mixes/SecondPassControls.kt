package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.ui.components.KKCPillStyle
import com.kkc.sheettracker.ui.components.kkcPillIndicator
import com.kkc.sheettracker.ui.components.rememberKKCQuietPillStyle
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryEvent
import com.kkc.sheettracker.data.mixservice.PgmEditCurrentState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

internal const val REVERT_UNAVAILABLE = "Revert needs a service update"

private fun historyState(state: PgmEditCurrentState?): String = state?.let {
    "${it.mode ?: "unknown"} · P-unload ${when (it.punloadRemoved) { true -> "removed"; false -> "present"; null -> "unknown" }}"
} ?: "unrecorded"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecondPassHistorySheet(pgm: String, events: List<PgmEditHistoryEvent>, canRevert: Boolean,
    onRevert: () -> Unit, onDismiss: () -> Unit, current: PgmEditCurrentState? = null,
    revertSupported: Boolean = true, pgms: List<String> = listOf(pgm), onSelect: (String) -> Unit = {},
    onPreview: (() -> Unit)? = null, previewEnabled: Boolean = false, onRefreshRevisions: (() -> Unit)? = null) {
    var confirm by remember(pgm) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("2nd-pass history · $pgm", style = MaterialTheme.typography.titleLarge)
            if (pgms.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pgms.forEach { name -> FilterChip(name == pgm, { onSelect(name) }, label = { Text(name) }) }
            }
            Text("Current: ${historyState(current)}")
            if (events.isEmpty()) Text("No recorded edits.")
            events.asReversed().forEach { event ->
                HorizontalDivider()
                Text("${event.at ?: "Time unavailable"} · ${event.actor ?: "Actor unavailable"}", style = MaterialTheme.typography.labelMedium)
                Text(event.action.replace('_', ' '))
                // Canonical ledger events use result; API batch outcomes use final. Neither implies an unrecorded before state.
                Text("${historyState(event.before)} → ${historyState(event.result ?: event.final)}")
                event.backup?.let { Text("Recovery copy: $it", style = MaterialTheme.typography.bodySmall) }
                event.restoredFrom?.let { Text("Restored from: $it", style = MaterialTheme.typography.bodySmall) }
                event.error?.let { Text(it) }
            }
            onPreview?.let { TextButton(onClick = it, enabled = previewEnabled) { Text("Preview staged edits") } }
            onRefreshRevisions?.let { TextButton(onClick = it) { Text("Refresh CNC revisions") } }
            if (revertSupported) TextButton(onClick = { confirm = true }, enabled = canRevert) { Text("Revert to original") }
            else Text(REVERT_UNAVAILABLE)
            TextButton(onClick = onDismiss) { Text("Close") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Revert to original?") },
        text = { Text("Stage a restore of the first recorded backup for $pgm. Apply 2nd pass sends it. The backup may already contain edits; the returned history shows the actual restored state.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRevert() }, enabled = canRevert) { Text("Stage revert") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
internal fun SecondPassControls(draft: SecondPassDraft?, pgms: List<String>, enabled: Boolean, revertSupported: Boolean,
    onSet: (SecondPassField, Boolean) -> Unit, compact: Boolean = true) {
    // Compact (per sheet row): one quiet pill track holding three toggles, the app's style for controls repeated down a list.
    val quiet = rememberKKCQuietPillStyle()
    val track: @Composable (@Composable () -> Unit) -> Unit = if (compact) { inner -> MixesPillTrack(style = quiet) { inner() } } else { inner -> inner() }
    track {
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 0.dp)) {
        SecondPassField.entries.forEach { field ->
            val tri = draft?.let { SecondPassDrafts.tri(it, pgms, field) } ?: TriState.OFF
            val checked = when (tri) { TriState.ON -> ToggleableState.On; TriState.OFF -> ToggleableState.Off; is TriState.PARTIAL -> ToggleableState.Indeterminate }
            val applied = draft?.let { SecondPassDrafts.tri(it.copy(wanted = it.current), pgms, field) } ?: TriState.OFF
            // Without revert capability, already-applied controls remain visibly checked and disabled.
            // A machine that always removes P-unload shows it checked and fixed.
            val fixed = field == SecondPassField.PUNLOAD && draft?.punloadFixed == true
            val canChange = !fixed && enabled && draft != null && pgms.isNotEmpty() && (revertSupported || applied == TriState.OFF) &&
                (field != SecondPassField.SUPER || SecondPassDrafts.tri(draft, pgms, SecondPassField.SECOND) == TriState.ON)
            val label = if (compact) when (field) { SecondPassField.PUNLOAD -> "PUN"; SecondPassField.SECOND -> "2ND"; SecondPassField.SUPER -> "SUP" }
                else when (field) { SecondPassField.PUNLOAD -> if (fixed) "P-unload always removed" else "Remove P-unload"; SecondPassField.SECOND -> "2nd pass"; SecondPassField.SUPER -> "Super pass" }
            val description = "$label · ${pgms.joinToString()}" + if (fixed) " · always removed" else ""
            if (compact) SecondPassChip(label, checked, canChange, description, quiet) { onSet(field, tri != TriState.ON) }
            else Column {
                TriStateCheckbox(checked, onClick = { onSet(field, tri != TriState.ON) }, enabled = canChange,
                    modifier = Modifier.semantics { contentDescription = description })
                Text(label, style = MaterialTheme.typography.labelSmall)
                if (tri is TriState.PARTIAL) Text("${tri.count} of ${pgms.size}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    }
}

/**
 * One toggle in the row's pill track: wears the pill while on. Keeps tri-state checkbox semantics so it reads
 * and tests like the checkbox it replaces.
 */
@Composable
private fun SecondPassChip(label: String, state: ToggleableState, enabled: Boolean, description: String, style: KKCPillStyle,
    onClick: () -> Unit) {
    val active = state != ToggleableState.Off
    val text = if (active) style.selectedText else style.unselectedText
    Row(Modifier.height(40.dp).alpha(if (enabled) 1f else 0.4f)
        .then(if (active) Modifier.kkcPillIndicator(style) else Modifier).clip(RoundedCornerShape(6.dp))
        .triStateToggleable(state, enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .semantics { contentDescription = description }
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            ToggleableState.On -> Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = text)
            ToggleableState.Indeterminate -> Icon(Icons.Default.Remove, null, Modifier.size(16.dp), tint = text)
            ToggleableState.Off -> {}
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = text)
    }
}

@Composable
internal fun SecondPassMenu(draft: SecondPassDraft?, pgms: List<String>, enabled: Boolean, revertSupported: Boolean,
    onSet: (SecondPassField, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        MixesPillButton("Second pass", { open = true }, enabled = draft != null, trailingIcon = Icons.Default.ArrowDropDown)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Apply to all ${pgms.size} files", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
            Box(Modifier.padding(12.dp)) { SecondPassControls(draft, pgms, enabled, revertSupported, onSet, compact = false) }
            if (!revertSupported) Text(REVERT_UNAVAILABLE, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
