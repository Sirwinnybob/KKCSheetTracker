package com.kkc.sheettracker.perf

import org.junit.Assert.assertEquals
import org.junit.Test

class StallTimelineTest {

    private fun timeline(startMs: Long = 0L) =
        StallTimeline(stallMs = 2_000L, probeIntervalMs = 1_000L, startMs = startMs)

    @Test
    fun answeredProbeReportsNothing() {
        val t = timeline()
        assertEquals(StallTimeline.Step.ProbeAnswered(endedStallMs = null), t.onTick(nowMs = 1_000L, probeRanAtMs = 5L))
    }

    @Test
    fun blockedMainThreadIsDetectedAfterStallMsThenClosedWithItsFullLength() {
        val t = timeline()
        assertEquals(StallTimeline.Step.Waiting, t.onTick(nowMs = 1_000L, probeRanAtMs = 0L))
        assertEquals(StallTimeline.Step.StallDetected(blockedMs = 2_000L), t.onTick(nowMs = 2_000L, probeRanAtMs = 0L))
        assertEquals(StallTimeline.Step.Waiting, t.onTick(nowMs = 3_000L, probeRanAtMs = 0L))
        assertEquals(
            StallTimeline.Step.ProbeAnswered(endedStallMs = 3_400L),
            t.onTick(nowMs = 4_000L, probeRanAtMs = 3_400L)
        )
    }

    // Field log 2026-10-07, SM-T738U-1220: a "15.4 s stall" while backgrounded at 0.5% CPU. The
    // cached-app freezer stopped the whole process; uptime kept counting, so on thaw the watchdog
    // saw the old unanswered probe and blamed the main thread for the freeze.
    @Test
    fun processFreezeIsNotReportedAsAStall() {
        val t = timeline()
        assertEquals(StallTimeline.Step.Waiting, t.onTick(nowMs = 15_000L, probeRanAtMs = 0L))
        assertEquals(
            StallTimeline.Step.ProbeAnswered(endedStallMs = null),
            t.onTick(nowMs = 16_000L, probeRanAtMs = 15_050L)
        )
    }

    @Test
    fun mainThreadStuckAfterAFreezeIsStillCaughtFromTheThaw() {
        val t = timeline()
        assertEquals(StallTimeline.Step.Waiting, t.onTick(nowMs = 15_000L, probeRanAtMs = 0L))
        assertEquals(StallTimeline.Step.Waiting, t.onTick(nowMs = 16_000L, probeRanAtMs = 0L))
        assertEquals(StallTimeline.Step.StallDetected(blockedMs = 2_000L), t.onTick(nowMs = 17_000L, probeRanAtMs = 0L))
    }

    @Test
    fun freezeDuringAReportedStallClosesItAtTheLastTickBeforeTheFreeze() {
        val t = timeline()
        t.onTick(nowMs = 1_000L, probeRanAtMs = 0L)
        assertEquals(StallTimeline.Step.StallDetected(blockedMs = 2_000L), t.onTick(nowMs = 2_000L, probeRanAtMs = 0L))
        assertEquals(
            StallTimeline.Step.ProbeAnswered(endedStallMs = 2_000L),
            t.onTick(nowMs = 30_000L, probeRanAtMs = 29_900L)
        )
    }

    @Test
    fun slightlyLateTicksStillCountAsOrdinaryTicks() {
        val t = timeline()
        assertEquals(StallTimeline.Step.Waiting, t.onTick(nowMs = 1_200L, probeRanAtMs = 0L))
        assertEquals(StallTimeline.Step.StallDetected(blockedMs = 2_500L), t.onTick(nowMs = 2_500L, probeRanAtMs = 0L))
    }
}
