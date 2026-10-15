package com.kkc.sheettracker.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotStackAggregatorTest {

    private fun worker(n: Int, state: Boolean, vararg frames: String) =
        ThreadStackSnapshot("DefaultDispatcher-worker-$n", state, frames.toList())

    @Test
    fun countsEachStackOncePerSnapshotAndMergesPoolWorkers() {
        val agg = HotStackAggregator()
        // Two pool workers on the same stack in one snapshot still count once.
        agg.add(listOf(worker(1, true, "a.Decode.run", "b.Pool"), worker(2, true, "a.Decode.run", "b.Pool")))
        agg.add(listOf(worker(3, true, "a.Decode.run", "b.Pool"), worker(4, true, "c.Read.run")))

        val summary = agg.summary()
        assertEquals(2, summary.size)
        assertEquals("DefaultDispatcher-worker-#", summary[0].threadName)
        assertEquals(listOf("a.Decode.run", "b.Pool"), summary[0].frames)
        assertEquals(2, summary[0].seen)
        assertEquals(2, summary[0].snapshots)
        assertEquals(1, summary[1].seen)
    }

    @Test
    fun ignoresParkedThreadsAndEmptyStacks() {
        val agg = HotStackAggregator()
        agg.add(listOf(worker(1, false, "x.Parked"), worker(2, true)))
        assertTrue(agg.summary().isEmpty())
        assertEquals(1, agg.snapshotCount)
    }

    @Test
    fun capsFramesStacksAndSnapshots() {
        val agg = HotStackAggregator(maxSnapshots = 2, framesPerStack = 3, maxStacks = 1)
        agg.add(listOf(worker(1, true, "f1", "f2", "f3", "f4", "f5"), worker(2, true, "other")))
        agg.add(listOf(worker(1, true, "f1", "f2", "f3", "f9")))
        agg.add(listOf(worker(1, true, "f1", "f2", "f3")))

        assertEquals(2, agg.snapshotCount)
        val summary = agg.summary()
        assertEquals(1, summary.size)
        assertEquals(listOf("f1", "f2", "f3"), summary[0].frames)
        // Truncation merges f4/f5 and f9 variants into one stack; the third snapshot was dropped.
        assertEquals(2, summary[0].seen)
    }

    @Test
    fun resetClearsEverything() {
        val agg = HotStackAggregator()
        agg.add(listOf(worker(1, true, "a")))
        agg.reset()
        assertEquals(0, agg.snapshotCount)
        assertTrue(agg.summary().isEmpty())
    }

    @Test
    fun liveCaptureSkipsCallerAndMainAndSeesARunnableThread() {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val busy = Thread({ while (!stop.get()) { Math.sqrt(System.nanoTime().toDouble()) } }, "hot-test-busy")
        busy.start()
        try {
            val caller = Thread.currentThread()
            val snapshots = HotStackSource.capture(exclude = caller, mainThread = null)
            assertTrue(snapshots.none { it.name == caller.name })
            assertTrue(snapshots.any { it.name == "hot-test-busy" && it.frames.isNotEmpty() })
        } finally {
            stop.set(true)
            busy.join(1000)
        }
    }
}
