package com.kkc.sheettracker.ui.mixes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PgmPreviewSheet(state: PgmPreviewState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Preview edits · ${state.result?.name ?: state.key?.pgm.orEmpty()}", style = MaterialTheme.typography.titleLarge)
            Text("Read-only preview. Apply 2nd pass saves your staged edits.", style = MaterialTheme.typography.bodyMedium)
            if (state.stale) Text("Cached preview. Refresh CNC revisions before reviewing new changes.", color = MaterialTheme.colorScheme.error)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loading) { Text("Preparing preview…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val file = state.result
            if (file != null) {
                Text("${file.before.mode} → ${file.proposed.mode} · P-unload ${if (file.proposed.punloadRemoved == true) "removed" else "present"}")
                file.notices.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                if (file.conversion.fallbackReasons.isNotEmpty()) Text("SCM fallback used to prepare this preview.", style = MaterialTheme.typography.bodySmall)
                var tab by remember(file.proposedSourceSha256, file.originalSha256) { mutableIntStateOf(0) }
                TabRow(selectedTabIndex = tab) {
                    listOf("Changes", "Current", "Proposed").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                val source = when (tab) { 0 -> file.diff.ifEmpty { "No source changes." }; 1 -> file.currentSource; else -> file.proposedSource }
                val lines = remember(source) { source.lineSequence().toList() }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val minimumWidth = maxWidth
                    SelectionContainer {
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            LazyColumn(Modifier.fillMaxHeight().widthIn(min = minimumWidth)) {
                                items(lines) { line -> Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall, softWrap = false) }
                            }
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh CNC revisions") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
