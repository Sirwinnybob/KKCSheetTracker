package com.kkc.sheettracker.ui.cnc

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kkc.sheettracker.KKCApplication
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.cnc.*
import kotlinx.coroutines.flow.combine

@Composable
internal fun applicationMachineStatusStore(): MachineStatusStore =
    (LocalContext.current.applicationContext as KKCApplication).machineStatusStore

/** Only a resumed route owns a polling lease. A navigator sheet uses its viewer's marks. */
@Composable
internal fun rememberCncSheetMarks(
    appStateStore: AppStateStore,
    store: MachineStatusStore = applicationMachineStatusStore(),
): CncSheetMarks {
    val lifecycleOwner = LocalLifecycleOwner.current
    val marks by produceState(CncSheetMarks(), appStateStore, store, lifecycleOwner) {
        if (!appStateStore.machineStatusEnabled) return@produceState
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val lease = store.acquire() // Synchronously expires stale state before collecting.
            try {
                combine(store.status, appStateStore.cncProjection) { status, projection ->
                    appStateStore.setCncProgramHints(cncProgramHints(status))
                    matchCncSheets(status, projection.programIndex)
                }.collect { value = it }
            } finally {
                lease.close()
                value = CncSheetMarks()
            }
        }
    }
    return marks
}


/** Observe each controller independently while the CNC dashboard or sheet viewer is resumed. */
@Composable
internal fun rememberCncMachineMarks(appStateStore: AppStateStore): List<Pair<String, CncSheetMarks>> {
    val owner = LocalLifecycleOwner.current
    val app = LocalContext.current.applicationContext as KKCApplication
    val red = app.machineStatusStore
    val blue = app.newMachineStatusStore
    val marks by produceState(emptyList<Pair<String, CncSheetMarks>>(), appStateStore, owner, red, blue) {
        if (!appStateStore.machineStatusEnabled) return@produceState
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val redLease = red.acquire()
            val blueLease = blue.acquire()
            try {
                combine(red.status, blue.status, appStateStore.cncProjection) { redStatus, blueStatus, projection ->
                    val hints = cncProgramHints(redStatus).programs + cncProgramHints(blueStatus).programs
                    appStateStore.setCncProgramHints(CncProgramHints(hints.distinct()))
                    val blueKnown = (blueStatus as? MachineStatus.Live)?.let {
                        it.loaded?.running != null || it.producing != null
                    } == true
                    listOf("Red SCM" to matchCncSheets(redStatus, projection.programIndex),
                        "Blue SCM" to if (blueKnown) matchCncSheets(blueStatus, projection.programIndex) else CncSheetMarks())
                }.collect { value = it }
            } finally {
                blueLease.close(); redLease.close()
                value = emptyList()
            }
        }
    }
    return marks
}
