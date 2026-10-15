package com.kkc.sheettracker.perf

/** One live thread's state and top frames at the moment of a snapshot. */
data class ThreadStackSnapshot(val name: String, val runnable: Boolean, val frames: List<String>)

/**
 * A stack that was seen on busy (RUNNABLE) worker threads, with how many snapshots saw it.
 * [threadName] is digit-normalised so pool workers aggregate ("DefaultDispatcher-worker-#").
 */
data class HotStack(
    val threadName: String,
    val seen: Int,
    val snapshots: Int,
    val frames: List<String>
)

/**
 * Names the code behind a sustained-CPU episode. [PerfEventDetector] only fires after a minute of
 * high CPU, which is after most spikes have already ended, so snapshots are collected from the first
 * high-CPU tick and summarised when the episode is reported. Only RUNNABLE threads are kept, so parked
 * pool workers add nothing. The main thread has its own stack capture and is excluded here.
 */
class HotStackAggregator(
    private val maxSnapshots: Int = 24,
    private val framesPerStack: Int = 14,
    private val maxStacks: Int = 6
) {
    private class Bucket(val threadName: String, val frames: List<String>, var seen: Int = 0)

    private val buckets = LinkedHashMap<String, Bucket>()
    private var snapshots = 0

    val snapshotCount: Int get() = snapshots

    fun add(threads: List<ThreadStackSnapshot>) {
        if (snapshots >= maxSnapshots) return
        snapshots++
        val seenThisSnapshot = HashSet<String>()
        for (thread in threads) {
            if (!thread.runnable || thread.frames.isEmpty()) continue
            val frames = thread.frames.take(framesPerStack)
            val name = ThreadCpuSampler.normalise(thread.name)
            val key = name + "\n" + frames.joinToString("\n")
            // Several pool workers on the same stack still count once per snapshot.
            if (!seenThisSnapshot.add(key)) continue
            buckets.getOrPut(key) { Bucket(name, frames) }.seen++
        }
    }

    fun summary(): List<HotStack> = buckets.values
        .sortedByDescending { it.seen }
        .take(maxStacks)
        .map { HotStack(it.threadName, it.seen, snapshots, it.frames) }

    fun reset() {
        buckets.clear()
        snapshots = 0
    }
}

/** Live capture for [HotStackAggregator]; kept apart so the aggregation stays plain-JVM testable. */
object HotStackSource {
    /** Every live thread except [exclude] (the caller) and the main thread. */
    fun capture(exclude: Thread, mainThread: Thread?): List<ThreadStackSnapshot> =
        Thread.getAllStackTraces().mapNotNull { (thread, trace) ->
            if (thread === exclude || thread === mainThread) return@mapNotNull null
            ThreadStackSnapshot(
                name = thread.name,
                runnable = thread.state == Thread.State.RUNNABLE,
                frames = trace.map { it.toString() }
            )
        }
}
