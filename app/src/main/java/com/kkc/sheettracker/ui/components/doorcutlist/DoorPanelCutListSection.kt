package com.kkc.sheettracker.ui.components.doorcutlist

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.ui.components.printPdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DoorPanelCutList"

private sealed interface CutListLoad {
    object Loading : CutListLoad
    object Unavailable : CutListLoad
    data class Ready(val source: DoorPanelCutListSource) : CutListLoad
}

/**
 * "Door Panel Cut List" section at the top of the print sheet. Hidden entirely when the job has
 * no sheet door-panel rows; the file list below never waits on it.
 */
@Composable
fun DoorPanelCutListSection(
    jobFolderName: String,
    jobRepository: JobRepository,
    onPrinted: () -> Unit,
) {
    val load by produceState<CutListLoad>(CutListLoad.Loading, jobFolderName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val basePath = jobRepository.getJobDirectory(jobFolderName).parent.orEmpty()
                val cabinetIndex = runCatching { jobRepository.getCabinetSheetIndex(jobFolderName) }.getOrNull()
                loadDoorPanelCutListSource(basePath, jobFolderName, cabinetIndex)
            }.onFailure { Log.d(TAG, "Door panel cut list unavailable for $jobFolderName", it) }
                .getOrNull()
                ?.let { CutListLoad.Ready(it) }
                ?: CutListLoad.Unavailable
        }
    }
    when (val state = load) {
        CutListLoad.Unavailable -> Unit
        CutListLoad.Loading -> SectionFrame {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Door Panel Cut List",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        is CutListLoad.Ready -> ReadySection(state.source, onPrinted)
    }
}

@Composable
private fun SectionFrame(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "GENERATED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            content()
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun ReadySection(source: DoorPanelCutListSource, onPrinted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(source) { mutableStateOf(false) }
    var selection by remember(source) { mutableStateOf(defaultSelection(source)) }
    var building by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val dateText = remember { SimpleDateFormat("d MMMM, yyyy", Locale.US).format(Date()) }
    val model = remember(source, selection, dateText) { buildCutListModel(source, selection, dateText) }
    val materialOpts = remember(source, selection) { materialOptions(source, selection) }
    val roomOpts = remember(source, selection) { roomOptions(source, selection) }

    SectionFrame {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Door Panel Cut List",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = summaryText(model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CheckRow(label = "Room Tags", trailing = null, checked = selection.roomTags) {
                            selection = selection.copy(roomTags = it)
                        }
                        GroupLabel("Materials")
                        materialOpts.forEach { option ->
                            CheckRow(option.material, "${option.pieces} pcs", option.checked) { checked ->
                                selection = selection.copy(
                                    materials = if (checked) selection.materials + option.material
                                    else selection.materials - option.material
                                )
                            }
                        }
                        if (showRoomFilter(source)) {
                            GroupLabel("Rooms")
                            roomOpts.forEach { option ->
                                CheckRow(option.displayName, "${option.pieces} pcs", option.checked) { checked ->
                                    selection = selection.copy(
                                        rooms = if (checked) selection.rooms + option.key else selection.rooms - option.key
                                    )
                                }
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        errorText?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                if (building) return@Button
                                building = true
                                errorText = null
                                val printModel = model
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            prepareCutListPrintFile(context, source.jobFolderName).also {
                                                writeDoorPanelCutListPdf(printModel, it)
                                            }
                                        }
                                    }
                                    building = false
                                    result.onSuccess { file ->
                                        printPdfFile(
                                            context,
                                            file,
                                            "KKC Sheet Tracker - ${cutListJobNumber(source.jobFolderName)} Door Cut List"
                                        )
                                        onPrinted()
                                    }.onFailure { e ->
                                        Log.e(TAG, "Door cut list build failed for ${source.jobFolderName}", e)
                                        errorText = "Couldn't build cut list. Try again."
                                    }
                                }
                            },
                            enabled = model.canPrint && !building,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            if (building) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Print")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun summaryText(model: DoorPanelCutListModel): String {
    val count = model.materials.size
    return "$count material${if (count == 1) "" else "s"} · ${model.totalPieces} pcs"
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun CheckRow(label: String, trailing: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 8.dp, end = 4.dp)) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
