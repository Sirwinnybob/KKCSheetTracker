package com.kkc.sheettracker.ui.cnc

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.*
import com.kkc.sheettracker.data.cnc.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class CncStatusLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun twoVisibleConsumersShareOnePollAndStoppedOwnersReleaseIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = AtomicInteger(); val cancellations = AtomicInteger()
        val store = MachineStatusStore(MachineStateSource {
            calls.incrementAndGet()
            try { awaitCancellation() } finally { cancellations.incrementAndGet() }
        }, scope, SystemClock::elapsedRealtime)
        val base = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "cnc-lifecycle-${System.nanoTime()}")
        val progress = ProgressStore(base, "test", File(base, ".local"), readOnly = true)
        val repository = JobRepository(base)
        val app = AppStateStore(ScanCoordinator(base, repository), progress, scope = scope)
        val owner = Owner()
        val consumers = mutableIntStateOf(2)
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                repeat(consumers.intValue) { key(it) { Text(rememberCncSheetMarks(app, store).toString()) } }
            } }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { calls.get() == 1 }
            compose.runOnIdle { consumers.intValue = 1 }
            compose.waitForIdle()
            assertEquals(0, cancellations.get())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil { cancellations.get() == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { calls.get() == 2 }
            compose.runOnIdle { consumers.intValue = 0 }
            compose.waitUntil { cancellations.get() == 2 }
        } finally { scope.cancel() }
    }
}

