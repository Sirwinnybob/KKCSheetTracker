package com.kkc.sheettracker.ui.jobs

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import com.kkc.sheettracker.ui.components.NavBarDecorationState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class JobsSearchNavBarTest {
    // Allocate outside composable code so compiler lambda memoization cannot hide feedback.
    private fun goCallback(version: Int, record: (Int) -> Unit): () -> Unit = { record(version) }

    @Test
    fun searchPublicationSettlesAndStillUpdatesTextCallbacksAndOwnership() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val nav = NavBarDecorationState()
        val query = mutableStateOf(TextFieldValue(""))
        val active = mutableStateOf(true)
        val callbackVersion = mutableStateOf(0)
        val unrelated = mutableStateOf(0)
        var goVersion = -1
        var compositions = 0
        var frame = 0L
        suspend fun drainFrames() {
            repeat(12) {
                Snapshot.sendApplyNotifications()
                yield()
                frameClock.sendFrame(++frame * 16_666_667L)
                yield()
            }
        }
        try {
            composition.setContent {
                // Model the parent and Jobs list reading the same state the side effect publishes.
                nav.searchDecoration
                unrelated.value
                val version = callbackVersion.value
                JobsSearchNavBar(nav, "jobs", active.value, query.value,
                    onQueryChange = { query.value = it },
                    onGo = goCallback(version) { goVersion = it })
                SideEffect { compositions++ }
            }
            drainFrames()
            val settled = compositions
            drainFrames()
            assertEquals("Idle search publication must stop invalidating its readers", settled, compositions)
            val initial = nav.searchDecoration!!
            unrelated.value++
            drainFrames()
            assertSame("Unrelated parent recomposition must retain the decoration", initial, nav.searchDecoration)

            initial.onSearchTextChange(TextFieldValue("644"))
            drainFrames()
            assertEquals("644", nav.searchDecoration!!.searchTextValue.text)
            assertEquals("Filtering jobs by \"644\"", nav.searchDecoration!!.contextLine)
            callbackVersion.value = 1
            drainFrames()
            nav.searchDecoration!!.onGo()
            assertEquals(1, goVersion)
            val afterUpdates = compositions
            drainFrames()
            assertEquals(afterUpdates, compositions)

            active.value = false
            drainFrames()
            assertNull(nav.searchDecoration)
            assertEquals("", nav.owner)
            active.value = true
            drainFrames()
            assertNotNull(nav.searchDecoration)
            composition.dispose()
            assertNull(nav.searchDecoration)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }
}
