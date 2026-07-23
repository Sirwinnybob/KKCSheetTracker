package com.kkc.sheettracker.ui.standards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.kkc.sheettracker.data.MoldingLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lightweight preview dialog for a molding profile linked to a Rip Cut List row.
 * Shows the profile art (with an optional measurements overlay) via the same
 * SVG-decoding ImageLoader used elsewhere in the Standards/Molding feature.
 */
@Composable
fun MoldingPreviewDialog(
    category: String,
    fileId: String,
    name: String,
    repository: MoldingLibraryRepository,
    onDismiss: () -> Unit
) {
    var showMeasurements by remember { mutableStateOf(true) }
    var svgFile by remember(category, fileId) { mutableStateOf<File?>(null) }
    val imageLoader = rememberSvgImageLoader()

    LaunchedEffect(category, fileId, showMeasurements) {
        svgFile = withContext(Dispatchers.IO) {
            repository.profileSvgFile(category, fileId, showMeasurements)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(category, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    Text("Measurements", modifier = Modifier.padding(end = 4.dp))
                    Switch(checked = showMeasurements, onCheckedChange = { showMeasurements = it })
                }
                Card(modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp)) {
                    AsyncImage(
                        model = svgFile,
                        contentDescription = name,
                        imageLoader = imageLoader,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
            }
        }
    }
}
