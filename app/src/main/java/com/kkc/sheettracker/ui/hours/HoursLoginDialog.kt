package com.kkc.sheettracker.ui.hours

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor

@Composable
fun HoursLoginDialog(
    initialInput: String = "",
    // Each entry is (displayLabel, committedValue): the label shown on the suggestion
    // button may prefer a friendlier display name, but the committed value must stay
    // the canonical name/PIN identity that onLogin resolves against.
    suggestions: List<Pair<String, String>> = emptyList(),
    onLogin: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialInput) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(17.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text("Who are you?") },
        text = {
            ImmersiveDialogDecor()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter your name or employee PIN to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Name or PIN") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (input.trim().isNotBlank()) onLogin(input.trim()) }
                    ),
                    shape = RoundedCornerShape(11.dp)
                )
                suggestions.take(5).forEach { (label, value) ->
                    OutlinedButton(
                        onClick = { input = value },
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onLogin(input.trim()) },
                enabled = input.trim().isNotBlank(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // Composable not yet attached to layout tree
        }
    }
}
