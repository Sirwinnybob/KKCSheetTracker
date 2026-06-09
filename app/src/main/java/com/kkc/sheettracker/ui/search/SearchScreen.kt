package com.kkc.sheettracker.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class SearchResult(
    val jobFolderName: String,
    val jobNumber: String,
    val materialName: String,
    val pdfFilename: String,
    val pageNumber: Int,
    val partNumber: Int,
    val partName: String,
    val room: String,
    val cabNumber: Int
)


internal data class SearchMatches(
    val results: List<SearchResult>,
    val totalMatches: Int
)

internal fun computeSearchMatches(
    allEntries: List<SearchResult>,
    rawQuery: String,
    maxResults: Int = 100
): SearchMatches {
    val query = rawQuery.trim()
    if (query.isBlank()) return SearchMatches(emptyList(), 0)

    val visibleResults = ArrayList<SearchResult>(maxResults.coerceAtMost(allEntries.size))
    var totalMatches = 0
    for (r in allEntries) {
        val isMatch = r.partName.contains(query, ignoreCase = true) ||
            r.room.contains(query, ignoreCase = true) ||
            r.cabNumber.toString() == query ||
            r.jobNumber.contains(query, ignoreCase = true) ||
            r.jobFolderName.contains(query, ignoreCase = true) ||
            r.materialName.contains(query, ignoreCase = true)
        if (!isMatch) continue
        totalMatches += 1
        if (visibleResults.size < maxResults) visibleResults.add(r)
    }
    return SearchMatches(results = visibleResults, totalMatches = totalMatches)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    scanCoordinator: ScanCoordinator,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    onResultClick: (String, String, Int) -> Unit,
    onBack: () -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf(SearchMatches(emptyList(), 0)) }
    val results = searchMatches.results
    val listState = rememberLazyListState()
    val allResults = remember(scanState.snapshot.generation) {
        scanState.snapshot.searchIndex.map { entry ->
            SearchResult(
                jobFolderName = entry.jobFolderName,
                jobNumber = entry.jobNumber,
                materialName = entry.materialName,
                pdfFilename = entry.pdfFilename,
                pageNumber = entry.pageNumber,
                partNumber = entry.partNumber,
                partName = entry.partName,
                room = entry.room,
                cabNumber = entry.cabNumber
            )
        }
    }
    val isLoaded = scanState.status != ScanStatus.LOADING

    LaunchedEffect(query, allResults) {
        val q = query.trim()
        if (q.isBlank()) {
            searchMatches = SearchMatches(emptyList(), 0)
            return@LaunchedEffect
        }
        delay(250)
        withContext(Dispatchers.Default) {
            searchMatches = computeSearchMatches(allEntries = allResults, rawQuery = query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
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
                placeholder = { Text("Search parts, rooms, jobs...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search icon") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            if (!isLoaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (query.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Enter a search term",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No results found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "Showing ${results.size} of ${searchMatches.totalMatches.coerceAtLeast(results.size)} matches",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { result ->
                        SearchResultCard(
                            result = result,
                            onClick = {
                                onResultClick(result.jobFolderName, result.pdfFilename, result.pageNumber)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "#${result.partNumber} — ${result.partName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${result.jobFolderName} / ${result.materialName} / Sheet ${result.pageNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result.room.isNotBlank()) {
                Text(
                    text = "Room: ${result.room}  Cab#: ${result.cabNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
