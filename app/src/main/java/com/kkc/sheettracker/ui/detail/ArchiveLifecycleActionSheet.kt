package com.kkc.sheettracker.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.ArchiveLifecycleClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OPERATION_POLL_INTERVAL_MS = 750L
private const val MAX_SERVER_ERROR_LENGTH = 160

internal sealed interface LifecycleUiState {
    data object Confirming : LifecycleUiState
    data object Queued : LifecycleUiState
    data object Working : LifecycleUiState
    data object Completed : LifecycleUiState
    data class Failed(val message: String) : LifecycleUiState
}

internal fun archiveActionVisible(adminEnabled: Boolean, sourceIsLive: Boolean): Boolean =
    adminEnabled && sourceIsLive

internal suspend fun submitArchive(
    client: ArchiveLifecycleClient,
    folderName: String,
    initiator: String,
): String? = client.triggerArchive(folderName = folderName, initiator = initiator)

internal fun reduceOperation(state: String, errorSummary: String?): LifecycleUiState = when (state) {
    "queued" -> LifecycleUiState.Queued
    "working", "running" -> LifecycleUiState.Working
    "succeeded" -> LifecycleUiState.Completed
    "failed" -> LifecycleUiState.Failed(boundServerError(errorSummary))
    else -> LifecycleUiState.Working
}

internal fun lifecycleSheetDismissible(state: LifecycleUiState): Boolean =
    state is LifecycleUiState.Confirming || state is LifecycleUiState.Failed

private fun boundServerError(errorSummary: String?): String {
    val message = errorSummary?.trim().orEmpty().ifBlank { "The archive request failed." }
    return if (message.length <= MAX_SERVER_ERROR_LENGTH) message
    else "${message.take(MAX_SERVER_ERROR_LENGTH - 1)}…"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveLifecycleActionSheet(
    folderName: String,
    adminEnabled: Boolean,
    tabletId: String,
    clientFactory: suspend () -> ArchiveLifecycleClient?,
    onCompleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!adminEnabled) return

    var state by remember(folderName) { mutableStateOf<LifecycleUiState>(LifecycleUiState.Confirming) }
    var submissionStarted by remember(folderName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = {
        if (lifecycleSheetDismissible(state)) onDismiss()
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Archive live job", style = MaterialTheme.typography.titleLarge)
            Text("Archive \"$folderName\"? The job will leave the normal live Jobs list after the server completes this request.")

            when (val currentState = state) {
                LifecycleUiState.Confirming -> {
                    Button(
                        onClick = {
                            if (submissionStarted) return@Button
                            submissionStarted = true
                            state = LifecycleUiState.Queued
                            scope.launch {
                                val client = clientFactory()
                                val operationId = client?.let { submitArchive(it, folderName, tabletId) }
                                if (operationId == null) {
                                    state = LifecycleUiState.Failed("Unable to start the archive request.")
                                    return@launch
                                }
                                while (true) {
                                    val operation = client.getOperationStatus(operationId)
                                    if (operation == null) {
                                        state = LifecycleUiState.Failed("Unable to check the archive request.")
                                        return@launch
                                    }
                                    state = reduceOperation(operation.state, operation.errorSummary)
                                    when (state) {
                                        LifecycleUiState.Completed -> {
                                            onCompleted()
                                            return@launch
                                        }
                                        is LifecycleUiState.Failed -> return@launch
                                        else -> delay(OPERATION_POLL_INTERVAL_MS)
                                    }
                                }
                            }
                        },
                        enabled = !submissionStarted,
                    ) {
                        Text("Archive job")
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                LifecycleUiState.Queued -> OperationProgress("Queued")
                LifecycleUiState.Working -> OperationProgress("Working")
                LifecycleUiState.Completed -> OperationProgress("Completed")
                is LifecycleUiState.Failed -> {
                    Text("Failed", style = MaterialTheme.typography.titleMedium)
                    Text(currentState.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun OperationProgress(label: String) {
    CircularProgressIndicator()
    Text(label, style = MaterialTheme.typography.titleMedium)
    Text("Do not close this sheet while the server is processing the request.")
}
