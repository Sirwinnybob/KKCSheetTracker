package com.kkc.sheettracker.ui.viewer

import android.os.FileObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.kkc.sheettracker.data.PdfMarkupStore
import kotlinx.coroutines.launch

/**
 * Tracks markup changes without repeatedly scanning the tracker directory while a viewer is open.
 */
@Composable
internal fun rememberPdfMarkupChangeGeneration(
    store: PdfMarkupStore?,
    jobFolderName: String
): Long {
    var changeGeneration by remember(store, jobFolderName) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    // Re-arm after every event. This is cheap at markup-write frequency and also recovers when
    // Syncthing replaces the watched .tracker directory (MOVE_SELF/DELETE_SELF).
    DisposableEffect(store, jobFolderName, changeGeneration) {
        val observer: FileObserver? = if (store == null || jobFolderName.isBlank()) {
            null
        } else {
            store.createTrackerChangeObserver(jobFolderName) {
                scope.launch { changeGeneration++ }
            }
        }
        observer?.startWatching()
        onDispose { observer?.stopWatching() }
    }

    return changeGeneration
}
