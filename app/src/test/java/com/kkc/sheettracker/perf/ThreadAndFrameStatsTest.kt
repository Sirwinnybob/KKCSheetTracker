package com.kkc.sheettracker.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadAndFrameStatsTest {

    private fun stat(comm: String, utime: Long, stime: Long) =
        "1 ($comm) S 1 1 0 0 -1 0 0 0 0 0 $utime $stime 0 0 20 0 1 0 1 1 1 1 1 1 0 0 0 0 0 0 0 0 0 0 17 0"

    @Test
    fun parsesThreadNameAndTicksEvenWhenCommHasParentheses() {
        val t = ProcStat.parseThread(stat("Chrome_InProcGp", 30, 12))!!
        assertEquals("Chrome_InProcGp", t.name)
        assertEquals(42L, t.ticks)
        assertEquals("a (b) c", ProcStat.parseThread(stat("a (b) c", 1, 1))!!.name)
        assertNull(ProcStat.parseThread("garbage"))
    }

    @Test
    fun parsesRssFromProcStatus() {
        val status = "Name:\tkc.sheettracker\nVmPeak:\t 999 kB\nVmRSS:\t  524288 kB\nThreads:\t80\n"
        assertEquals(524288L * 1024, ProcStat.parseRssBytes(status))
        assertNull(ProcStat.parseRssBytes("Name:\tx\n"))
    }

    private fun sampler(vararg readings: Map<Int, ThreadTicks>): Pair<ThreadCpuSampler, () -> Unit> {
        var i = 0
        val s = ThreadCpuSampler(clkTck = 100, mainTid = 1, readThreads = { readings[minOf(i, readings.size - 1)] })
        return s to { i++ }
    }

    @Test
    fun attributesCpuToTheRightThreadsAndFlagsTheMainThread() {
        val (s, advance) = sampler(
            mapOf(1 to ThreadTicks("kc.sheettracker", 0), 2 to ThreadTicks("Chrome_InProcGp", 0), 3 to ThreadTicks("RenderThread", 0)),
            // 5s window at 100 ticks/s: +450 ticks on the GPU thread = 90%, +100 on main = 20%.
            mapOf(1 to ThreadTicks("kc.sheettracker", 100), 2 to ThreadTicks("Chrome_InProcGp", 450), 3 to ThreadTicks("RenderThread", 25))
        )
        assertNull(s.sample(0))
        advance()
        val r = s.sample(5_000)!!
        assertEquals(20.0, r.mainThreadCpuPercent, 0.01)
        assertEquals(listOf("Chrome_InProcGp", "kc.sheettracker", "RenderThread"), r.topThreads.map { it.name })
        assertEquals(90.0, r.topThreads[0].cpuPercent, 0.01)
    }

    @Test
    fun poolThreadsAggregateUnderOneNormalisedName() {
        val (s, advance) = sampler(
            mapOf(1 to ThreadTicks("main", 0), 4 to ThreadTicks("binder:12_1", 0), 5 to ThreadTicks("binder:12_2", 0)),
            mapOf(1 to ThreadTicks("main", 0), 4 to ThreadTicks("binder:12_1", 50), 5 to ThreadTicks("binder:12_2", 50))
        )
        s.sample(0); advance()
        val binder = s.sample(5_000)!!.topThreads.single()
        assertEquals("binder:#_#", binder.name)
        assertEquals(2, binder.threadCount)
        assertEquals(20.0, binder.cpuPercent, 0.01)
    }

    @Test
    fun aReusedTidWithADifferentNameDoesNotProduceGarbage() {
        val (s, advance) = sampler(
            mapOf(1 to ThreadTicks("main", 10), 9 to ThreadTicks("OldThread", 100000)),
            mapOf(1 to ThreadTicks("main", 10), 9 to ThreadTicks("NewThread", 50))
        )
        s.sample(0); advance()
        val top = s.sample(5_000)!!.topThreads.single()
        assertEquals("NewThread", top.name)
        assertEquals(10.0, top.cpuPercent, 0.01)
    }

    @Test
    fun frameWindowSummarisesFpsJankAndGpuTime() {
        val f = FrameWindowStats()
        repeat(100) { f.record(totalFrameMs = 8.0, gpuMs = 2.0) }
        f.record(totalFrameMs = 40.0, gpuMs = 9.0)
        f.record(totalFrameMs = 900.0)
        val s = f.snapshotAndReset(windowMs = 5_000)
        assertEquals(102, s.frames)
        assertEquals(20.4, s.fps, 0.01)
        assertEquals(2, s.jankyFrames)
        assertEquals(1, s.frozenFrames)
        assertEquals(900.0, s.maxFrameMs, 0.001)
        assertNotNull(s.avgGpuMs)
        assertEquals(9.0, s.maxGpuMs!!, 0.001)
        assertEquals("window resets", 0, f.snapshotAndReset(5_000).frames)
    }

    @Test
    fun anIdleWindowIsEmptyWithNoGpuFigures() {
        val s = FrameWindowStats().snapshotAndReset(5_000)
        assertEquals(0, s.frames)
        assertNull(s.avgGpuMs)
    }
}
