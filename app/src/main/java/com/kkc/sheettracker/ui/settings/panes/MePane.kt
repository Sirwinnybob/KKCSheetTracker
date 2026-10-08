package com.kkc.sheettracker.ui.settings.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.ui.settings.CardBody
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.settings.SaveRow
import com.kkc.sheettracker.ui.settings.rememberSavedFlash
import com.kkc.sheettracker.ui.settings.saveButtonState
import com.kkc.sheettracker.ui.settings.settingsFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MePane(employeeName: String, onEmployeeNameChanged: (String) -> Unit) {
    val records by EmployeeDirectory.recordsFlow.collectAsState()
    var text by rememberSaveable(employeeName) { mutableStateOf(employeeName) }
    var expanded by remember { mutableStateOf(false) }
    var savedFlash by rememberSavedFlash()
    val matches = remember(text, records) {
        if (text.isBlank()) emptyList()
        else records.filter {
            it.name.contains(text, ignoreCase = true) ||
                it.pin.contains(text, ignoreCase = true) ||
                it.displayName.contains(text, ignoreCase = true)
        }
    }
    val button = saveButtonState(text, employeeName, allowBlank = true)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard(caption = "Hours Tracker login") {
            CardBody {
                ExposedDropdownMenuBox(
                    expanded = expanded && matches.isNotEmpty(),
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            expanded = it.isNotBlank()
                        },
                        label = { Text("Your Name / PIN") },
                        supportingText = { Text("Used for auto-login to the Hours Tracker. Leave blank to be prompted each time.") },
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (matches.isNotEmpty()) {
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            matches.forEach { record ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (record.displayName.isNotBlank()) "${record.displayName} (${record.pin})"
                                            else record.name
                                        )
                                    },
                                    onClick = {
                                        text = record.name
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                SaveRow(
                    visible = button.visible,
                    enabled = button.enabled,
                    saveLabel = "Save name",
                    savedFlash = savedFlash,
                    onClick = {
                        onEmployeeNameChanged(text.trim())
                        savedFlash = true
                    }
                )
            }
        }
    }
}
