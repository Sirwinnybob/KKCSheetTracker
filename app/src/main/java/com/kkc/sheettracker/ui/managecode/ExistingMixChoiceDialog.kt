package com.kkc.sheettracker.ui.managecode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import com.kkc.sheettracker.data.mixservice.isCatalogMixNameAvailable
import com.kkc.sheettracker.data.mixservice.nextAdditionalMixName

private enum class ChoiceStage { MAIN, PICK_REPLACE, CONFIRM_REPLACE, NAME_ADDITIONAL }

/** Shown on the first MIX check for a material that already has an active mix. */
@Composable
fun ExistingMixChoiceDialog(
    catalog: MixCatalogSnapshot,
    materialName: String,
    onReplace: (MixGenerationTarget.ReplaceActive) -> Unit,
    onAdditional: (MixGenerationTarget.CreateAdditional) -> Unit,
    onCancel: () -> Unit,
) {
    val active = remember(catalog) { catalog.entries.filter { it.lifecycle == MixLifecycle.ACTIVE } }
    var stage by remember(catalog) { mutableStateOf(ChoiceStage.MAIN) }
    var replacing by remember(catalog) { mutableStateOf<MixCatalogEntry?>(active.singleOrNull()) }
    var name by remember(catalog) { mutableStateOf(nextAdditionalMixName(materialName, catalog)) }

    when (stage) {
        ChoiceStage.MAIN -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("$materialName already has a mix") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    active.forEach { Text("${it.name} — ${it.programs.size} PGMs") }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        stage = if (active.size > 1) ChoiceStage.PICK_REPLACE else ChoiceStage.CONFIRM_REPLACE
                    }) { Text("Replace Mix") }
                    TextButton(onClick = { stage = ChoiceStage.NAME_ADDITIONAL }) { Text("Additional Mix") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            },
        )
        ChoiceStage.PICK_REPLACE -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Replace which mix?") },
            text = {
                Column {
                    active.forEach { entry ->
                        TextButton(onClick = { replacing = entry; stage = ChoiceStage.CONFIRM_REPLACE }) {
                            Text("${entry.name} — ${entry.programs.size} PGMs")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        ChoiceStage.CONFIRM_REPLACE -> {
            val entry = replacing ?: return
            val archivedAt = entry.updatedAt ?: entry.createdAt ?: "an unknown time"
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("Replace ${entry.name}?") },
                text = { Text("The current ${entry.name} version from $archivedAt will be archived before the replacement is compiled.") },
                confirmButton = {
                    TextButton(onClick = { onReplace(replacementTarget(entry, catalog)) }) { Text("Replace and archive") }
                },
                dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        }
        ChoiceStage.NAME_ADDITIONAL -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Additional mix") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mix name") },
                    supportingText = { Text("Must be unique") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAdditional(MixGenerationTarget.CreateAdditional(name)) },
                    enabled = isCatalogMixNameAvailable(name, catalog),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }
}
