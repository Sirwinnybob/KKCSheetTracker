package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodRowProgress

/** Raw production tallies may exceed the order; only completion credit is bounded. */
fun adjustTallyCount(current: Int, delta: Int): Int =
    (current.coerceAtLeast(0).toLong() + delta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

fun creditedHardwoodProgress(qty: Int, progress: HardwoodRowProgress): HardwoodRowProgress {
    val target = qty.coerceAtLeast(0)
    val done = progress.doneCount.coerceIn(0, target)
    return progress.copy(doneCount = done, badCount = progress.badCount.coerceIn(0, target - done))
}
