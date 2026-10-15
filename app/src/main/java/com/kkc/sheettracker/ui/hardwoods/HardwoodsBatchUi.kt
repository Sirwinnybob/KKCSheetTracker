package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex

// Doors are shown in the workspace's Door Assembly tab (ui/doorpreview/DoorAssemblyList.kt).

fun hardwoodsBatchLabel(doc: HardwoodDocumentIndex): String = doc.batchName.ifBlank { "Default" }

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
