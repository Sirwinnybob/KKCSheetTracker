package com.kkc.sheettracker.ui.archive

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.contentColorFor
import com.kkc.sheettracker.ui.theme.kkcZebraHighlight
import com.kkc.sheettracker.ui.theme.kkcZebraTint
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.navigation.shortLabel
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.ArchiveAdminClient
import com.kkc.sheettracker.data.ArchiveCacheManager
import com.kkc.sheettracker.data.ArchiveCacheResult
import com.kkc.sheettracker.data.ArchiveDownloadProgress
import com.kkc.sheettracker.data.ArchiveLibraryClient
import com.kkc.sheettracker.data.ArchiveLibraryStore
import com.kkc.sheettracker.data.models.ArchiveJobEntry
import com.kkc.sheettracker.ui.components.KKCPillAction
import com.kkc.sheettracker.ui.components.KKCPillActionRow
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.rememberKKCQuietPillStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    onOpenArchiveJob: (archiveJobId: String, folderName: String, contentVersion: String, workMode: WorkMode) -> Unit,
    onBack: () -> Unit,
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
    // Keep the decoration's identity stable across recompositions where the visible query
    // text hasn't changed -- constructing a fresh NavBarSearchDecoration (data class, fresh
    // lambdas) every recomposition made every reader see a "changed" value each time,
    // self-sustaining a recompose loop. Same bug and fix as UnifiedJobsScreen/JobsSearchNavBar.kt.
    val archiveSearchDecoration = remember(currentQuery) {
        NavBarSearchDecoration(
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
    }

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
    // Survives leaving for the job detail screen and coming back (rememberSaveable, tied to this
    // nav-backstack entry), so the row the operator just opened is still highlighted on return.
    var lastOpenedArchiveJobId by rememberSaveable { mutableStateOf<String?>(null) }

    fun clearDownloadState(completedArchiveJobId: String) {
        if (shouldClearArchiveDownload(downloadingArchiveJobId, completedArchiveJobId)) {
            downloadingArchiveJobId = null
            downloadProgress = null
        }
    }

    fun openArchive(entry: ArchiveJobEntry, mode: WorkMode) {
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
                    lastOpenedArchiveJobId = entry.archiveJobId
                    onOpenArchiveJob(entry.archiveJobId, cached.folderName, entry.contentVersion, mode)
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
                        lastOpenedArchiveJobId = entry.archiveJobId
                        onOpenArchiveJob(entry.archiveJobId, entry.folderName, entry.contentVersion, mode)
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
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = { Text(if (connected) "Archive Library" else "Archive Library (disconnected)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        Text(
                            archiveJobCountLabel(filteredEntries.size, entries.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TopBarClock()
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                        state = listState,
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
                                recentlyOpened = entry.archiveJobId == lastOpenedArchiveJobId,
                                onOpen = { mode -> openArchive(entry, mode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun archiveJobCountLabel(shown: Int, total: Int): String = when {
    shown == total -> if (total == 1) "1 job" else "$total jobs"
    else -> "$shown of $total jobs"
}

private val ARCHIVED_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** Ready Jobs stamps `archivedAt` as UTC ISO-8601; show the shop-local calendar date. Unparseable values pass through. */
internal fun formatArchivedAt(archivedAt: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { Instant.parse(archivedAt).atZone(zone).format(ARCHIVED_DATE_FORMAT) }.getOrDefault(archivedAt)

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

/** Row text/controls are scaled 25% up from stock Material sizes -- shop tablets are tapped at
 * arm's length and the stock sizes made mis-taps common. */
private const val ARCHIVE_ROW_SCALE = 1.25f

private fun TextStyle.scaledUp(): TextStyle = copy(fontSize = fontSize * ARCHIVE_ROW_SCALE)

@Composable
private fun ArchiveJobRow(
    entry: ArchiveJobEntry,
    opening: Boolean,
    downloadProgress: ArchiveDownloadProgress?,
    showRestore: Boolean,
    tabletId: String,
    adminSyncConfig: AdminSyncConfig,
    index: Int,
    recentlyOpened: Boolean,
    onOpen: (WorkMode) -> Unit,
) {
    // The job the operator just opened gets its zebra color at full strength (instead of the
    // faint tint every other row gets) so it's easy to spot again after coming back from it.
    val background = if (recentlyOpened) {
        kkcZebraHighlight(index)
    } else {
        kkcZebraTint(index).compositeOver(MaterialTheme.colorScheme.surface)
    }
    val rowContentColor = if (recentlyOpened) contentColorFor(background) else null

    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = rowContentColor ?: MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .semantics {
                contentDescription = if (opening) {
                    "Opening archived job ${entry.jobNumber}, ${entry.jobName}"
                } else {
                    "Archived job ${entry.jobNumber}, ${entry.jobName}"
                }
            },
    ) {
        val secondaryTextColor = rowContentColor?.copy(alpha = 0.75f) ?: MaterialTheme.colorScheme.onSurfaceVariant
        // Text stacks on the left; the pills are centered against the whole card, not one line of it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
        ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = entry.jobNumber,
                            style = MaterialTheme.typography.titleSmall.scaledUp(),
                            color = rowContentColor ?: MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = entry.jobName,
                            style = MaterialTheme.typography.titleSmall.scaledUp(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "Archived ${formatArchivedAt(entry.archivedAt)}",
                        style = MaterialTheme.typography.bodySmall.scaledUp(),
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
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
                                    .padding(top = 8.dp)
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                            Text(
                                "Downloading $percentage%",
                                style = MaterialTheme.typography.labelSmall.scaledUp(),
                                color = secondaryTextColor,
                            )
                        } else {
                            Text(
                                "Downloading…",
                                style = MaterialTheme.typography.labelSmall.scaledUp(),
                                color = secondaryTextColor,
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
                Spacer(Modifier.width(15.dp))
                if (opening) {
                    if (downloadProgress?.totalBytes?.takeIf { it > 0L } == null) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Text(
                            "Opening…",
                            style = MaterialTheme.typography.labelSmall.scaledUp(),
                            color = rowContentColor ?: MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    KKCPillActionRow(
                        actions = WorkMode.entries.filter { it != WorkMode.ASSEMBLY }.map { mode ->
                            KKCPillAction(label = mode.shortLabel(), onClick = { onOpen(mode) })
                        },
                        style = rememberKKCQuietPillStyle(),
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
