package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowEndModeFlagsTest {

    private fun flags(
        master: Boolean = false,
        blur: Boolean = true,
        webViewBlurSuppressed: Boolean = false
    ) = LowEndModeFlags(
        masterEnabled = master,
        animationsEnabled = true,
        shadowsEnabled = true,
        blurEnabled = blur,
        lazyLoadingEnabled = true,
        webViewBlurSuppressed = webViewBlurSuppressed
    )

    @Test
    fun blurIsEnabledByDefault() {
        assertFalse(flags().blurDisabled)
    }

    @Test
    fun blurFollowsTheLowEndSettingWhenNoWebViewIsShowing() {
        assertTrue(flags(master = true, blur = false).blurDisabled)
        assertFalse(flags(master = true, blur = true).blurDisabled)
        assertFalse("blur switch is ignored while low-end mode is off", flags(master = false, blur = false).blurDisabled)
    }

    @Test
    fun aVisibleWebViewDisablesBlurEvenWhenLowEndModeIsOff() {
        assertTrue(flags(master = false, blur = true, webViewBlurSuppressed = true).blurDisabled)
    }

    @Test
    fun aVisibleWebViewOnlyTouchesBlur() {
        val f = flags(webViewBlurSuppressed = true)
        assertFalse(f.animationsDisabled)
        assertFalse(f.shadowsDisabled)
        assertFalse(f.lazyLoadingActive)
    }
}
