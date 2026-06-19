package com.kkc.sheettracker.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ClockInStateTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var state: ClockInState

    @Before
    fun setUp() {
        editor = mock()
        whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
        whenever(editor.clear()).thenReturn(editor)
        prefs = mock()
        whenever(prefs.getBoolean("is_active", false)).thenReturn(false)
        whenever(prefs.edit()).thenReturn(editor)
        state = ClockInState(prefs)
    }

    @Test
    fun `initial state is inactive`() {
        assertFalse(state.snapshot.isActive)
    }

    @Test
    fun `clockIn sets active state with job details`() {
        state.clockIn("1234", "Smith Kitchen", "1234 Smith Kitchen", "cnc")
        assertTrue(state.snapshot.isActive)
        assertEquals("1234", state.snapshot.jobNumber)
        assertEquals("Smith Kitchen", state.snapshot.jobName)
        assertEquals("cnc", state.snapshot.tabType)
        assertTrue(state.snapshot.startTimeMs > 0L)
        assertFalse(state.snapshot.pendingPrompt)
    }

    @Test
    fun `clockOut returns positive elapsed time and clears state`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        Thread.sleep(10)
        val elapsed = state.clockOut()
        assertTrue("elapsed should be positive", elapsed > 0L)
        assertFalse(state.snapshot.isActive)
    }

    @Test
    fun `triggerPrompt sets pendingPrompt when active`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        state.triggerPrompt()
        assertTrue(state.snapshot.pendingPrompt)
        assertTrue(state.snapshot.isActive)
    }

    @Test
    fun `triggerPrompt is no-op when not active`() {
        state.triggerPrompt()
        assertFalse(state.snapshot.pendingPrompt)
    }

    @Test
    fun `dismissPromptKeepActive clears pendingPrompt but keeps active`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        state.triggerPrompt()
        state.dismissPromptKeepActive()
        assertFalse(state.snapshot.pendingPrompt)
        assertTrue(state.snapshot.isActive)
    }

    @Test
    fun `pause sets paused state and keeps active session`() {
        state.clockIn("1234", "Job", "folder", "cnc")

        state.pause()

        assertTrue(state.snapshot.isActive)
        assertTrue(state.snapshot.isPaused)
        assertTrue(state.snapshot.pausedAtMs > 0L)
    }

    @Test
    fun `resume clears paused state and accumulates paused duration`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        state.pause()
        Thread.sleep(10)

        state.resume()

        assertFalse(state.snapshot.isPaused)
        assertEquals(0L, state.snapshot.pausedAtMs)
        assertTrue(state.snapshot.accumulatedPausedMs > 0L)
    }

    @Test
    fun `clockOut excludes paused duration from elapsed time`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        Thread.sleep(30)
        state.pause()
        Thread.sleep(60)
        state.resume()
        Thread.sleep(30)

        val elapsed = state.clockOut()

        assertTrue("elapsed should include active time", elapsed >= 40L)
        assertTrue("elapsed should exclude paused time", elapsed < 120L)
    }

    @Test
    fun `clockOut while paused counts pause interval as inactive`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        Thread.sleep(15)
        state.pause()
        Thread.sleep(60)

        val elapsed = state.clockOut()

        assertTrue("elapsed should include pre-pause active time", elapsed >= 10L)
        assertTrue("elapsed should not include paused wait", elapsed < 60L)
    }

    @Test
    fun `clockOut on inactive state returns 0 and does not throw`() {
        val elapsed = state.clockOut()
        assertEquals(0L, elapsed)
        assertFalse(state.snapshot.isActive)
    }

    @Test
    fun `load restores active session from persisted prefs`() {
        val activePrefs: SharedPreferences = mock()
        val activeEditor: SharedPreferences.Editor = mock()
        whenever(activeEditor.putBoolean(any(), any())).thenReturn(activeEditor)
        whenever(activeEditor.putString(any(), any())).thenReturn(activeEditor)
        whenever(activeEditor.putLong(any(), any())).thenReturn(activeEditor)
        whenever(activeEditor.clear()).thenReturn(activeEditor)
        whenever(activePrefs.edit()).thenReturn(activeEditor)
        whenever(activePrefs.getBoolean("is_active", false)).thenReturn(true)
        whenever(activePrefs.getString("job_number", "")).thenReturn("5678")
        whenever(activePrefs.getString("job_name", "")).thenReturn("Test Job")
        whenever(activePrefs.getString("folder_name", "")).thenReturn("5678 Test Job")
        whenever(activePrefs.getString("tab_type", "cnc")).thenReturn("hardwoods")
        whenever(activePrefs.getLong("start_time_ms", 0L)).thenReturn(1000L)
        whenever(activePrefs.getBoolean("pending_prompt", false)).thenReturn(false)
        whenever(activePrefs.getBoolean("is_paused", false)).thenReturn(true)
        whenever(activePrefs.getLong("paused_at_ms", 0L)).thenReturn(2_000L)
        whenever(activePrefs.getLong("accumulated_paused_ms", 0L)).thenReturn(3_000L)

        val loadedState = ClockInState(activePrefs)
        assertTrue(loadedState.snapshot.isActive)
        assertEquals("5678", loadedState.snapshot.jobNumber)
        assertEquals("Test Job", loadedState.snapshot.jobName)
        assertEquals("hardwoods", loadedState.snapshot.tabType)
        assertEquals(1000L, loadedState.snapshot.startTimeMs)
        assertTrue(loadedState.snapshot.isPaused)
        assertEquals(2_000L, loadedState.snapshot.pausedAtMs)
        assertEquals(3_000L, loadedState.snapshot.accumulatedPausedMs)
    }

    @Test
    fun `pause and resume persist new pause fields`() {
        state.clockIn("1234", "Job", "folder", "cnc")

        state.pause()
        state.resume()

        verify(editor, atLeastOnce()).putBoolean("is_paused", true)
        verify(editor, atLeastOnce()).putBoolean("is_paused", false)
        verify(editor, atLeastOnce()).putLong("paused_at_ms", 0L)
        verify(editor, atLeastOnce()).putLong(org.mockito.kotlin.eq("accumulated_paused_ms"), any())
    }

    @Test
    fun `setMinimized updates snapshot and persists`() {
        state.clockIn("1234", "Job", "folder", "cnc")
        state.setMinimized(true)
        assertTrue(state.snapshot.isMinimized)
        verify(editor, atLeastOnce()).putBoolean("is_minimized", true)

        state.setMinimized(false)
        assertFalse(state.snapshot.isMinimized)
        verify(editor, atLeastOnce()).putBoolean("is_minimized", false)
    }

    @Test
    fun `setMinimized does nothing when inactive`() {
        state.setMinimized(true)
        assertFalse(state.snapshot.isMinimized)
    }
}
