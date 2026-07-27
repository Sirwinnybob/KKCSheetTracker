package com.kkc.sheettracker.data

data class SheetRipTallyState(val done: Int, val target: Int) {
    val isComplete: Boolean get() = target > 0 && done >= target
}

fun resolveSheetRipTallyState(storedDone: Int?, legacyDone: Boolean, target: Int): SheetRipTallyState {
    val normalizedTarget = target.coerceAtLeast(0)
    val done = when {
        storedDone != null -> storedDone.coerceIn(0, normalizedTarget)
        legacyDone -> normalizedTarget
        else -> 0
    }
    return SheetRipTallyState(done, normalizedTarget)
}
