package com.kkc.sheettracker.perf

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CpuSpikeMonitoringTest {

    @Test
    fun stallReportKeepsCorrelationAndStartupAndPressureSamplesInJson() {
        val dir = Files.createTempDirectory("stall-diagnostics-json").toFile()
        try {
            val trace = StartupTrace().apply { begin(100); mark("edge_to_edge_setup", 140) }
            val store = CpuSpikeLogStore(File(dir, "pending"))
            val sample = MainThreadStallSample(
                2_140, 2_000, "RUNNABLE", "accessibility_binder", listOf("android.os.BinderProxy.transactNative"),
                trace.snapshot(2_140), parseStallSystemPressure("1.0 0.5 0.2 2/400 50", "MemAvailable: 1024000 kB", "VmRSS: 102400 kB", 140, 20, 100)
            )
            store.recordEntry(
                dir, CpuSpikeContext(tabletId = "T-1", employeeName = "Morgan"), CpuSpikeEnvironment(),
                CpuSpikeEntry("main_thread_stall_detail", "main_thread_stall", 0.0,
                    stallId = "123-2140", stallSamples = listOf(sample), longestWatchdogTickGapMs = 1_020)
            )
            val json = JsonParser.parseString(File(dir, ".metadata/cpu_spikes").listFiles()!!.single().readText()).asJsonObject
            assertEquals("123-2140", json["stallId"].asString)
            assertEquals("Morgan", json["employeeName"].asString)
            assertEquals(1_020L, json["longestWatchdogTickGapMs"].asLong)
            val writtenSample = json["stallSamples"].asJsonArray.single().asJsonObject
            assertEquals("accessibility_binder", writtenSample["waitKind"].asString)
            assertEquals("edge_to_edge_setup", writtenSample["startup"].asJsonObject["latestStage"].asString)
            assertEquals(2_040L, writtenSample["startup"].asJsonObject["elapsedMs"].asLong)
            assertEquals(20L, writtenSample["systemPressure"].asJsonObject["mainThreadCpuTicks"].asLong)
            assertEquals(100L, writtenSample["systemPressure"].asJsonObject["clockTicksPerSecond"].asLong)
            assertEquals("android.os.BinderProxy.transactNative", writtenSample["stack"].asJsonArray.single().asString)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun employeeNameSurvivesPendingReportFlushWithoutChangingFilename() {
        val dir = Files.createTempDirectory("cpu-employee").toFile()
        try {
            val pendingDir = File(dir, "pending")
            val store = CpuSpikeLogStore(pendingDir)
            store.recordEntry(
                baseDir = null,
                context = CpuSpikeContext(tabletId = "Tablet-A", employeeName = "Alex O'Neil \"Jr\""),
                environment = CpuSpikeEnvironment(),
                entry = CpuSpikeEntry("main_thread_stall", "main_thread_stall", 0.0)
            )
            val pending = pendingDir.listFiles()!!.single()
            assertEquals("Alex O'Neil \"Jr\"", JsonParser.parseString(pending.readText()).asJsonObject["employeeName"].asString)
            val flushed = store.flushPending(dir).single()
            assertEquals("Alex O'Neil \"Jr\"", JsonParser.parseString(flushed.readText()).asJsonObject["employeeName"].asString)
            assertTrue(flushed.name.endsWith("_Tablet-A_cpuspike.json"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Fields after the parenthesised comm start at "state"; utime and stime are the 12th and 13th.
    private fun statLine(comm: String, utime: Long, stime: Long): String =
        "12345 ($comm) S 1 12345 0 0 -1 4194560 100 0 0 0 $utime $stime 0 0 20 0 30 0 1000 2000000 300 " +
            "18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"

    @Test
    fun parsesUtimePlusStime() {
        assertEquals(150L, ProcStat.parseCpuTicks(statLine("kc.sheettracker", 100, 50)))
    }

    @Test
    fun commContainingSpacesAndParenthesesDoesNotShiftTheFields() {
        assertEquals(7L, ProcStat.parseCpuTicks(statLine("weird (name) x", 3, 4)))
    }

    @Test
    fun malformedStatYieldsNull() {
        assertNull(ProcStat.parseCpuTicks(""))
        assertNull(ProcStat.parseCpuTicks("12345 (short) S 1"))
    }

    @Test
    fun reportKeepsProcessAndMainThreadCpuSeparateAndRecordsWebViewState() {
        val dir = Files.createTempDirectory("cpuspike").toFile()
        try {
            val store = CpuSpikeLogStore(pendingDir = File(dir, "pending"))
            store.recordEntry(
                baseDir = dir,
                context = CpuSpikeContext(tabletId = "T-1", currentRoute = "assembly/viewer"),
                environment = CpuSpikeEnvironment(),
                entry = CpuSpikeEntry(
                    entryType = "sustained_start",
                    triggerReason = "sustained",
                    cpuPercent = 130.0,
                    mainThreadCpuPercent = 38.5,
                    webViewShowing = true,
                    endReason = "calm",
                    topThreads = listOf(ThreadLoad("Chrome_InProcGp", 90.0, 1)),
                    frames = FrameSummary(600, 120.0, 8.0, 12.0, 0, 0, 2.5, 4.0),
                    rssMb = 1400,
                    blockedMs = 2500,
                    mainThreadStack = listOf("a.B.c(B.kt:1)")
                )
            )
            val file = File(dir, ".metadata/cpu_spikes").listFiles()!!.single()
            val json = JsonParser.parseString(file.readText()).asJsonObject
            assertEquals("", json["employeeName"]?.asString)
            assertEquals(130.0, json["cpuPercent"].asDouble, 0.001)
            assertEquals(38.5, json["mainThreadCpuPercent"].asDouble, 0.001)
            assertEquals(2, json["schemaVersion"].asInt)
            assertTrue(json["webViewShowing"].asBoolean)
            assertEquals("calm", json["endReason"].asString)
            val gpu = json["topThreads"].asJsonArray[0].asJsonObject
            assertEquals("Chrome_InProcGp", gpu["name"].asString)
            assertEquals(90.0, gpu["cpuPercent"].asDouble, 0.001)
            val frames = json["frames"].asJsonObject
            assertEquals(120.0, frames["fps"].asDouble, 0.001)
            assertEquals(2.5, frames["avgGpuMs"].asDouble, 0.001)
            assertEquals(1400, json["rssMb"].asInt)
            assertEquals(2500, json["blockedMs"].asInt)
            assertEquals("a.B.c(B.kt:1)", json["mainThreadStack"].asJsonArray[0].asString)
        } finally {
            dir.deleteRecursively()
        }
    }
}
