package com.kkc.sheettracker.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfEventDetectorTest {

    private val tick = 5_000L
    private val mb = 1024L * 1024

    private fun frames(fps: Double) =
        if (fps <= 0) FrameSummary.EMPTY else FrameSummary((fps * 5).toInt(), fps, 8.0, 12.0, 0, 0, null, null)

    private fun sample(
        t: Long,
        cpu: Double = 2.0,
        fps: Double = 0.0,
        foreground: Boolean = true,
        interacting: Boolean = false,
        sinceInputMs: Long = 0L,
        rssMb: Long? = null
    ) = PerfSample(
        nowMs = t,
        cpuPercent = cpu,
        mainThreadCpuPercent = cpu / 3,
        topThreads = listOf(ThreadLoad("RenderThread", cpu / 2, 1)),
        frames = frames(fps),
        rssBytes = rssMb?.let { it * mb },
        foreground = foreground,
        viewerInteracting = interacting,
        msSinceUserInput = sinceInputMs
    )

    private fun run(d: PerfEventDetector, count: Int, from: Long = 0, build: (Long) -> PerfSample): List<PerfEvent> =
        (0 until count).flatMap { d.process(build(from + it * tick)) }

    @Test
    fun cpuEpisodeStartsAfterSixtySecondsAndEndsAfterThreeCalmSamples() {
        val d = PerfEventDetector()
        val events = run(d, 12 + 3) { t -> sample(t, cpu = if (t < 12 * tick) 150.0 else 1.0) }
        assertEquals(listOf("sustained_start", "episode_end"), events.map { it.entryType })
        assertEquals("cpu", events[0].detector)
        assertEquals(150.0, events[1].peakValue!!, 0.001)
        assertEquals("calm", events[1].endReason)
    }

    @Test
    fun oneCalmSampleInsideALongSpikeDoesNotSplitTheEpisode() {
        val d = PerfEventDetector()
        val events = run(d, 30) { t -> sample(t, cpu = if (t == 15 * tick) 1.0 else 150.0) }
        assertEquals("one episode, not two", listOf("sustained_start"), events.map { it.entryType })
    }

    @Test
    fun shortBurstsNeverLog() {
        val d = PerfEventDetector()
        val events = run(d, 40) { t -> sample(t, cpu = if ((t / tick) % 12 < 8) 150.0 else 1.0) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun touchingThe3dPaneClosesTheEpisodeWithAReasonInsteadOfDroppingIt() {
        val d = PerfEventDetector()
        val start = run(d, 12) { t -> sample(t, cpu = 150.0) }
        assertEquals(listOf("sustained_start"), start.map { it.entryType })
        val ev = d.process(sample(12 * tick, cpu = 150.0, interacting = true))
        assertEquals(listOf("episode_end"), ev.map { it.entryType })
        assertEquals("viewer_interaction", ev[0].endReason)
    }

    @Test
    fun noNewCpuEpisodeDuringTheGraceWindowAfterInteraction() {
        val d = PerfEventDetector()
        d.process(sample(0, cpu = 150.0, interacting = true))
        // 10s grace: the first two calm-of-interaction samples must not count toward a new streak.
        val events = run(d, 12, from = tick) { t -> sample(t, cpu = 150.0) }
        assertTrue("streak restarted only after grace, so 12 samples are not enough", events.isEmpty())
    }

    @Test
    fun idleRedrawFiresWhenFramesKeepComingAndTheUserIsIdle() {
        val d = PerfEventDetector()
        val events = run(d, 12) { t -> sample(t, cpu = 4.0, fps = 120.0, sinceInputMs = 60_000) }
        assertEquals(listOf("idle_redraw_start"), events.map { it.entryType })
        assertEquals("idle_redraw", events[0].detector)
    }

    @Test
    fun framesWhileTheUserIsTouchingAreNotIdleRedraw() {
        val d = PerfEventDetector()
        val events = run(d, 20) { t -> sample(t, cpu = 40.0, fps = 120.0, sinceInputMs = 200) }
        assertTrue(events.none { it.detector == "idle_redraw" })
    }

    @Test
    fun anIdleAppThatDrawsNothingIsQuiet() {
        val d = PerfEventDetector()
        assertTrue(run(d, 30) { t -> sample(t, cpu = 1.0, fps = 0.0, sinceInputMs = 60_000) }.isEmpty())
    }

    @Test
    fun goingToTheBackgroundClosesAnIdleRedrawEpisode() {
        val d = PerfEventDetector()
        run(d, 12) { t -> sample(t, fps = 120.0, sinceInputMs = 60_000) }
        val ev = d.process(sample(12 * tick, fps = 120.0, sinceInputMs = 60_000, foreground = false))
        assertEquals(listOf("idle_redraw_end"), ev.map { it.entryType })
        assertEquals("backgrounded", ev[0].endReason)
    }

    @Test
    fun steadyMemoryClimbIsReportedOnceThenRateLimited() {
        val d = PerfEventDetector()
        // +40 MB per minute from 900 MB; crosses the 1 GB floor and +300 MB growth.
        val events = run(d, 12 * 25) { t -> sample(t, rssMb = 900 + (t / 60_000) * 40) }
        val memory = events.filter { it.detector == "memory" }
        assertEquals("cooldown keeps it to one report in the first ~25 min", 1, memory.size)
        assertTrue(memory[0].memoryGrowthBytes!! >= 300 * mb)
    }

    @Test
    fun highButFlatMemoryIsNotAGrowthReport() {
        val d = PerfEventDetector()
        val events = run(d, 12 * 30) { t -> sample(t, rssMb = 1500) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun gcSawtoothDoesNotLookLikeGrowth() {
        val d = PerfEventDetector()
        val events = run(d, 12 * 30) { t -> sample(t, rssMb = if ((t / 60_000) % 2 == 0L) 1400 else 1500) }
        assertFalse(events.any { it.detector == "memory" })
    }

    @Test
    fun sustainedDetectorAbortOnlyReportsWhenAnEpisodeIsOpen() {
        val s = SustainedDetector(startSamples = 2, endSamples = 1)
        assertNull(s.abort())
        s.update(0, true, 10.0)
        assertNull("not yet a full episode", s.abort())
        s.update(5, true, 10.0)
        s.update(10, true, 20.0)
        assertNotNullTransition(s.abort())
    }

    private fun assertNotNullTransition(t: SustainedDetector.Transition?) {
        assertTrue(t != null && !t.started)
    }
}
