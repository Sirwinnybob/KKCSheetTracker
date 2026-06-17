package com.kkc.sheettracker.ui.assembly

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import com.kkc.sheettracker.ui.components.PrintDocumentsBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.specialty.SpecialtyChecklistRow
import com.kkc.sheettracker.ui.specialty.SpecialtySurfaceMode
import com.kkc.sheettracker.ui.specialty.checklistTogglesForItem
import com.kkc.sheettracker.ui.specialty.finishInFlightUpdate
import com.kkc.sheettracker.ui.specialty.isChecklistItemComplete
import com.kkc.sheettracker.ui.specialty.isItemRelevantToMode
import com.kkc.sheettracker.ui.specialty.startInFlightUpdate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyJobDetailScreen(
    jobFolderName: String,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    jobRepository: JobRepository,
    onOpenSplitView: () -> Unit,
    onJumpToCabinet: (String) -> Unit,
    onBack: () -> Unit
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val completionOverrides = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    val inFlightUpdates = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    var toggleErrorMessage by remember(jobFolderName) { mutableStateOf<String?>(null) }
    var showPrintDialog by remember { mutableStateOf(false) }

    val resolvedItems = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        specialtyStateStore.getResolvedItems(jobFolderName)
            .filter { isItemRelevantToMode(it, SpecialtySurfaceMode.ASSEMBLY) }
    }
    val completedItems = resolvedItems.count { resolved ->
        isChecklistItemComplete(resolved, completionOverrides)
    }
    val totalItems = resolvedItems.size

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(jobFolderName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "summary") {
                Text(
                    text = if (totalItems == 0) {
                        "No specialty checklist items for this job."
                    } else {
                        "$completedItems / $totalItems checklist items complete"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onOpenSplitView) {
                        Text("Split View")
                    }
                    Button(onClick = { showPrintDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Print")
                    }
                }
            }

            if (resolvedItems.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No specialty checklist items found. Tap Split View to open the assembly viewer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(
                    items = resolvedItems,
                    key = { resolved -> resolved.item.id }
                ) { resolved ->
                    val itemToggles = checklistTogglesForItem(resolved, completionOverrides)
                    SpecialtyChecklistRow(
                        resolved = resolved,
                        toggles = itemToggles,
                        inFlightUpdates = inFlightUpdates,
                        onJumpToCabinet = if (resolved.item.cabinetNumbers.isNotEmpty()) {
                            { onJumpToCabinet(resolved.item.cabinetNumbers.first()) }
                        } else null,
                        onCheckedChange = { toggle, next ->
                            val itemId = resolved.item.id
                            val controlId = toggle.controlId
                            val previous = completionOverrides[controlId] ?: toggle.checked
                            completionOverrides[controlId] = next
                            startInFlightUpdate(inFlightUpdates, controlId)
                            coroutineScope.launch {
                                try {
                                    specialtyStateStore.setItemCompletionKey(
                                        jobFolderName = jobFolderName,
                                        itemId = itemId,
                                        completionKey = toggle.completionKey,
                                        completed = next
                                    )
                                    completionOverrides.remove(controlId)
                                    toggleErrorMessage = null
                                } catch (_: Exception) {
                                    completionOverrides[controlId] = previous
                                    val message = "Failed to update checklist item. Please retry."
                                    toggleErrorMessage = message
                                    snackbarHostState.showSnackbar(message)
                                } finally {
                                    finishInFlightUpdate(inFlightUpdates, controlId)
                                }
                            }
                        }
                    )
                }
            }

            if (!toggleErrorMessage.isNullOrBlank()) {
                item(key = "error") {
                    Text(
                        text = toggleErrorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    if (showPrintDialog) {
        PrintDocumentsBottomSheet(
            jobFolderName = jobFolderName,
            jobRepository = jobRepository,
            onDismissRequest = { showPrintDialog = false }
        )
    }
}
