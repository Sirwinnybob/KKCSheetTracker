package com.kkc.sheettracker.viewer3d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Holds one screen-owned server and closes it only when explicitly replaced or disposed. */
internal class WingServerSessionSlot<T>(
    private val stop: (T) -> Unit
) {
    var current: T? by mutableStateOf(null)
        private set

    fun replace(next: T?) {
        val previous = current
        if (previous === next) return
        current = next
        previous?.let(stop)
    }

    fun dispose() = replace(null)
}
