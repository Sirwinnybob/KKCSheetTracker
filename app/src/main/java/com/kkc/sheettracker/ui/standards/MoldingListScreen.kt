package com.kkc.sheettracker.ui.standards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only molding profile library: category tabs + a grid of profile cards.
 * Data comes from [MoldingLibraryRepository], which reads the cache Hours Tracker publishes
 * under `.metadata/moldings_cache/` — this screen never writes anything.
 */
@Composable
fun MoldingListScreen(
    repository: MoldingLibraryRepository,
    onBack: () -> Unit,
    onOpenMolding: (MoldingLibraryItem) -> Unit
) {
    // Profile art ships as .svg. Coil's default ImageLoader has no SVG decoder registered
    // anywhere app-wide, so — matching the pattern already used for HeaderGradient (SVG) and
    // TimeclockBackground (GIF) — build one locally here and pass it explicitly to AsyncImage.
    val context = LocalContext.current
    val svgImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    var library by remember { mutableStateOf(MoldingLibrary()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showMeasurements by remember { mutableStateOf(true) }
    var usageCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { repository.fetchLibrary() }
        library = loaded
        selectedCategory = MoldingLibraryScreenLogic.defaultCategory(loaded)
        usageCounts = withContext(Dispatchers.IO) { repository.fetchUsageCounts() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        KKCTopAppBar(
            title = { Text("Molding") },
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

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(library.categories, key = { it }) { category ->
                FilterChip(
                    selected = category == selectedCategory,
                    onClick = { selectedCategory = category },
                    label = { Text(category) }
                )
            }
        }

        val visible = selectedCategory
            ?.let { MoldingLibraryScreenLogic.moldingsForCategory(library, it) }
            ?: emptyList()

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(visible, key = { it.id }) { item ->
                MoldingCard(
                    item = item,
                    repository = repository,
                    svgImageLoader = svgImageLoader,
                    showMeasurements = showMeasurements,
                    usageCount = usageCounts[item.id] ?: 0,
                    onClick = { onOpenMolding(item) }
                )
            }
        }
    }
}

@Composable
private fun MoldingCard(
    item: MoldingLibraryItem,
    repository: MoldingLibraryRepository,
    svgImageLoader: ImageLoader,
    showMeasurements: Boolean,
    usageCount: Int,
    onClick: () -> Unit
) {
    // profileSvgFile() does a File.exists()/.isFile check, which the repository's own docs
    // require running on Dispatchers.IO (this is the same Y:\Ready Jobs network-share wiring
    // as DeliveryScheduleRepository) — so resolve it off the composition thread, not via
    // remember{} directly, and re-resolve whenever the measurements toggle flips.
    var svgFile by remember(item.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(item.id, showMeasurements) {
        svgFile = withContext(Dispatchers.IO) {
            repository.profileSvgFile(item.category, item.fileId, showMeasurements)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            AsyncImage(
                model = svgFile,
                contentDescription = item.name,
                imageLoader = svgImageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "Used on $usageCount jobs",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
