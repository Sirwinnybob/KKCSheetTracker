package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodDoorAssembly

fun hardwoodsBatchLabel(doc: HardwoodDocumentIndex): String = doc.batchName.ifBlank { "Default" }

fun hardwoodsDoorSummaryLine(door: HardwoodDoorAssembly): String {
    val parts = buildList {
        add(door.label.ifBlank { "Door" })
        if (door.cabinet.isNotBlank()) add("Cab ${door.cabinet}")
        if (door.room.isNotBlank()) add(door.room)
        if (door.width.isNotBlank() && door.height.isNotBlank()) add("${door.width} × ${door.height}")
        if (door.style.isNotBlank()) add(door.style)
        if (door.hinge.isNotBlank()) add("hinge ${door.hinge}")
        if (door.grain.isNotBlank()) add("grain ${door.grain}")
        if (door.notes.isNotBlank()) add(door.notes)
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsBatchChips(
    docs: List<HardwoodDocumentIndex>,
    selected: HardwoodDocumentIndex?,
    onSelect: (HardwoodDocumentIndex) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        docs.forEach { doc ->
            FilterChip(
                selected = doc.batchId == selected?.batchId,
                onClick = { onSelect(doc) },
                label = { Text(hardwoodsBatchLabel(doc)) }
            )
        }
    }
}

@Composable
fun HardwoodsDoorAssembliesPanel(
    doors: List<HardwoodDoorAssembly>,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide doors (${doors.size})" else "Show doors (${doors.size})")
        }
        if (expanded) {
            doors.forEach { door ->
                Text(
                    hardwoodsDoorSummaryLine(door),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
