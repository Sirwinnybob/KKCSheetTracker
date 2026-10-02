package com.kkc.sheettracker.ui.components.doorcutlist

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.KKCPillOption
import com.kkc.sheettracker.ui.components.KKCSlidingPillRow
import com.kkc.sheettracker.ui.components.kkcCardDepth
import com.kkc.sheettracker.ui.components.printPdfFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DoorPanelCutList"
private const val PREFS_NAME = "kkc_ui_prefs"
private const val PREF_ROOM_TAGS = "door_cut_list_room_tags"

/** Page 2 of the print modal: settings on the left, live PDF preview on the right. */
@Composable
internal fun DoorPanelCutListScreen(source: DoorPanelCutListSource, onPrinted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    // Only the Standard / Room Tags choice is remembered per tablet; materials and rooms keep per-job defaults.
    var selection by remember(source) {
        mutableStateOf(defaultSelection(source).copy(roomTags = prefs.getBoolean(PREF_ROOM_TAGS, false)))
    }
    fun setRoomTags(value: Boolean) {
        selection = selection.copy(roomTags = value)
        prefs.edit().putBoolean(PREF_ROOM_TAGS, value).apply()
    }
    var building by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val dateText = remember { SimpleDateFormat("d MMMM, yyyy", Locale.US).format(Date()) }
    val model = remember(source, selection, dateText) { buildCutListModel(source, selection, dateText) }
    val materialOpts = remember(source, selection) { materialOptions(source, selection) }
    val roomOpts = remember(source, selection) { roomOptions(source, selection) }
    val roomColors = remember(source) { assignRoomColors(source.roomKeys, source.jobFolderName) }

    val settings: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Layout")
            KKCSlidingPillRow(
                options = listOf(
                    KKCPillOption("Standard", !selection.roomTags, { setRoomTags(false) }),
                    KKCPillOption("Room Tags", selection.roomTags, { setRoomTags(true) }),
                ),
                fillWidth = true
            )
            SectionHeader("Materials")
            OptionCard {
                materialOpts.forEach { option ->
                    OptionRow(
                        label = option.material,
                        trailing = "${option.pieces} pcs",
                        checked = option.checked,
                        onChange = { checked ->
                            selection = selection.copy(
                                materials = if (checked) selection.materials + option.material
                                else selection.materials - option.material
                            )
                        }
                    )
                }
            }
            if (showRoomFilter(source)) {
                SectionHeader("Rooms")
                OptionCard {
                    roomOpts.forEach { option ->
                        OptionRow(
                            label = option.displayName,
                            trailing = "${option.pieces} pcs",
                            checked = option.checked,
                            swatch = if (selection.roomTags) {
                                Color(roomColors[option.key] ?: UNASSIGNED_ROOM_COLOR)
                            } else null,
                            onChange = { checked ->
                                selection = selection.copy(
                                    rooms = if (checked) selection.rooms + option.key
                                    else selection.rooms - option.key
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    val preview: @Composable (Modifier) -> Unit = { previewModifier ->
        CutListPreview(model, previewModifier)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (maxWidth >= 720.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 12.dp)
                    ) { settings() }
                    Spacer(modifier = Modifier.width(16.dp))
                    preview(
                        Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                ) {
                    settings()
                    Spacer(modifier = Modifier.height(16.dp))
                    preview(
                        Modifier
                            .fillMaxWidth()
                            .height(520.dp)
                    )
                }
            }
        }

        HorizontalDivider()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            errorText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = {
                    if (building) return@Button
                    building = true
                    errorText = null
                    val printModel = model
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                prepareCutListPrintFile(context, source.jobFolderName).also {
                                    writeDoorPanelCutListPdf(printModel, it)
                                }
                            }
                            building = false
                            printPdfFile(
                                context,
                                file,
                                "KKC Sheet Tracker - ${cutListJobNumber(source.jobFolderName)} Door Cut List"
                            )
                            onPrinted()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            building = false
                            Log.e(TAG, "Door cut list build failed for ${source.jobFolderName}", e)
                            errorText = "Couldn't build cut list. Try again."
                        }
                    }
                },
                enabled = model.canPrint && !building,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                if (building) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Building cut list" },
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                } else {
                    Text("Print")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun OptionCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .kkcCardDepth(MaterialTheme.shapes.large)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun OptionRow(
    label: String,
    trailing: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    swatch: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(modifier = Modifier.width(12.dp))
        if (swatch != null) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(swatch, RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
