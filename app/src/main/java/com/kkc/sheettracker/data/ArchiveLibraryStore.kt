package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reduces [ArchiveLibraryClient]'s raw snapshot/delta callbacks into a
 * [StateFlow] a Compose screen can `collectAsState()` directly, plus a
 * connection flag.
 *
 * Deliberately much simpler than [com.kkc.sheettracker.data.unified.LiveAwareUnifiedMetadataEngine]:
 * there is no file-backed fallback delegate, because there is no local/offline
 * archive data to fall back to. When disconnected, [entries] simply keeps
 * whatever was last known instead of being cleared.
 *
 * Threading: like [LiveAwareUnifiedMetadataEngine.applySnapshot]/`applyDelta`,
 * this class assumes [applySnapshot]/[applyDelta]/[setConnected] are only ever
 * invoked from [ArchiveLibraryClient]'s single WebSocket listener thread.
 * OkHttp invokes a given `WebSocketListener`'s callbacks serially (the reader
 * loop calls into the listener synchronously, one frame at a time), so the
 * read-modify-write on [liveMap] below never races against itself in
 * practice. No additional locking is used here, matching
 * [LiveAwareUnifiedMetadataEngine]'s identical (`@Volatile`, unsynchronized)
 * pattern for the same reason.
 */
class ArchiveLibraryStore {
    @Volatile private var liveMap: Map<String, ArchiveJobEntry> = emptyMap()

    private val _entries = MutableStateFlow<List<ArchiveJobEntry>>(emptyList())
    val entries: StateFlow<List<ArchiveJobEntry>> = _entries.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun applySnapshot(archives: Map<String, ArchiveJobEntry>) {
        liveMap = archives.toMap()
        publish()
        _connected.value = true
    }

    fun applyDelta(archiveJobId: String, entry: ArchiveJobEntry?) {
        liveMap = if (entry == null) liveMap - archiveJobId else liveMap + (archiveJobId to entry)
        publish()
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    private fun publish() {
        _entries.value = liveMap.values.sortedWith(ARCHIVE_JOB_NUMBER_ORDER)
    }

    companion object {
        private val LEADING_DIGITS = Regex("^\\d+")

        /**
         * Numeric-aware job-number sort. Unlike the plain `toIntOrNull()` used by
         * the live job list in [com.kkc.sheettracker.data.unified.FileBackedUnifiedMetadataEngine]
         * and [com.kkc.sheettracker.data.unified.LiveAwareUnifiedMetadataEngine],
         * this reads only the leading digit run so a lettered job number like
         * "530a" sorts as 530 (next to "530"/"530b"/531) instead of falling
         * through to Int.MAX_VALUE and landing at the end of the list -- the
         * live list gets away with the plain version because it primarily sorts
         * by lineupPosition, which the archive library has no equivalent of.
         * Ascending, with a string tiebreak for equal numeric keys so "530a"
         * sorts before "530b".
         */
        internal val ARCHIVE_JOB_NUMBER_ORDER: Comparator<ArchiveJobEntry> =
            compareBy<ArchiveJobEntry> { LEADING_DIGITS.find(it.jobNumber)?.value?.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.jobNumber }
    }
}
