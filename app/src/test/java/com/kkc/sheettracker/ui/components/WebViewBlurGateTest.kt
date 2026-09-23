package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haze blur drawn over a live WebView makes Chromium's compositor request a redraw every vsync
 * (about 120% process CPU with a 3D pane open and idle), so a visible WebView must be able to
 * turn navbar blur off for as long as it is on screen.
 */
class WebViewBlurGateTest {

    @Test
    fun inactiveByDefault() {
        assertFalse(WebViewBlurGate().isActive.value)
    }

    @Test
    fun activeWhileAnyHolderIsRegistered() {
        val gate = WebViewBlurGate()
        val releaseFirst = gate.acquire()
        val releaseSecond = gate.acquire()
        assertTrue(gate.isActive.value)

        releaseFirst()
        assertTrue("second pane is still showing", gate.isActive.value)

        releaseSecond()
        assertFalse(gate.isActive.value)
    }

    @Test
    fun releaseIsIdempotent() {
        val gate = WebViewBlurGate()
        val releaseFirst = gate.acquire()
        val releaseSecond = gate.acquire()

        releaseFirst()
        releaseFirst()
        assertTrue("a double release must not drop another pane's hold", gate.isActive.value)

        releaseSecond()
        assertFalse(gate.isActive.value)
    }

    @Test
    fun canBeReacquiredAfterFullyReleased() {
        val gate = WebViewBlurGate()
        gate.acquire()()
        assertFalse(gate.isActive.value)

        gate.acquire()
        assertTrue(gate.isActive.value)
    }
}
