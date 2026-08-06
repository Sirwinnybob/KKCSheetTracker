package com.kkc.sheettracker.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class UiPreferencesStoreTest {

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

        whenever(prefs.getString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<String?>(1)
            storage[key] as? String ?: default
        }

        whenever(editor.putBoolean(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<Boolean>(1)
            storage[key] = value
            editor
        }

        whenever(editor.putString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String>(1)
            storage[key] = value
            editor
        }
    }

    @Test
    fun testSafetyPreferences() {
        val store = UiPreferencesStore(context)
        assertFalse(store.isSafetySubscriber())
        assertEquals("", store.getSafetyAuthorName())

        store.setSafetySubscriber(true)
        store.setSafetyAuthorName("Bob Smith")

        assertTrue(store.isSafetySubscriber())
        assertEquals("Bob Smith", store.getSafetyAuthorName())
    }

    @Test
    fun lowEndMode_defaultsToFalse() {
        val store = UiPreferencesStore(context)
        assertFalse(store.getLowEndMode())
    }

    @Test
    fun lowEndMode_persists() {
        val store = UiPreferencesStore(context)
        store.setLowEndMode(true)
        assertTrue(store.getLowEndMode())
    }

    @Test
    fun granularFlags_defaultToTrue() {
        val store = UiPreferencesStore(context)
        assertTrue(store.getAnimationsEnabled())
        assertTrue(store.getShadowsEnabled())
        assertTrue(store.getBlurEnabled())
        assertTrue(store.getLazyLoadingEnabled())
    }

    @Test
    fun granularFlags_persistIndependently() {
        val store = UiPreferencesStore(context)
        store.setAnimationsEnabled(false)
        store.setShadowsEnabled(true)
        store.setBlurEnabled(false)
        store.setLazyLoadingEnabled(true)
        assertFalse(store.getAnimationsEnabled())
        assertTrue(store.getShadowsEnabled())
        assertFalse(store.getBlurEnabled())
        assertTrue(store.getLazyLoadingEnabled())
    }

    @Test
    fun scrollPreviewLabelOnly_defaultsToFalse() {
        val store = UiPreferencesStore(context)
        assertFalse(store.getScrollPreviewLabelOnly())
    }

    @Test
    fun scrollPreviewLabelOnly_persists() {
        val store = UiPreferencesStore(context)
        store.setScrollPreviewLabelOnly(true)
        assertTrue(store.getScrollPreviewLabelOnly())
    }
}
