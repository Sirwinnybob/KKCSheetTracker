package com.kkc.sheettracker.ui.theme

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ThemeCatalogStateTest {
    @Test
    fun slowCatalogReadDoesNotBlockInitialStateAndRunsOffCallerThread() = runBlocking {
        val workerThread = AtomicReference<Thread>()
        val dispatcher = Executors.newSingleThreadExecutor {
            Thread(it, "theme-test-io").also(workerThread::set)
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var readerThread: Thread? = null
        val loaded = KKCThemeRepository.builtInCatalog().copy(syncedDefaultThemeId = "loaded")
        try {
            val state = ThemeCatalogState(scope, {
                readerThread = Thread.currentThread()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                loaded
            }, dispatcher)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(state.catalog.value.syncedDefaultThemeId)
            assertSame(workerThread.get(), readerThread)
            release.countDown()
            assertEquals(loaded, withTimeout(2_000) { state.catalog.first { it.syncedDefaultThemeId == "loaded" } })
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun reloadFailureKeepsLastGoodThemeAndCanRecover() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val reads = AtomicInteger()
        val loaded = KKCThemeRepository.builtInCatalog().copy(syncedDefaultThemeId = "saved")
        try {
            val state = ThemeCatalogState(scope, {
                when (reads.incrementAndGet()) {
                    2 -> error("storage unavailable")
                    3 -> loaded.copy(syncedDefaultThemeId = "recovered")
                    else -> loaded
                }
            }, dispatcher)
            withTimeout(2_000) { state.catalog.first { it.syncedDefaultThemeId == "saved" } }
            state.reload()
            val failed = withTimeout(2_000) { state.catalog.first { it.loadMessages.isNotEmpty() } }
            assertEquals("saved", failed.syncedDefaultThemeId)
            assertTrue(failed.loadMessages.single().contains("storage unavailable"))
            state.reload()
            withTimeout(2_000) { state.catalog.first { it.syncedDefaultThemeId == "recovered" } }
            assertTrue(state.catalog.value.loadMessages.isEmpty())
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }
}
