package com.kkc.sheettracker.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.ArchiveRestoreClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface RestorePickerState {
    data object Loading : RestorePickerState
    data class Ready(val folderNames: List<String>) : RestorePickerState
    data object Unavailable : RestorePickerState
}

/**
 * An admin-only action selector, deliberately not an archive browser. It loads only archived
 * folder names, never downloads, caches, previews, or opens archived job content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestoreArchivedJobSheet(
    adminEnabled: Boolean,
    tabletId: String,
    clientFactory: suspend () -> ArchiveRestoreClient?,
    onCompleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerState by remember { mutableStateOf<RestorePickerState>(RestorePickerState.Loading) }
    var client by remember { mutableStateOf<ArchiveRestoreClient?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    var lifecycleState by remember { mutableStateOf<LifecycleUiState?>(null) }
    var submissionStarted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val resolvedClient = try {
            clientFactory()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        client = resolvedClient
        val folders = try {
            resolvedClient?.listArchivedFolderNames()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        pickerState = folders?.let(RestorePickerState::Ready) ?: RestorePickerState.Unavailable
    }

    val activeLifecycle = lifecycleState
    if (!restoreActionVisible(adminEnabled) && activeLifecycle == null) return
    if (activeLifecycle != null && !lifecycleSheetVisible(adminEnabled, activeLifecycle)) return

    ModalBottomSheet(
        onDismissRequest = {
            if (activeLifecycle == null || lifecycleSheetDismissible(activeLifecycle)) onDismiss()
        },
        sheetGesturesEnabled = activeLifecycle == null || lifecycleSheetGesturesEnabled(activeLifecycle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Restore archived job", style = MaterialTheme.typography.titleLarge)

            when (val currentLifecycle = lifecycleState) {
                LifecycleUiState.Queued -> OperationProgress("Queued")
                LifecycleUiState.Working -> OperationProgress("Working")
                LifecycleUiState.Completed -> OperationProgress("Completed")
                is LifecycleUiState.Failed -> {
                    Text("Failed", style = MaterialTheme.typography.titleMedium)
                    Text(currentLifecycle.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                LifecycleUiState.Confirming, null -> when (val state = pickerState) {
                    RestorePickerState.Loading -> {
                        CircularProgressIndicator()
                        Text("Loading archived job names…")
                    }
                    RestorePickerState.Unavailable -> {
                        Text("The archived-job list is unavailable. Check the server connection and try again.")
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    is RestorePickerState.Ready -> {
                        if (state.folderNames.isEmpty()) {
                            Text("There are no archived jobs to restore.")
                        } else {
                            Text("Choose a job to return to the live Jobs list.")
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                items(state.folderNames, key = { it }) { folderName ->
                                    TextButton(
                                        onClick = { selectedFolderName = folderName },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(folderName, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            selectedFolderName?.let { folderName ->
                                Text("Selected: $folderName")
                                Button(
                                    onClick = {
                                        if (submissionStarted) return@Button
                                        val lifecycleClient = client ?: return@Button
                                        submissionStarted = true
                                        scope.launch {
                                            runRestoreLifecycle(
                                                clientFactory = { lifecycleClient },
                                                folderName = folderName,
                                                initiator = tabletId,
                                                onState = { lifecycleState = it },
                                                onCompleted = onCompleted,
                                            )
                                        }
                                    },
                                    enabled = !submissionStarted,
                                ) {
                                    Text("Restore job")
                                }
                            }
                        }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
