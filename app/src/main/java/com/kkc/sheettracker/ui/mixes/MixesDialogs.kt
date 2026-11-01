package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixfiles.*

@Composable
internal fun FrzMismatchDialog(mismatches: List<FrzMismatch>, statusOf: (String) -> String,
    onResolve: (Map<String, String>) -> Unit, onDismiss: () -> Unit, enabled: Boolean = true,
    dismissEnabled: Boolean = enabled, blockedReason: String? = null) {
    val choices = remember(mismatches) { mutableStateMapOf<String, String>() }
    val pgms = mismatches.distinctBy { it.pgm.lowercase() }
    AlertDialog(onDismissRequest = { if (dismissEnabled) onDismiss() }, title = { Text("Tablet and FRZ disagree") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose the completion source for each PGM for this save. Tablet statuses stay as they are.")
            blockedReason?.let { Text(it) }
            pgms.forEach { mismatch -> Column {
                Text(mismatch.pgm, style = MaterialTheme.typography.titleSmall)
                Text("Tablet: ${statusOf(mismatch.pgm)} · FRZ: ${mismatch.frzRemaining} left")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tablet" to "Tablet", "frz" to "FRZ").forEach { (value, label) ->
                        FilterChip(selected = choices[mismatch.pgm] == value, enabled = enabled,
                            onClick = { choices[mismatch.pgm] = value }, label = { Text(label) })
                    }
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = { onResolve(choices.toMap()) }, enabled = enabled && choices.size == pgms.size) { Text("Apply choices") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = dismissEnabled) { Text("Keep editing") } })
}

@Composable
internal fun ConflictBanner(onLoadTheirs: () -> Unit, onReapply: () -> Unit, loadEnabled: Boolean = true, reapplyEnabled: Boolean = true) {
    Row {
        TextButton(onClick = onLoadTheirs, enabled = loadEnabled) { Text("Load theirs") }
        TextButton(onClick = onReapply, enabled = reapplyEnabled) { Text("Re-apply my changes") }
    }
}

