package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.data.mixfiles.*
import java.util.Locale

/** Count occurrences, so repeated PGM sheets never disappear from an added/removed summary. */
fun revisionSummary(older: MixFileRow, newer: MixFileRow): String {
    fun identity(entry: MixFileEntry) = entry.pgm.lowercase(Locale.ROOT)
    val old = older.entries.map(::identity)
    val fresh = newer.entries.map(::identity)
    val before = old.groupingBy { it }.eachCount()
    val after = fresh.groupingBy { it }.eachCount()
    val added = after.entries.sumOf { (pgm, count) -> (count - (before[pgm] ?: 0)).coerceAtLeast(0) }
    val removed = before.entries.sumOf { (pgm, count) -> (count - (after[pgm] ?: 0)).coerceAtLeast(0) }
    if (added > 0) return "$added sheets added"
    if (removed > 0) return "$removed sheets removed"
    if (old != fresh) return "reordered"
    if (older.entries.map { it.quantity to it.remaining } != newer.entries.map { it.quantity to it.remaining }) return "quantities changed"
    return "no change"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RevisionsPanel(group: MixGroup, onView: (MixFileRow) -> Unit, onRestore: (MixFileRow) -> Unit,
    onDeleteRevision: (MixFileRow) -> Unit, onDismiss: () -> Unit, rail: Boolean, compare: Boolean = false,
    mutationsEnabled: Boolean = true) {
    var frz by remember(group.key) { mutableStateOf(group.mix == null && group.mixRevisions.isEmpty()) }
    var viewed by remember(group.key, compare) { mutableStateOf<MixFileRow?>(null) }
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (compare) "Compare files" else "Revisions · ${group.title}", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        if (compare) RevisionComparison(group.mix, group.frz, ".mix", ".FRZ")
        else {
            if ((group.mix != null || group.mixRevisions.isNotEmpty()) && (group.frz != null || group.frzRevisions.isNotEmpty())) Row {
                FilterChip(!frz, { frz = false; viewed = null }, label = { Text(".mix") })
                Spacer(Modifier.width(8.dp))
                FilterChip(frz, { frz = true; viewed = null }, label = { Text(".FRZ") })
            }
            val current = if (frz) group.frz else group.mix
            val revisions = if (frz) group.frzRevisions else group.mixRevisions
            if (frz) Text("FRZ revisions are view-only.")
            current?.let { Text("Current · ${it.file}", style = MaterialTheme.typography.titleSmall) }
                ?: Text("Deleted mix · no current file", style = MaterialTheme.typography.titleSmall)
            viewed?.let { RevisionComparison(it, current, "Revision", "Current") }
            if (revisions.isEmpty()) Text("No saved revisions.")
            revisions.forEachIndexed { index, revision ->
                val newer = if (index == 0) current else revisions[index - 1]
                HorizontalDivider()
                Text(revision.file, style = MaterialTheme.typography.titleSmall)
                Text(revision.modifiedAt ?: revision.revisionNumber?.let { "OLD$it" } ?: revision.file.substringAfter(" — ", "CNC archive"), style = MaterialTheme.typography.labelSmall)
                Text(if (index == revisions.lastIndex || newer == null) "made at CNC" else revisionSummary(revision, newer), style = MaterialTheme.typography.bodySmall)
                revision.error?.let { Text("Unreadable: $it") }
                Row {
                    TextButton(onClick = { viewed = revision; onView(revision) }) { Text("View") }
                    if (!frz) TextButton(onClick = { onRestore(revision) }, enabled = mutationsEnabled && revision.error == null) { Text("Restore") }
                    if (!frz) TextButton(onClick = { onDeleteRevision(revision) }, enabled = mutationsEnabled) { Text("Delete revision") }
                }
            }
        }
    }
    if (rail) Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss))
            Surface(Modifier.width(560.dp).fillMaxHeight()) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun RevisionComparison(left: MixFileRow?, right: MixFileRow?, leftTitle: String, rightTitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(leftTitle to left, rightTitle to right).forEach { (title, file) -> Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            file?.error?.let { Text("Unreadable: $it") }
            file?.entries.orEmpty().forEachIndexed { i, entry -> Text("${i + 1}. ${entry.pgm} · qty ${entry.quantity}${entry.remaining?.let { " · $it left" }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            if (file == null) Text("No current file")
        } }
    }
}
