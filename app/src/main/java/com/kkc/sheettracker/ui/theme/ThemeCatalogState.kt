package com.kkc.sheettracker.ui.theme

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serial, coalesced file reads. Composition always starts with an in-memory theme. */
internal class ThemeCatalogState(
    scope: CoroutineScope,
    loadCatalog: () -> KKCThemeCatalog,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableCatalog = MutableStateFlow(KKCThemeRepository.builtInCatalog())
    val catalog: StateFlow<KKCThemeCatalog> = mutableCatalog.asStateFlow()
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(ioDispatcher) {
            for (request in reloads) {
                try {
                    mutableCatalog.value = loadCatalog()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A temporarily unavailable synced folder must not reset the selected theme.
                    mutableCatalog.value = mutableCatalog.value.copy(
                        loadMessages = listOf("Unable to reload themes: ${error.message ?: "storage unavailable"}")
                    )
                }
            }
        }.invokeOnCompletion { reloads.close() }
        reload()
    }

    fun reload() {
        reloads.trySend(Unit)
    }
}
