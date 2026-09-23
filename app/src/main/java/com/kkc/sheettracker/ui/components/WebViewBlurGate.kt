package com.kkc.sheettracker.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * True while any live WebView (the 3D model pane) is on screen. Haze blur sampling a WebView
 * makes Chromium's compositor ask for a redraw every vsync — about 120% process CPU with the pane
 * open and completely idle — so frosted chrome that overlays a WebView falls back to a solid
 * surface while this is active.
 */
class WebViewBlurGate {
    private val lock = Any()
    private var holders = 0
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** Registers one visible WebView. Call the returned function once it leaves the screen. */
    fun acquire(): () -> Unit {
        synchronized(lock) {
            holders++
            _isActive.value = true
        }
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    holders--
                    _isActive.value = holders > 0
                }
            }
        }
    }

    companion object {
        val shared = WebViewBlurGate()
    }
}
