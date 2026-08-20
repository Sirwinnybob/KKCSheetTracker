package com.kkc.sheettracker.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.ArchiveAdminClient
import com.kkc.sheettracker.data.ArchiveCacheManager
import com.kkc.sheettracker.data.ArchiveCacheResult
import com.kkc.sheettracker.data.ArchiveLibraryClient
import com.kkc.sheettracker.data.ArchiveLibraryStore
import com.kkc.sheettracker.data.models.ArchiveJobEntry
import kotlinx.coroutines.launch
import java.io.File

/**
 * User-facing screen for the Ready Jobs archive library: lists archived jobs from the live
 * WebSocket-backed [ArchiveLibraryStore], downloads+extracts the tapped entry via
 * [ArchiveCacheManager] (or reuses an up-to-date cache hit), then hands off to job-detail
 * navigation via [onOpenArchiveJob] (wired up by Task 7). Restore is admin-gated per
 * [AdminModeController.enabled] -- browsing/opening archived jobs is available to everyone,
 * only the restore-to-live trigger requires admin mode. There is no "archive" trigger on this
 * screen: this screen lists jobs that are already archived, so only restore is a meaningful
 * action here -- archiving a still-live job belongs to a live-job screen, out of scope for this
 * task.
 */
@Composable
fun ArchiveLibraryScreen(
    tabletId: String,
    isDebugBuild: Boolean,
    onOpenArchiveJob: (archiveJobId: String, folderName: String, contentVersion: String) -> Unit,
) {
    val context = LocalContext.current
    val adminEnabled by AdminModeController.enabled.collectAsState()
    val store = remember { ArchiveLibraryStore() }
    val entries by store.entries.collectAsState()
    val connected by store.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }

    val client = remember {
        ArchiveLibraryClient(
            config = adminSyncConfig,
            tabletId = tabletId,
            onSnapshot = { store.applySnapshot(it) },
            onDelta = { id, entry -> store.applyDelta(id, entry) },
            onConnectionState = { store.setConnected(it) },
        )
    }
    DisposableEffect(client) {
        client.start()
        onDispose { client.stop() }
    }

    var downloadingArchiveJobId by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    fun openArchive(entry: ArchiveJobEntry) {
        // Guards against a fast double-tap starting two concurrent downloads for the same
        // entry: rememberCoroutineScope()'s launch runs synchronously up to its first suspend
        // point (adminSyncConfig.getServerUrl() below), so by the time a second onClick dispatch
        // for the same entry reaches this check, downloadingArchiveJobId already reflects the
        // first tap's in-flight state -- this does not depend on the button having visually
        // swapped to the progress indicator yet, which only happens on the next frame.
        if (downloadingArchiveJobId == entry.archiveJobId) return
        scope.launch {
            downloadingArchiveJobId = entry.archiveJobId
            downloadError = null
            val serverUrl = adminSyncConfig.getServerUrl()
            if (serverUrl == null) {
                downloadError = "No server configured"
                downloadingArchiveJobId = null
                return@launch
            }
            // ArchiveCacheManager self-manages a 24h expiry (see pruneExpiredEntries) and treats
            // a missing/evicted entry as an ordinary re-download rather than an error, so this is
            // genuinely reclaimable cache data -- cacheDir (not filesDir) matches both that
            // contract and the codebase's existing convention for download-cache-like storage
            // (see SupplyItemDetailScreen's "supply_temp" and SafetyDocumentsScreen's camera temp
            // file, both under context.cacheDir; filesDir elsewhere in this codebase is reserved
            // for data that must persist, e.g. CrashReporter's pending reports).
            val cacheRoot = File(context.cacheDir, "archive-cache")
            val manager = ArchiveCacheManager(cacheRoot, serverUrl)
            val cached = manager.getCachedEntry(entry.archiveJobId)
            if (cached != null && cached.contentVersion == entry.contentVersion) {
                manager.touchLastAccess(entry.archiveJobId)
                downloadError = null
                onOpenArchiveJob(entry.archiveJobId, cached.folderName, entry.contentVersion)
                downloadingArchiveJobId = null
                return@launch
            }
            when (val result = manager.downloadAndExtract(entry.archiveJobId, entry.folderName, entry.contentVersion)) {
                is ArchiveCacheResult.Success -> {
                    downloadError = null
                    onOpenArchiveJob(entry.archiveJobId, entry.folderName, entry.contentVersion)
                }
                is ArchiveCacheResult.Failure -> {
                    downloadError = result.reason
                }
            }
            downloadingArchiveJobId = null
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (connected) "Archive Library" else "Archive Library (disconnected)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            downloadError?.let {
                Text(
                    "Download failed: $it",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (connected) "No archived jobs" else "Connecting…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.archiveJobId }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("${entry.jobNumber} - ${entry.jobName}", style = MaterialTheme.typography.titleMedium)
                                Text("Archived: ${entry.archivedAt}", style = MaterialTheme.typography.bodySmall)
                                if (downloadingArchiveJobId == entry.archiveJobId) {
                                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                                } else {
                                    TextButton(onClick = { openArchive(entry) }) { Text("Open") }
                                }
                                if (adminEnabled) {
                                    RestoreButton(entry, tabletId, adminSyncConfig)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestoreButton(entry: ArchiveJobEntry, tabletId: String, adminSyncConfig: AdminSyncConfig) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        scope.launch {
            val serverUrl = adminSyncConfig.getServerUrl() ?: run {
                status = "No server configured"
                return@launch
            }
            val client = ArchiveAdminClient(serverUrl)
            // Deliberately minimal collision handling for this first pass: a detected collision
            // is always resolved by restoring under a timestamp-suffixed name rather than
            // presenting a rename/overwrite dialog (a fuller collision-resolution UI is out of
            // scope here, matching the design's "first release" scoping) -- but the preview
            // result is still surfaced to the user so a silent rename isn't a surprise, rather
            // than being fetched and discarded.
            val collision = client.previewRestoreCollision(entry.folderName)?.collision == true
            if (collision) status = "Naming conflict — will restore under a new name"
            val operationId = client.triggerRestore(entry.folderName, tabletId, "timestamp")
            status = when {
                operationId != null && collision -> "Restore queued (renamed)"
                operationId != null -> "Restore queued"
                else -> "Restore failed to queue"
            }
        }
    }) {
        Text(status ?: "Restore")
    }
}
