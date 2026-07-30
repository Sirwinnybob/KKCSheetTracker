package com.kkc.sheettracker.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AppStateFeatureFlagsTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val storage = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        context = mock()
        prefs = mock()
        editor = mock()

        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)

        whenever(prefs.getBoolean(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<Boolean>(1)
            storage[key] as? Boolean ?: default
        }

        whenever(editor.putBoolean(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<Boolean>(1)
            storage[key] = value
            editor
        }
    }

    @Test
    fun snapshot_includesLowEndFlags() {
        storage["low_end_mode"] = true
        storage["low_end_animations_enabled"] = false
        storage["low_end_shadows_enabled"] = false
        storage["low_end_blur_enabled"] = false
        storage["low_end_lazy_loading_enabled"] = false

        val flags = AppStateFeatureFlags(prefs, false).snapshot()
        assertTrue(flags.lowEndMode)
        assertFalse(flags.animationsEnabled)
        assertFalse(flags.shadowsEnabled)
        assertFalse(flags.blurEnabled)
        assertFalse(flags.lazyLoadingEnabled)
    }

    @Test
    fun snapshot_defaultsCorrect() {
        storage.clear()
        val flags = AppStateFeatureFlags(prefs, false).snapshot()
        assertFalse(flags.lowEndMode)
        assertTrue(flags.animationsEnabled)
        assertTrue(flags.shadowsEnabled)
        assertTrue(flags.blurEnabled)
        assertTrue(flags.lazyLoadingEnabled)
    }
}