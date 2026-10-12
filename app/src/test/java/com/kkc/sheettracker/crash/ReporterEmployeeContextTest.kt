package com.kkc.sheettracker.crash

import android.content.Context
import android.content.SharedPreferences
import com.kkc.sheettracker.perf.CpuSpikeContext
import com.kkc.sheettracker.perf.CpuSpikeMonitor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ReporterEmployeeContextTest {
    @Test
    fun preferencesPopulateCrashEmployeeBeforeActivityAndClearingDoesNotKeepOldName() {
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString("employee_name", "")).thenReturn("Morgan", "")
        val context = mock<Context>()
        whenever(context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE)).thenReturn(prefs)
        val field = CrashReporter::class.java.getDeclaredField("contextSnapshot").apply { isAccessible = true }
        val original = field.get(CrashReporter)
        try {
            CrashReporter.refreshFromPreferences(context)
            assertEquals("Morgan", (field.get(CrashReporter) as CrashReportContext).employeeName)
            CrashReporter.updateContext(currentRoute = "jobs")
            assertEquals("Morgan", (field.get(CrashReporter) as CrashReportContext).employeeName)
            CrashReporter.updateContext(employeeName = "Alex")
            assertEquals("Alex", (field.get(CrashReporter) as CrashReportContext).employeeName)
            CrashReporter.refreshFromPreferences(context)
            assertEquals("", (field.get(CrashReporter) as CrashReportContext).employeeName)
        } finally {
            field.set(CrashReporter, original)
        }
    }

    @Test
    fun preferencesPopulateCpuEmployeeAndLiveUpdatesCanReplaceAndClearIt() {
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString("employee_name", "")).thenReturn("Morgan")
        val context = mock<Context>()
        whenever(context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE)).thenReturn(prefs)
        val field = CpuSpikeMonitor::class.java.getDeclaredField("contextSnapshot").apply { isAccessible = true }
        val original = field.get(CpuSpikeMonitor)
        try {
            CpuSpikeMonitor.refreshFromPreferences(context)
            assertEquals("Morgan", (field.get(CpuSpikeMonitor) as CpuSpikeContext).employeeName)
            CpuSpikeMonitor.updateContext(currentRoute = "jobs")
            assertEquals("Morgan", (field.get(CpuSpikeMonitor) as CpuSpikeContext).employeeName)
            CpuSpikeMonitor.updateContext(employeeName = "Alex")
            assertEquals("Alex", (field.get(CpuSpikeMonitor) as CpuSpikeContext).employeeName)
            CpuSpikeMonitor.updateContext(employeeName = "")
            assertEquals("", (field.get(CpuSpikeMonitor) as CpuSpikeContext).employeeName)
        } finally {
            field.set(CpuSpikeMonitor, original)
        }
    }
}
