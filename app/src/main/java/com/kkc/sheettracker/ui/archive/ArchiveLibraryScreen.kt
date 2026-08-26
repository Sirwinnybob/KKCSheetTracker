package com.kkc.sheettracker.ui.archive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.ArchiveAdminClient
import com.kkc.sheettracker.data.ArchiveCacheManager
import com.kkc.sheettracker.data.ArchiveCacheResult
import com.kkc.sheettracker.data.ArchiveDownloadProgress
import com.kkc.sheettracker.data.ArchiveLibraryClient
import com.kkc.sheettracker.data.ArchiveLibraryStore
import com.kkc.sheettracker.data.models.ArchiveJobEntry
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    active: Boolean = true,
) {
    val context = LocalContext.current
    val adminEnabled by AdminModeController.enabled.collectAsState()
    val store = remember { ArchiveLibraryStore() }
    val entries by store.entries.collectAsState()
    val connected by store.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val navBarDeco = LocalNavBarDecoration.current
    val focusManager = LocalFocusManager.current
    val currentQuery = query
    val screenBottomPadding = archiveScreenBottomPadding()
    val archiveSearchDecoration = NavBarSearchDecoration(
        searchTextValue = currentQuery,
        onSearchTextChange = { query = it },
        onGo = { focusManager.clearFocus() },
        isPartsEnabled = false,
        onParts = {},
        contextLine = currentQuery.text.takeIf { it.isNotBlank() }
            ?.let { "Filtering archived jobs by \"$it\"" }
            .orEmpty(),
        placeholder = "Search archive…",
        showParts = false,
        onScan = null,
    )

    SideEffect {
        updateArchiveNavBarDecoration(navBarDeco, active, archiveSearchDecoration)
    }
    DisposableEffect(navBarDeco) {
        onDispose {
            updateArchiveNavBarDecoration(navBarDeco, active = false, archiveSearchDecoration)
        }
    }

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

    // Design requirement: "App startup and periodic cleanup remove entries whose last access is
    // older than 24 hours" (see ArchiveCacheManager.pruneExpiredEntries, which is otherwise never
    // called from any running code path -- see the fix that added this LaunchedEffect). This
    // screen being composed is the practical "app startup" moment on a shop tablet: a user opens
    // the Archive tab at least once per session, which is the realistic case this needs to cover,
    // without standing up a dedicated background service/WorkManager job for it. LaunchedEffect(
    // Unit) runs this once per composition of this screen (not on every recomposition), and the
    // file-system walk runs on Dispatchers.IO so it never blocks the UI thread. serverUrl is a
    // placeholder here -- pruneExpiredEntries only ever touches cacheRoot, never the network
    // client, so no real server URL is needed to prune.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cacheRoot = File(context.cacheDir, "archive-cache")
            ArchiveCacheManager(cacheRoot, serverUrl = "").pruneExpiredEntries()
        }
    }

    var downloadingArchiveJobId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<ArchiveDownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    fun clearDownloadState(completedArchiveJobId: String) {
        if (shouldClearArchiveDownload(downloadingArchiveJobId, completedArchiveJobId)) {
            downloadingArchiveJobId = null
            downloadProgress = null
        }
    }

    fun openArchive(entry: ArchiveJobEntry) {
        // Guards against a fast double-tap starting two concurrent downloads for the same
        // entry: rememberCoroutineScope()'s launch runs synchronously up to its first suspend
        // point (adminSyncConfig.getServerUrl() below), so by the time a second onClick dispatch
        // for the same entry reaches this check, downloadingArchiveJobId already reflects the
        // first tap's in-flight state -- this does not depend on the button having visually
        // swapped to the progress indicator yet, which only happens on the next frame.
        if (!canStartArchiveOpen(downloadingArchiveJobId)) return
        downloadingArchiveJobId = entry.archiveJobId
        downloadProgress = null
        downloadError = null
        scope.launch {
            try {
                val serverUrl = adminSyncConfig.getServerUrl()
                if (serverUrl == null) {
                    downloadError = "No server configured"
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
                    return@launch
                }
                when (val result = manager.downloadAndExtract(
                    entry.archiveJobId,
                    entry.folderName,
                    entry.contentVersion,
                    onDownloadProgress = { progress ->
                        if (shouldClearArchiveDownload(downloadingArchiveJobId, entry.archiveJobId)) {
                            downloadProgress = progress
                        }
                    },
                )) {
                    is ArchiveCacheResult.Success -> {
                        downloadError = null
                        onOpenArchiveJob(entry.archiveJobId, entry.folderName, entry.contentVersion)
                    }
                    is ArchiveCacheResult.Failure -> {
                        downloadError = result.reason
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (shouldClearArchiveDownload(downloadingArchiveJobId, entry.archiveJobId)) {
                    downloadError = error.message ?: "unexpected error"
                }
            } finally {
                clearDownloadState(entry.archiveJobId)
            }
        }
    }

    val filteredEntries = remember(entries, query.text) {
        filterArchiveEntries(entries, query.text)
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (connected) "No archived jobs" else "Connecting…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (filteredEntries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No archived jobs match \"${query.text}\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 12.dp,
                            end = 16.dp,
                            bottom = screenBottomPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        itemsIndexed(filteredEntries, key = { _, entry -> entry.archiveJobId }) { index, entry ->
                            ArchiveJobRow(
                                entry = entry,
                                opening = downloadingArchiveJobId == entry.archiveJobId,
                                downloadProgress = downloadProgress.takeIf {
                                    downloadingArchiveJobId == entry.archiveJobId
                                },
                                showRestore = adminEnabled,
                                tabletId = tabletId,
                                adminSyncConfig = adminSyncConfig,
                                index = index,
                                onOpen = { openArchive(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Applies the same case-insensitive job lookup fields as the live Jobs screen. */
fun filterArchiveEntries(entries: List<ArchiveJobEntry>, query: String): List<ArchiveJobEntry> {
    if (query.isBlank()) return entries
    return entries.filter { entry ->
        entry.jobNumber.contains(query, ignoreCase = true) ||
            entry.jobName.contains(query, ignoreCase = true) ||
            entry.folderName.contains(query, ignoreCase = true)
    }
}

internal fun archiveScreenBottomPadding(): Dp = 150.dp

private const val ARCHIVE_NAV_BAR_OWNER = "archive_library"

internal fun updateArchiveNavBarDecoration(
    navBarDecoration: com.kkc.sheettracker.ui.components.NavBarDecorationState,
    active: Boolean,
    searchDecoration: NavBarSearchDecoration,
) {
    if (active) {
        navBarDecoration.owner = ARCHIVE_NAV_BAR_OWNER
        navBarDecoration.searchDecoration = searchDecoration
    } else if (navBarDecoration.owner == ARCHIVE_NAV_BAR_OWNER) {
        navBarDecoration.searchDecoration = null
        navBarDecoration.keepSearchDeco = false
        navBarDecoration.owner = ""
    }
}

internal fun canStartArchiveOpen(activeArchiveJobId: String?): Boolean = activeArchiveJobId == null

internal fun shouldClearArchiveDownload(
    activeArchiveJobId: String?,
    completedArchiveJobId: String,
): Boolean = activeArchiveJobId == completedArchiveJobId

internal fun canRestoreArchivedJob(opening: Boolean): Boolean = !opening

@Composable
private fun ArchiveJobRow(
    entry: ArchiveJobEntry,
    opening: Boolean,
    downloadProgress: ArchiveDownloadProgress?,
    showRestore: Boolean,
    tabletId: String,
    adminSyncConfig: AdminSyncConfig,
    index: Int,
    onOpen: () -> Unit,
) {
    val zebra = if (index % 2 == 0) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val background = zebra.compositeOver(MaterialTheme.colorScheme.surface)

    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = if (opening) {
                    "Opening archived job ${entry.jobNumber}, ${entry.jobName}"
                } else {
                    "Open archived job ${entry.jobNumber}, ${entry.jobName}"
                }
            }
            .clickable(enabled = !opening, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.jobNumber,
                modifier = Modifier.width(72.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.jobName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Archived ${entry.archivedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (opening) {
                    val totalBytes = downloadProgress?.totalBytes?.takeIf { it > 0L }
                    if (totalBytes != null) {
                        val fraction = (downloadProgress.bytesRead.toFloat() / totalBytes.toFloat())
                            .coerceIn(0f, 1f)
                        val percentage = (fraction * 100).toInt()
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                        Text(
                            "Downloading $percentage%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Downloading…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showRestore) {
                    RestoreButton(
                        entry = entry,
                        tabletId = tabletId,
                        adminSyncConfig = adminSyncConfig,
                        enabled = canRestoreArchivedJob(opening),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (opening) {
                if (downloadProgress?.totalBytes?.takeIf { it > 0L } == null) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "Opening…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text(
                    "Open",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RestoreButton(
    entry: ArchiveJobEntry,
    tabletId: String,
    adminSyncConfig: AdminSyncConfig,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    TextButton(enabled = enabled, onClick = {
        scope.launch {
            val serverUrl = adminSyncConfig.getServerUrl() ?: run {
                status = "No server configured"
                return@launch
            }
            val client = ArchiveAdminClient(serverUrl)
            val operationId = client.triggerRestore(entry.folderName, tabletId)
            status = if (operationId != null) "Restore queued" else "Restore failed to queue"
        }
    }) {
        Text(status ?: "Restore")
    }
}
