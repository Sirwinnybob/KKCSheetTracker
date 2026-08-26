package com.kkc.sheettracker.ui.managecode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import com.kkc.sheettracker.data.mixservice.isValidMixName

data class MixActionDialogContent(
    val activeMixes: List<MixCatalogEntry>,
    val historyMixes: List<MixCatalogEntry>,
    val externalMixes: List<MixCatalogEntry>,
    val automaticTarget: MixGenerationTarget?,
    val generationBlockedByExternal: Boolean
)

fun mixActionDialogContent(catalog: MixCatalogSnapshot): MixActionDialogContent {
    val active = catalog.entries.filter { it.lifecycle == MixLifecycle.ACTIVE }
    val history = catalog.entries.filter { it.lifecycle == MixLifecycle.HISTORY }
    val external = catalog.entries.filter { it.lifecycle == MixLifecycle.EXTERNAL }
    return MixActionDialogContent(
        activeMixes = active,
        historyMixes = history,
        externalMixes = external,
        automaticTarget = if (active.isEmpty() && external.isEmpty()) MixGenerationTarget.FirstDefault else null,
        generationBlockedByExternal = external.isNotEmpty()
    )
}

fun isAdditionalMixNameReady(originalName: String, draft: String, catalog: MixCatalogSnapshot): Boolean =
    draft != originalName && isValidMixName(draft) && catalog.entries
        .none { it.name.equals(draft, ignoreCase = true) }

/** Returns only an exact external filename, never a display name or case-insensitive approximation. */
fun externalDeletionFilename(catalog: MixCatalogSnapshot, requestedFilename: String): String? =
    catalog.entries.firstOrNull {
        it.lifecycle == MixLifecycle.EXTERNAL && it.mixFilename == requestedFilename
    }?.mixFilename

@Composable
fun MixActionDialog(
    catalog: MixCatalogSnapshot,
    originalName: String,
    onDismiss: () -> Unit,
    onTargetSelected: (MixGenerationTarget) -> Unit,
    onDeleteExternal: (String) -> Unit
) {
    val content = remember(catalog) { mixActionDialogContent(catalog) }
    var additionalName by remember(catalog, originalName) { mutableStateOf(originalName) }
    var pendingDeletionFilename by remember(catalog) { mutableStateOf<String?>(null) }
    var pendingReplacement by remember(catalog) { mutableStateOf<MixCatalogEntry?>(null) }
    val deletion = pendingDeletionFilename
    if (deletion != null) {
        AlertDialog(
            onDismissRequest = { pendingDeletionFilename = null },
            title = { Text("Permanently delete external mix?") },
            text = { Text("Delete $deletion permanently from the CNC? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    externalDeletionFilename(catalog, deletion)?.let(onDeleteExternal)
                    pendingDeletionFilename = null
                }) { Text("Delete $deletion") }
            },
            dismissButton = { TextButton(onClick = { pendingDeletionFilename = null }) { Text("Cancel") } }
        )
        return
    }
    val replacement = pendingReplacement
    if (replacement != null) {
        val archivedAt = replacement.updatedAt ?: replacement.createdAt ?: "an unknown time"
        AlertDialog(
            onDismissRequest = { pendingReplacement = null },
            title = { Text("Replace ${replacement.name}?") },
            text = {
                Text(
                    "The current ${replacement.name} version from $archivedAt will be archived before the replacement is compiled."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onTargetSelected(MixGenerationTarget.ReplaceActive(replacement.name, catalog.revision))
                    pendingReplacement = null
                }) { Text("Replace and archive") }
            },
            dismissButton = { TextButton(onClick = { pendingReplacement = null }) { Text("Cancel") } }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (content.generationBlockedByExternal) "External mix file detected" else "Choose mix action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (content.generationBlockedByExternal) {
                    Text("Create and replace are blocked until every external mix file is removed.")
                    content.activeMixes.forEach { entry ->
                        Text("${entry.name}: ${entry.programs.joinToString().ifBlank { "No PGMs" }}")
                        Text("Compile: ${compileStatus(entry)}", style = MaterialTheme.typography.bodySmall)
                    }
                    content.externalMixes.forEach { entry ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.mixFilename, fontWeight = FontWeight.Medium)
                            TextButton(onClick = { pendingDeletionFilename = entry.mixFilename }) { Text("Delete permanently") }
                        }
                    }
                    if (content.historyMixes.isNotEmpty()) {
                        Text("History (display only)", fontWeight = FontWeight.Medium)
                        content.historyMixes.forEach { entry -> Text(entry.name) }
                    }
                } else {
                    content.activeMixes.forEach { entry ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(entry.name, fontWeight = FontWeight.Medium)
                            Text("PGMs: ${entry.programs.joinToString().ifBlank { "None" }}")
                            Text("Compile: ${compileStatus(entry)}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                pendingReplacement = entry
                            }) { Text("Replace and archive current version") }
                        }
                    }
                    OutlinedTextField(
                        value = additionalName,
                        onValueChange = { additionalName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Create another mix") },
                        supportingText = { Text("Choose a new unique name") },
                        singleLine = true
                    )
                    Button(
                        onClick = { onTargetSelected(MixGenerationTarget.CreateAdditional(additionalName)) },
                        enabled = isAdditionalMixNameReady(originalName, additionalName, catalog)
                    ) { Text("Create another") }
                    if (content.historyMixes.isNotEmpty()) {
                        Text("History (display only)", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                        content.historyMixes.forEach { entry ->
                            Text("${entry.name} — ${entry.updatedAt ?: entry.createdAt ?: "Archived"}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun compileStatus(entry: MixCatalogEntry): String = when (entry.lastCompileOk) {
    true -> "Compiled ${entry.lastCompiledAt.orEmpty()}".trim()
    false -> "Failed: ${entry.lastCompileError.orEmpty()}".trimEnd(':', ' ')
    null -> entry.status ?: "Not compiled"
}
