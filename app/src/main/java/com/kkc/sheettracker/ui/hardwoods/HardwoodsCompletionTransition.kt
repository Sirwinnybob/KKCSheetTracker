package com.kkc.sheettracker.ui.hardwoods

/** Completion feedback belongs to a status change, not to composing an already-finished row. */
internal class HardwoodsCompletionTransition(initiallyComplete: Boolean) {
    private var wasComplete = initiallyComplete

    fun update(isComplete: Boolean): Boolean {
        val becameComplete = isComplete && !wasComplete
        wasComplete = isComplete
        return becameComplete
    }
}
