package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.background
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodSearchEntry
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

internal data class HardwoodsSearchMatches(
    val results: List<HardwoodSearchEntry>,
    val totalMatches: Int
)

internal fun computeHardwoodsSearchMatches(
    allEntries: List<HardwoodSearchEntry>,
    rawQuery: String,
    maxResults: Int = 120
): HardwoodsSearchMatches {
    val query = rawQuery.trim()
    if (query.isBlank()) return HardwoodsSearchMatches(emptyList(), 0)

    val visibleResults = ArrayList<HardwoodSearchEntry>(maxResults.coerceAtMost(allEntries.size))
    var totalMatches = 0
    for (entry in allEntries) {
        val isMatch = entry.description.contains(query, ignoreCase = true) ||
            entry.jobFolderName.contains(query, ignoreCase = true) ||
            entry.jobNumber.contains(query, ignoreCase = true) ||
            entry.width.contains(query, ignoreCase = true) ||
            entry.length.contains(query, ignoreCase = true) ||
            entry.cabinetNumbers.any { it.contains(query, ignoreCase = true) }
        if (!isMatch) continue
        totalMatches += 1
        if (visibleResults.size < maxResults) visibleResults.add(entry)
    }
    return HardwoodsSearchMatches(results = visibleResults, totalMatches = totalMatches)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsSearchScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    onResultClick: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onBack: () -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val all = scanState.snapshot.searchIndex
    var query by rememberSaveable { mutableStateOf("") }

    var searchMatches by remember { mutableStateOf(HardwoodsSearchMatches(emptyList(), 0)) }
    LaunchedEffect(all, query) {
        val q = query.trim()
        if (q.isBlank()) {
            searchMatches = HardwoodsSearchMatches(emptyList(), 0)
            return@LaunchedEffect
        }
        delay(250)
        withContext(Dispatchers.Default) {
            searchMatches = computeHardwoodsSearchMatches(allEntries = all, rawQuery = query)
        }
    }
    val results = searchMatches.results

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "Hardwoods Search",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search hardwood cut list rows...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            when {
                scanState.status == ScanStatus.LOADING && all.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                query.isBlank() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Enter a search term", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                "Showing ${results.size} of ${searchMatches.totalMatches.coerceAtLeast(results.size)} matches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(results) { result ->
                            SearchCard(result = result) {
                                onResultClick(result.jobFolderName, result.docType, result.rowId)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCard(
    result: HardwoodSearchEntry,
    onClick: () -> Unit
) {
    val cabinetsText = formatCabinetDisplay(result.rawCabinetText, result.cabinetNumbers)
    val status = hardwoodsSearchStatus(result)
    val statusColor = hardwoodsSearchStatusColor(status)
    val statusText = hardwoodsSearchStatusLabel(result)

    StatusBorderedCard(
        status = status,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    result.description,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    text = statusText,
                    backgroundColor = statusColor.copy(alpha = 0.15f),
                    contentColor = statusColor
                )
            }
            Text(
                "${result.jobFolderName} • ${result.docType.uiLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${result.width} x ${result.length}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Cab: $cabinetsText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun hardwoodsSearchStatus(result: HardwoodSearchEntry): SheetStatus {
    return when {
        result.cabinetNumbers.size > 1 -> SheetStatus.IN_PROGRESS
        result.cabinetNumbers.isNotEmpty() -> SheetStatus.COMPLETE
        else -> SheetStatus.NOT_STARTED
    }
}

@Composable
private fun hardwoodsSearchStatusColor(status: SheetStatus): Color {
    val colors = KKCThemeColors.statusColors
    return when (status) {
        SheetStatus.COMPLETE -> colors.completeBorder
        SheetStatus.IN_PROGRESS -> colors.inProgressBorder
        SheetStatus.NOT_STARTED -> colors.notStarted
        SheetStatus.SKIPPED -> colors.skipBorder
        SheetStatus.HAS_BAD_PARTS -> colors.bad
        SheetStatus.RE_NESTED -> colors.completeBorder.copy(alpha = 0.5f)
    }
}

private fun hardwoodsSearchStatusLabel(result: HardwoodSearchEntry): String {
    return when {
        result.cabinetNumbers.size > 1 -> "Multi-Cab"
        result.cabinetNumbers.isNotEmpty() -> "Single-Cab"
        else -> "Unassigned"
    }
}
