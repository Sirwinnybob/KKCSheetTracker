package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether any in-app 3D viewer pane is both visible and actively interacting.
 * Tracker invalidation can coalesce briefly while this signal is true to avoid UI hitches.
 */
object ViewerInteractionSignal {
    private val lock = Any()
    private val activePaneIds = mutableSetOf<String>()
    private val interactingPaneIds = mutableSetOf<String>()
    private val _isViewerInteracting = MutableStateFlow(false)
    val isViewerInteracting: StateFlow<Boolean> = _isViewerInteracting.asStateFlow()

    fun setPaneActive(paneId: String, active: Boolean) {
        synchronized(lock) {
            if (active) {
                activePaneIds += paneId
            } else {
                activePaneIds -= paneId
                interactingPaneIds -= paneId
            }
            publishLocked()
        }
    }

    fun setPaneInteracting(paneId: String, interacting: Boolean) {
        synchronized(lock) {
            if (interacting) {
                // Ignore interaction reports for panes that are not active/visible.
                if (paneId in activePaneIds) {
                    interactingPaneIds += paneId
                }
            } else {
                interactingPaneIds -= paneId
            }
            publishLocked()
        }
    }

    private fun publishLocked() {
        _isViewerInteracting.value = activePaneIds.any { it in interactingPaneIds }
    }
}

