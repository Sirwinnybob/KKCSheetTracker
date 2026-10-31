package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixfiles.*

internal const val REVERT_UNAVAILABLE = "Revert needs a service update"

@Composable
internal fun SecondPassControls(draft: SecondPassDraft?, pgms: List<String>, enabled: Boolean, revertSupported: Boolean,
    onSet: (SecondPassField, Boolean) -> Unit, compact: Boolean = true) {
    Row {
        SecondPassField.entries.forEach { field ->
            val tri = draft?.let { SecondPassDrafts.tri(it, pgms, field) } ?: TriState.OFF
            val checked = when (tri) { TriState.ON -> ToggleableState.On; TriState.OFF -> ToggleableState.Off; is TriState.PARTIAL -> ToggleableState.Indeterminate }
            val applied = draft?.let { SecondPassDrafts.tri(it.copy(wanted = it.current), pgms, field) } ?: TriState.OFF
            // Without revert capability, already-applied controls remain visibly checked and disabled.
            val canChange = enabled && draft != null && pgms.isNotEmpty() && (revertSupported || applied == TriState.OFF) &&
                (field != SecondPassField.SUPER || SecondPassDrafts.tri(draft, pgms, SecondPassField.SECOND) == TriState.ON)
            val label = if (compact) when (field) { SecondPassField.PUNLOAD -> "PUN"; SecondPassField.SECOND -> "2ND"; SecondPassField.SUPER -> "SUP" }
                else when (field) { SecondPassField.PUNLOAD -> "Remove P-unload"; SecondPassField.SECOND -> "2nd pass"; SecondPassField.SUPER -> "Super pass" }
            Column {
                TriStateCheckbox(checked, onClick = { onSet(field, tri != TriState.ON) }, enabled = canChange,
                    modifier = Modifier.semantics { contentDescription = "$label · ${pgms.joinToString()}" })
                Text(label, style = MaterialTheme.typography.labelSmall)
                if (!compact && tri is TriState.PARTIAL) Text("${tri.count} of ${pgms.size}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun SecondPassMenu(draft: SecondPassDraft?, pgms: List<String>, enabled: Boolean, revertSupported: Boolean,
    onSet: (SecondPassField, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = draft != null) { Text("Second pass") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Apply to all ${pgms.size} files", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
            Box(Modifier.padding(12.dp)) { SecondPassControls(draft, pgms, enabled, revertSupported, onSet, compact = false) }
            if (!revertSupported) Text(REVERT_UNAVAILABLE, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
