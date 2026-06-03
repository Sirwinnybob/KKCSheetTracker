package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.StatusSummaryRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsJobBrowserScreen(
    scanCoordinator: ScanCoordinator,
    onJobClick: (Job) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var expandedJobs by rememberSaveable { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    val scanState by scanCoordinator.state.collectAsState()
    val jobs = scanState.snapshot.jobs
    val isLoading = scanState.status == ScanStatus.LOADING && jobs.isEmpty()

    val filteredJobs = remember(jobs, searchQuery, sortByName) {
        val base = if (searchQuery.isBlank()) {
            jobs
        } else {
            jobs.filter { job ->
                job.jobNumber.contains(searchQuery, ignoreCase = true) ||
                    job.jobName.contains(searchQuery, ignoreCase = true) ||
                    job.folderName.contains(searchQuery, ignoreCase = true)
            }
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order from listJobs
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hardwoods") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onSearchClick) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Filter jobs...") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            SortToggleBar(sortByName = sortByName, onSortChange = { sortByName = it })
            Text(
                text = if (searchQuery.isBlank()) {
                    "${jobs.size} jobs"
                } else {
                    "Showing ${filteredJobs.size} of ${jobs.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            AnimatedContent(
                targetState = sortByName,
                transitionSpec = {
                    val dir = if (targetState) 1 else -1
                    slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it * dir }
                },
                label = "sort_anim"
            ) { _ ->
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (filteredJobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No jobs found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredJobs, key = { it.folderName }) { job ->
                        val materialCount = job.materials.size
                        val totalPages = job.materials.sumOf { it.pageCount }.coerceAtLeast(0)
                        val totalDocs = materialCount.coerceAtLeast(1)
                        val docsRemaining = totalDocs
                        val counts = StatusCounts(
                            total = totalDocs,
                            complete = 0,
                            notStarted = docsRemaining
                        )
                        val expanded = job.folderName in expandedJobs

                        ProgressCard(
                            title = job.folderName,
                            subtitle = "$materialCount material file" + if (materialCount == 1) "" else "s",
                            fraction = 0f,
                            expanded = expanded,
                            segmentedStatusCounts = counts,
                            showBottomProgressBar = true,
                            onToggleExpanded = {
                                expandedJobs = if (expanded) {
                                    expandedJobs - job.folderName
                                } else {
                                    expandedJobs + job.folderName
                                }
                            },
                            headerActions = {
                                if (sortByName) {
                                    val pos = job.lineupPosition
                                    if (pos != null) {
                                        StatusChip(
                                            text = "#$pos",
                                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            },
                            onClick = { onJobClick(job) }
                        ) {
                            StatusSummaryRow(counts)
                            Text(
                                "$totalPages total page" + if (totalPages == 1) "" else "s" + " tracked in this job",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
