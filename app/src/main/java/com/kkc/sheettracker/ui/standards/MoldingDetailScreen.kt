package com.kkc.sheettracker.ui.standards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.data.models.MoldingUsage
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only detail view for a single molding: a large profile drawing plus the list of jobs
 * it's used on. Data comes from [MoldingLibraryRepository], which reads the cache Hours Tracker
 * publishes under `.metadata/moldings_cache/` — this screen never writes anything.
 */
@Composable
fun MoldingDetailScreen(
    molding: MoldingLibraryItem,
    repository: MoldingLibraryRepository,
    onBack: () -> Unit
) {
    val svgImageLoader = rememberSvgImageLoader()

    var showMeasurements by remember { mutableStateOf(true) }
    var svgFile by remember(molding.id) { mutableStateOf<File?>(null) }
    var usage by remember(molding.id) { mutableStateOf<List<MoldingUsage>>(emptyList()) }

    // profileSvgFile() does a File.exists()/.isFile check, which the repository's own docs
    // require running on Dispatchers.IO (same Y:\Ready Jobs network-share wiring as
    // DeliveryScheduleRepository) — so resolve it off the composition thread, not via
    // remember{} directly, and re-resolve whenever the measurements toggle flips.
    LaunchedEffect(molding.id, showMeasurements) {
        svgFile = withContext(Dispatchers.IO) {
            repository.profileSvgFile(molding.category, molding.fileId, showMeasurements)
        }
    }

    LaunchedEffect(molding.id) {
        usage = withContext(Dispatchers.IO) { repository.fetchUsage(molding.id) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        KKCTopAppBar(
            title = { Text("${molding.name} · ${molding.category}") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                Text("Measurements", modifier = Modifier.padding(end = 4.dp))
                Switch(checked = showMeasurements, onCheckedChange = { showMeasurements = it })
            }
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            AsyncImage(
                model = svgFile,
                contentDescription = molding.name,
                imageLoader = svgImageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(12.dp)
            )
        }

        Text(
            text = "Used on ${usage.size} jobs",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(usage) { entry ->
                MoldingUsageRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MoldingUsageRow(usage: MoldingUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = usage.job,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = usage.estimatedFeet?.let { "$it ft" } ?: "",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
