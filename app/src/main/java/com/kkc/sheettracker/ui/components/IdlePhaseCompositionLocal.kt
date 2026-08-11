package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.kkc.sheettracker.data.IdlePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val LocalIdlePhase = staticCompositionLocalOf<StateFlow<IdlePhase>> {
    MutableStateFlow(IdlePhase.ACTIVE)
}

val LocalIdlePollIntervalOverrideMs = staticCompositionLocalOf<StateFlow<Long?>> {
    MutableStateFlow(null)
}

/** Backstop reset callback for the pointerInput touch listener wrapping AppNavigation's root (Task 9) — belt-and-suspenders alongside MainActivity.onUserInteraction(). */
val LocalIdleReset = staticCompositionLocalOf<() -> Unit> { {} }
