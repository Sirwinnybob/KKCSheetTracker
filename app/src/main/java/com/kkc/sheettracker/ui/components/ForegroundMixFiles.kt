package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kkc.sheettracker.data.mixfiles.*

/** One lease per material and screen, released when this destination is no longer STARTED. */
@Composable
internal fun rememberForegroundMixFiles(job: String, stores: Map<String, MixFilesStore>): Map<String, CatalogState> {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, stores) {
        var leases = emptyList<AutoCloseable>()
        fun sync() {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (leases.isEmpty()) leases = stores.values.map { it.acquire() }
            } else { leases.forEach { it.close() }; leases = emptyList() }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer); sync()
        onDispose { owner.lifecycle.removeObserver(observer); leases.forEach { it.close() } }
    }
    val states = remember(job, stores) { mutableStateMapOf<String, CatalogState>() }
    stores.forEach { (name, store) -> key(job, name) {
        LaunchedEffect(store, states) { store.state.collect { states[name] = it } }
    } }
    return states
}

internal fun CatalogState?.mixCatalogSnapshot(): MixFilesCatalog? = when (this) {
    is CatalogState.Live -> catalog
    is CatalogState.Offline -> cached
    else -> null // Outdated services deliberately fall back to natural order outside Mixes.
}
