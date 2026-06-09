package com.kkc.sheettracker.ui.assembly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers


internal data class AssemblySearchMatches(
    val results: List<AssemblySearchEntry>,
    val totalMatches: Int
)

internal fun computeAssemblySearchMatches(
    allEntries: List<AssemblySearchEntry>,
    rawQuery: String,
    maxResults: Int = 180
): AssemblySearchMatches {
    val query = rawQuery.trim()
    if (query.isBlank()) return AssemblySearchMatches(emptyList(), 0)

    val visibleResults = ArrayList<AssemblySearchEntry>(maxResults.coerceAtMost(allEntries.size))
    var totalMatches = 0
    for (entry in allEntries) {
        val isMatch = entry.jobFolderName.contains(query, ignoreCase = true) ||
            entry.jobNumber.contains(query, ignoreCase = true) ||
            entry.jobName.contains(query, ignoreCase = true) ||
            entry.cabinetNumber.equals(query, ignoreCase = true) ||
            entry.description.contains(query, ignoreCase = true) ||
            entry.material.contains(query, ignoreCase = true) ||
            entry.sectionType.contains(query, ignoreCase = true) ||
            (entry.room?.contains(query, ignoreCase = true) == true) ||
            (entry.wall?.contains(query, ignoreCase = true) == true)
        if (!isMatch) continue
        totalMatches += 1
        if (visibleResults.size < maxResults) visibleResults.add(entry)
    }
    return AssemblySearchMatches(results = visibleResults, totalMatches = totalMatches)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblySearchScreen(
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyProgressVersionHint: Long = 0L,
    onResultClick: (AssemblySearchEntry) -> Unit,
    onBack: () -> Unit
) {
    val scanState by assemblyScanCoordinator.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val all = remember(scanState.snapshot.generation, specialtyProgressVersionHint) {
        assemblyStateStore.deriveSearchIndex()
    }
    var searchMatches by remember { mutableStateOf(AssemblySearchMatches(emptyList(), 0)) }
    val results = searchMatches.results

    LaunchedEffect(all, query) {
        val q = query.trim()
        if (q.isBlank()) {
            searchMatches = AssemblySearchMatches(emptyList(), 0)
            return@LaunchedEffect
        }
        delay(250)
        withContext(Dispatchers.Default) {
            searchMatches = computeAssemblySearchMatches(allEntries = all, rawQuery = query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assembly Search") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
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
                placeholder = { Text("Search cabinets, parts, room, wall...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search icon") },
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
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onResultClick(result) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "Cabinet ${result.cabinetNumber} • ${result.jobFolderName}",
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (result.description.isNotBlank()) {
                                        Text(
                                            "${result.description} (${result.material})",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(
                                            "Assembly p${result.assemblyPage ?: "-"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "Plans p${result.plansPage ?: "-"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    val context = listOf(result.room.orEmpty(), result.wall.orEmpty())
                                        .filter { it.isNotBlank() }
                                        .joinToString(" - ")
                                    if (context.isNotBlank()) {
                                        Text(
                                            context,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
