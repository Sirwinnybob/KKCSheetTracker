package com.kkc.sheettracker.ui.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MigrationRequiredScreen(
    basePath: String,
    markerPath: String,
    onRetry: () -> Unit,
    onContinueViewOnly: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Dataset Migration Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "This tablet is blocked because the shared dataset has not been migrated.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Ready Jobs path:",
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = basePath,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Missing marker file:",
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = markerPath,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Run the central migration command on the shared dataset, then tap Retry Check.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "./gradlew :tools-migration:run --args=\"--base-path '<Ready Jobs Path>' --max-events 300 --write-marker\"",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retry Check")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onContinueViewOnly,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue View-Only")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Exit App")
        }
    }
}
