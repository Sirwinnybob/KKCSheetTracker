package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.CvJobWings
import com.kkc.sheettracker.data.cvjob.cvJobDataFile
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobWings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A matched, read-once view of exporter-owned data for all wing entry points. */
data class CvJobViewerSnapshot(
    val doors: CvJobDoorPreviews,
    val wings: CvJobWings
)

fun loadCvJobViewerSnapshot(basePath: String, jobFolderName: String): CvJobViewerSnapshot {
    if (basePath.isBlank() || jobFolderName.isBlank()) {
        return CvJobViewerSnapshot(CvJobDoorPreviews.NoJobData, CvJobWings.Missing)
    }
    val file = cvJobDataFile(basePath, jobFolderName)
    if (!file.isFile) return CvJobViewerSnapshot(CvJobDoorPreviews.NoJobData, CvJobWings.Missing)
    val raw = try { file.readText() } catch (error: Exception) {
        return CvJobViewerSnapshot(
            CvJobDoorPreviews.Unreadable(error.message ?: error.javaClass.simpleName),
            CvJobWings.Unreadable(error.message ?: error.javaClass.simpleName)
        )
    }
    return CvJobViewerSnapshot(parseCvJobDoorPreviews(raw), parseCvJobWings(raw))
}

/** Each scan key owns its own state, so a cancelled prior read cannot publish into the new key. */
@Composable
fun rememberCvJobViewerSnapshot(
    basePath: String,
    jobFolderName: String,
    generation: Long
): State<CvJobViewerSnapshot?> = produceState<CvJobViewerSnapshot?>(null, basePath, jobFolderName, generation) {
    value = withContext(Dispatchers.IO) { loadCvJobViewerSnapshot(basePath, jobFolderName) }
}
