package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

data class LowEndModeFlags(
    val masterEnabled: Boolean,
    val animationsEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val blurEnabled: Boolean,
    val lazyLoadingEnabled: Boolean,
    /**
     * Transient, not a setting: true while a live WebView (the 3D pane) is on screen. Blur over a
     * WebView makes Chromium redraw every vsync (~120% CPU while idle), so all blur is off until
     * the pane leaves, whatever the user's low-end settings say. See [WebViewBlurGate].
     */
    val webViewBlurSuppressed: Boolean = false
) {
    val animationsDisabled get() = masterEnabled && !animationsEnabled
    val shadowsDisabled get() = masterEnabled && !shadowsEnabled
    val blurDisabled get() = (masterEnabled && !blurEnabled) || webViewBlurSuppressed
    val lazyLoadingActive get() = masterEnabled && lazyLoadingEnabled
}

val LocalLowEndMode = staticCompositionLocalOf<LowEndModeFlags> {
    LowEndModeFlags(false, true, true, true, true)
}