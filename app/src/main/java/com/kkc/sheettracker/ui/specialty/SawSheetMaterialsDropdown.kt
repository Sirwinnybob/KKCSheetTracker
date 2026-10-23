package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SawSheetMaterial
import com.kkc.sheettracker.data.parseWholeSheets
import com.kkc.sheettracker.data.sheetUsageHiddenKey
import com.kkc.sheettracker.data.sheetUsageKey
import com.kkc.sheettracker.ui.components.SectionProgressHeader
import com.kkc.sheettracker.ui.hardwoods.TallyStepButton
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.kkcZebraTint

@Composable
internal fun SawSheetMaterialsDropdown(
    materials: List<SawSheetMaterial>, totals: Map<String, Int>, stateKey: String, readOnly: Boolean,
    onAdjust: (String, Int) -> Unit, onSetCount: (String, Int) -> Unit, onHide: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    var showHidden by rememberSaveable(stateKey) { mutableStateOf(false) }
    var editingMaterial by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    val hiddenCount = materials.count { (totals[sheetUsageHiddenKey(it.material)] ?: 0) > 0 }
    Column(modifier.padding(horizontal = 2.dp, vertical = 6.dp)) {
        SectionProgressHeader(
            title = "Sheet materials", itemCount = materials.size - hiddenCount, done = 0, total = 0,
            // Bound the shared header's fill-height accent during the Kanban intrinsic probe.
            modifier = Modifier.height(40.dp),
            expanded = expanded, onToggleExpanded = { expanded = !expanded },
            isSubHeader = true, showProgress = false
        )
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                materials.filter { showHidden || (totals[sheetUsageHiddenKey(it.material)] ?: 0) == 0 }
                    .forEachIndexed { index, row ->
                        SawSheetMaterialRow(row, totals[sheetUsageKey(row.material)],
                            hidden = (totals[sheetUsageHiddenKey(row.material)] ?: 0) > 0,
                            zebraIndex = index, readOnly = readOnly, compact = compact,
                            onAdjust = { onAdjust(row.material, it) },
                            onEdit = { editingMaterial = row.material },
                            onHide = { onHide(row.material, it) })
                    }
                if (materials.size == hiddenCount && !showHidden) {
                    Text("All materials hidden", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
                if (hiddenCount > 0) {
                    TextButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "Hide hidden materials" else "Show hidden ($hiddenCount)")
                    }
                }
            }
        }
    }
    editingMaterial?.let { material ->
        var draft by rememberSaveable(stateKey, material) { mutableStateOf(totals[sheetUsageKey(material)]?.toString().orEmpty()) }
        val parsed = parseWholeSheets(draft)
        AlertDialog(onDismissRequest = { editingMaterial = null }, title = { Text("Saw sheets used") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(material)
                    OutlinedTextField(value = draft, onValueChange = { next ->
                        if (next.isEmpty() || parseWholeSheets(next) != null) draft = next
                    }, label = { Text("Sheets used (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }, confirmButton = {
                TextButton(enabled = parsed != null && !readOnly, onClick = {
                    parsed?.let { onSetCount(material, it) }; editingMaterial = null
                }) { Text("Save") }
            }, dismissButton = { TextButton(onClick = { editingMaterial = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SawSheetMaterialRow(
    row: SawSheetMaterial, count: Int?, hidden: Boolean, zebraIndex: Int, readOnly: Boolean, compact: Boolean,
    onAdjust: (Int) -> Unit, onEdit: () -> Unit, onHide: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = scheme.outlineVariant.copy(alpha = 0.4f)
    val background = kkcZebraTint(zebraIndex).compositeOver(scheme.surface)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp), color = background,
        shadowElevation = 2.dp, border = BorderStroke(1.dp, borderColor)
    ) {
        // Kanban probes header intrinsics before measurement; keep this subtree free of subcomposition.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            val name: @Composable (Modifier) -> Unit = { nameModifier ->
                Text(row.material, modifier = nameModifier, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (hidden) scheme.onSurfaceVariant else scheme.onSurface)
            }
            val cnc: @Composable (Modifier) -> Unit = { cncModifier ->
                Text("CNC: ${row.cncUsed?.toString() ?: "—"} sheets${if (hidden) " · Hidden" else ""}",
                    modifier = cncModifier, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (compact) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        name(Modifier.weight(1f))
                        if (!readOnly) SheetMaterialMenu(row.material, hidden, onHide)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cnc(Modifier.weight(1f))
                        SawSheetTally(row.material, count, readOnly, onAdjust, onEdit)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        name(Modifier)
                        cnc(Modifier)
                    }
                    SawSheetTally(row.material, count, readOnly, onAdjust, onEdit)
                    if (!readOnly) SheetMaterialMenu(row.material, hidden, onHide)
                }
            }
        }
    }
}

@Composable
private fun SawSheetTally(material: String, count: Int?, readOnly: Boolean, onAdjust: (Int) -> Unit, onEdit: () -> Unit) {
    val status = KKCThemeColors.statusColors
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Saw", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        TallyStepButton(Icons.Default.Remove, "Use one fewer $material sheet", status.bad,
            enabled = !readOnly && (count ?: 0) > 0,
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAdjust(-1) },
            onLongClick = { false })
        Box(Modifier.height(48.dp).widthIn(min = 64.dp).clickable(enabled = !readOnly, onClick = onEdit)
            .semantics { contentDescription = "Enter saw sheets used for $material" }, contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(9.dp),
                color = scheme.outlineVariant.copy(alpha = 0.5f).compositeOver(scheme.surface), shadowElevation = 2.dp) {
                Box(Modifier.height(22.dp).widthIn(min = 64.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(count?.toString() ?: "—", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        TallyStepButton(Icons.Default.Add, "Use one more $material sheet", status.completeBorder,
            enabled = !readOnly && (count ?: 0) < Int.MAX_VALUE,
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAdjust(1) },
            onLongClick = { false })
    }
}

@Composable
private fun SheetMaterialMenu(material: String, hidden: Boolean, onHide: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options for $material", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            DropdownMenuItem(text = { Text(if (hidden) "Unhide for this job" else "Hide for this job") },
                onClick = { expanded = false; onHide(!hidden) })
        }
    }
}
