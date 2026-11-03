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
}
