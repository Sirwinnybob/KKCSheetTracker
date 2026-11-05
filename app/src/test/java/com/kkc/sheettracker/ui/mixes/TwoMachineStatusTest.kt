package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.cnc.MachineStatus
import com.kkc.sheettracker.data.cnc.LoadedProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoMachineStatusTest {
    @Test fun statusLabelsKeepUnknownDistinctFromIdle() {
        assertEquals("Blue SCM · unavailable", machineStatusLabel("Blue SCM", MachineStatus.Unavailable))
        assertEquals("Blue SCM · unknown", machineStatusLabel("Blue SCM", MachineStatus.Live(null, null)))
        assertEquals("Blue SCM · cutting", machineStatusLabel("Blue SCM", MachineStatus.Live(null, null, producing = true)))
        assertEquals("Red SCM · idle", machineStatusLabel("Red SCM", MachineStatus.Live(null, null, producing = false)))
        assertEquals("Blue SCM \u00b7 ready \u00b7 R260501A", machineStatusLabel("Blue SCM", MachineStatus.Live(LoadedProgram("R260501A"), null, producing = false, state = "ready")))
    }

    @Test fun blueIdleAndOfflineAreNotUnknown() {
        assertEquals("Blue SCM \u00b7 idle", machineStatusLabel("Blue SCM", MachineStatus.Live(null, null, producing = false, state = "idle")))
        assertEquals("Blue SCM \u00b7 offline", machineStatusLabel("Blue SCM", MachineStatus.Live(null, null, producing = false, state = "offline")))
        // An offline state is still offline even if a service omits displayProducing.
        assertEquals("Blue SCM \u00b7 offline", machineStatusLabel("Blue SCM", MachineStatus.Live(null, null, state = "offline")))
    }

    @Test fun headerPillMatchesStatusText() {
        assertEquals("Offline", machineHeaderLabel(MachineStatus.Live(null, null, producing = false, state = "offline")))
        assertEquals("Idle", machineHeaderLabel(MachineStatus.Live(null, null, producing = false, state = "idle")))
        assertEquals("Ready", machineHeaderLabel(MachineStatus.Live(null, null, producing = false, state = "ready")))
        assertEquals("Cutting", machineHeaderLabel(MachineStatus.Live(null, null, producing = true)))
        assertEquals("Unknown", machineHeaderLabel(MachineStatus.Live(null, null)))
        assertEquals("Unavailable", machineHeaderLabel(MachineStatus.Unavailable))
    }
}
