package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

data class LowEndModeFlags(
    val masterEnabled: Boolean,
    val animationsEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val blurEnabled: Boolean,
    val lazyLoadingEnabled: Boolean
) {
    val animationsDisabled get() = masterEnabled && !animationsEnabled
    val shadowsDisabled get() = masterEnabled && !shadowsEnabled
    val blurDisabled get() = masterEnabled && !blurEnabled
    val lazyLoadingActive get() = masterEnabled && lazyLoadingEnabled
}

val LocalLowEndMode = staticCompositionLocalOf<LowEndModeFlags> {
    LowEndModeFlags(false, true, true, true, true)
}