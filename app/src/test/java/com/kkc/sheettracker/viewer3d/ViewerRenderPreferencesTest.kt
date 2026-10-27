package com.kkc.sheettracker.viewer3d

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ViewerRenderPreferencesTest {
    @Test
    fun bridgeReadsSavedAppPreferencesAcrossViewerSessions() {
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getBoolean("show_outlines", true)).thenReturn(false)
        whenever(prefs.getString("antialiasing_quality", "high")).thenReturn("low")
        val bridge = Viewer3DBridge("test", renderPreferences = ViewerRenderPreferences(prefs))
        val settings = JSONObject(bridge.getRenderSettings())
        assertFalse(settings.getBoolean("showOutlines"))
        assertEquals("low", settings.getString("antialiasingQuality"))
    }

    @Test
    fun bridgeSavesPreferencesAndNormalizesUnknownQuality() {
        val prefs = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putBoolean("show_outlines", false)).thenReturn(editor)
        whenever(editor.putString("antialiasing_quality", "high")).thenReturn(editor)
        Viewer3DBridge("test", renderPreferences = ViewerRenderPreferences(prefs)).setRenderSettings(false, "invalid")
        verify(editor).putBoolean("show_outlines", false)
        verify(editor).putString("antialiasing_quality", "high")
        verify(editor).apply()
    }
}
