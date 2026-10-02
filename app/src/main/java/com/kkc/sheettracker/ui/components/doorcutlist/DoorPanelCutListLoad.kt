package com.kkc.sheettracker.ui.components.doorcutlist

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.kkc.sheettracker.data.JobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DoorPanelCutList"

internal sealed interface CutListLoad {
    object Loading : CutListLoad
    object Unavailable : CutListLoad
    data class Ready(val source: DoorPanelCutListSource) : CutListLoad
}

/**
 * Loads the door panel cut list source once for the print modal. [CutListLoad.Unavailable] when the
 * job has no sheet door-panel rows (or loading failed); the file list never waits on it.
 */
@Composable
internal fun rememberDoorPanelCutListLoad(
    jobFolderName: String,
    jobRepository: JobRepository,
): State<CutListLoad> = produceState<CutListLoad>(CutListLoad.Loading, jobFolderName) {
    value = withContext(Dispatchers.IO) {
        try {
            val basePath = jobRepository.getJobDirectory(jobFolderName).parent.orEmpty()
            val cabinetIndex = try {
                jobRepository.getCabinetSheetIndex(jobFolderName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cabinet sheet index unavailable for $jobFolderName; rooms fall back to Unassigned", e)
                null
            }
            loadDoorPanelCutListSource(basePath, jobFolderName, cabinetIndex)
                ?.let { CutListLoad.Ready(it) }
                ?: CutListLoad.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Door panel cut list unavailable for $jobFolderName", e)
            CutListLoad.Unavailable
        }
    }
}