/** Source spelling and unknown parameter values survive unless this editor changes them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryEditSheet(entry: DraftEntry, interrupted: Boolean, onSave: (DraftEntry) -> Unit,
    onDismiss: () -> Unit, enabled: Boolean = true, siblings: List<DraftEntry> = listOf(entry), onSelect: (DraftEntry) -> Unit = {},
    dismissEnabled: Boolean = enabled, blockedReason: String? = null) {
    var quantity by remember(entry) { mutableStateOf(entry.quantity.toString()) }
    var left by remember(entry) { mutableStateOf((entry.remaining ?: entry.quantity).toString()) }
    var leftEdited by remember(entry) { mutableStateOf(false) }
    val parameters = remember(entry) { mutableStateMapOf<String, String>().apply { putAll(entry.parameters.orEmpty()) } }
    val q = quantity.toIntOrNull()
    val remaining = left.toIntOrNull()
    val valid = entry.source != null && q != null && q >= 1 && (!interrupted || (remaining != null && remaining in 1..q))
    ModalBottomSheet(onDismissRequest = { if (dismissEnabled) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (interrupted) "Quantity and Left" else "Quantity and parameters", style = MaterialTheme.typography.titleLarge)
            blockedReason?.let { Text(it) }
            if (siblings.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { siblings.forEach { item ->
                FilterChip(selected = item.id == entry.id, onClick = { onSelect(item) }, enabled = enabled,
                    label = { Text(item.source?.pgm ?: item.newPgm.orEmpty()) })
            } } else Text(entry.source?.pgm ?: entry.newPgm.orEmpty())
            OutlinedTextField(quantity, { value -> quantity = value; if (!leftEdited) left = value }, label = { Text("Quantity (R)") }, enabled = enabled && entry.source != null)
            if (interrupted) OutlinedTextField(left, { left = it; leftEdited = true }, label = { Text("Left") }, enabled = enabled && entry.source != null)
            else entry.parameters.orEmpty().keys.filterNot { it.equals("R", true) }.forEach { name ->
                OutlinedTextField(parameters[name].orEmpty(), { parameters[name] = it }, label = { Text(name) }, enabled = enabled && entry.source != null)
            }
            if (entry.source == null) Text("Apply the new sheet before editing its quantity or parameters.")
            else if (!valid) Text(if (interrupted) "Quantity must be at least 1; Left must be between 1 and Quantity." else "Quantity must be at least 1.")
            Row {
                TextButton(onClick = onDismiss, enabled = dismissEnabled) { Text("Cancel") }
                Button(onClick = {
                    val values = LinkedHashMap(entry.parameters.orEmpty()).apply { parameters.forEach { (k, v) -> this[k] = v } }
                    if (!interrupted && q != entry.quantity) {
                        val key = values.keys.firstOrNull { it.equals("R", true) } ?: "R"
                        val original = entry.source?.parameters?.entries?.firstOrNull { it.key.equals("R", true) }
                        if (q == entry.source?.quantity) {
                            if (original == null) values.remove(key) else values[key] = original.value
                        } else values[key] = q.toString()
                    }
                    onSave(entry.copy(quantity = q!!, remaining = if (interrupted) remaining else null, parameters = values))
                }, enabled = enabled && valid) { Text("Save to draft") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

internal fun mixFileName(name: String, extension: String = "mix"): String = name.trim().let {
    if (it.endsWith(".$extension", true)) it else "$it.$extension"
}
internal fun validMixName(name: String): Boolean = name.trim().let { it.isNotEmpty() && it !in setOf(".", "..") && it.none { c -> c in "\\/:*?\"<>|" || c.isISOControl() } }

@Composable
internal fun SplitNameDialog(defaultName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
    taken: List<String> = emptyList(), enabled: Boolean = true, dismissEnabled: Boolean = enabled, blockedReason: String? = null) =
    NameDialog("Move to new mix", defaultName, "Move to draft", "mix", taken, enabled, onConfirm, onDismiss, dismissEnabled, blockedReason)

@Composable
internal fun RenameDialog(defaultName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
    extension: String = "mix", taken: List<String> = emptyList(), enabled: Boolean = true, dismissEnabled: Boolean = enabled, blockedReason: String? = null) =
    NameDialog("Rename mix", defaultName, "Rename", extension, taken, enabled, onConfirm, onDismiss, dismissEnabled, blockedReason)

@Composable
private fun NameDialog(title: String, defaultName: String, action: String, extension: String, taken: List<String>, enabled: Boolean,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit, dismissEnabled: Boolean, blockedReason: String?) {
    var name by remember(defaultName) { mutableStateOf(defaultName.substringBeforeLast('.', defaultName)) }
    val file = mixFileName(name, extension)
    val stem = file.substringBeforeLast('.')
    val collision = taken.any { it.substringBeforeLast('.').equals(stem, true) }
    AlertDialog(onDismissRequest = { if (dismissEnabled) onDismiss() }, title = { Text(title) }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("Mix name") }, singleLine = true, enabled = enabled)
        if (collision) Text("That mix name already exists.")
        blockedReason?.let { Text(it) }
    } }, confirmButton = { TextButton(onClick = { onConfirm(file) }, enabled = enabled && validMixName(name) && !collision) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = dismissEnabled) { Text("Cancel") } })
}

@Composable
internal fun DeleteConfirm(file: String, onConfirm: () -> Unit, onDismiss: () -> Unit, permanent: Boolean = false, enabled: Boolean = true) {
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, title = { Text(if (permanent) "Permanently delete revision?" else "Delete mix?") },
        text = { Text(if (permanent) "$file will be permanently deleted. This cannot be undone." else "$file and its companion will be retired to revisions. You can restore the mix later.") },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(if (permanent) "Delete permanently" else "Delete") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("Cancel") } })
}
