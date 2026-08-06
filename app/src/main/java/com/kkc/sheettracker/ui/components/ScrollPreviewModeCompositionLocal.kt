package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Independent style preference for PdfLabelScrollbar's drag preview — true shows only the
 * current entry's text label, false shows the thumbnail carousel. Uses LocalLowEndMode's
 * wiring pattern (see LowEndModeCompositionLocal.kt) but simplified to a single boolean:
 * computed once in MainActivity from UiPreferencesStore/AppStateFeatureFlags, provided globally
 * so no call site between MainActivity and PdfLabelScrollbar needs to thread it through as an
 * explicit parameter.
 */
val LocalScrollPreviewLabelOnly = staticCompositionLocalOf { false }
