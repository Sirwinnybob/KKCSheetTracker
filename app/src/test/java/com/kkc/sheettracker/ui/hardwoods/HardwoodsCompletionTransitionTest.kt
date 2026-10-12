package com.kkc.sheettracker.ui.hardwoods

import org.junit.Assert.*
import org.junit.Test

class HardwoodsCompletionTransitionTest {
    @Test
    fun initiallyCompleteRowsDoNotFlashWhenDisplayedOrReopened() {
        val transition = HardwoodsCompletionTransition(initiallyComplete = true)
        assertFalse(transition.update(isComplete = true))
        assertFalse(transition.update(isComplete = true))
        assertFalse(HardwoodsCompletionTransition(initiallyComplete = true).update(isComplete = true))
    }

    @Test
    fun completingAnIncompleteRowFlashesOnceAndCanFlashAfterItIsReopenedForWork() {
        val transition = HardwoodsCompletionTransition(initiallyComplete = false)
        assertFalse(transition.update(isComplete = false))
        assertTrue(transition.update(isComplete = true))
        assertFalse(transition.update(isComplete = true))
        assertFalse(transition.update(isComplete = false))
        assertTrue(transition.update(isComplete = true))
    }
}
