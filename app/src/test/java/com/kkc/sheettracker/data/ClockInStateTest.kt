package com.kkc.sheettracker.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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
        assertTrue(elapsed >= 0L)
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
}
