package com.kkc.sheettracker.data.mixservice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixOperationSessionStoreTest {

    @Test
    fun `missing persisted value is a valid empty session state`() = runBlocking {
        val store = DataStoreMixOperationSessionStore(
            FakePreferencesDataStore(flowOf(preferencesOf()))
        )

        val result = store.load()

        assertEquals(MixOperationSessionLoadResult.Success(emptyMap()), result)
    }

    @Test
    fun `malformed persisted JSON returns failure without overwriting stored bytes`() = runBlocking {
        val raw = "{not valid JSON"
        val initial = preferencesOf(stringPreferencesKey("mix_operation_sessions") to raw)
        val dataStore = FakePreferencesDataStore(
            data = flowOf(initial),
            current = initial,
        )
        val store = DataStoreMixOperationSessionStore(dataStore)

        val result = store.load()

        assertTrue(result is MixOperationSessionLoadResult.Failure)
        assertEquals(raw, dataStore.current[stringPreferencesKey("mix_operation_sessions")])
        assertEquals(0, dataStore.updateCount)
    }

    @Test
    fun `DataStore read error returns failure instead of an empty session map`() = runBlocking {
        val dataStore = FakePreferencesDataStore(
            flow { throw IOException("disk unavailable") }
        )
        val store = DataStoreMixOperationSessionStore(dataStore)

        val result = store.load()

        assertTrue(result is MixOperationSessionLoadResult.Failure)
        assertTrue((result as MixOperationSessionLoadResult.Failure).message.contains("disk unavailable"))
        assertEquals(0, dataStore.updateCount)
    }

    private class FakePreferencesDataStore(
        override val data: Flow<Preferences>,
        var current: Preferences = preferencesOf(),
    ) : DataStore<Preferences> {
        var updateCount = 0

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updateCount += 1
            current = transform(current)
            return current
        }
    }
}
