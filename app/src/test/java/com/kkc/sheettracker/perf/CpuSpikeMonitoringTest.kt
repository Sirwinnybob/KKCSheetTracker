package com.kkc.sheettracker.perf

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CpuSpikeMonitoringTest {

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
