package com.kkc.sheettracker.ui.timecard

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kkc.sheettracker.data.TimecardDiscovery
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.TimeclockMessagesRepository
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock

class TimecardStoreTest {
    private lateinit var server: MockWebServer
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var store: TimecardStore

    @Before
    fun setUp() {
        runBlocking {
            server = MockWebServer()
            server.start(8765)

            val dataDir = createTempDirectory("timecard-store-${UUID.randomUUID()}").toFile()
            dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val config = TimecardServerConfig(
                PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = { File(dataDir, "timecard.preferences_pb") }
                )
            )
            config.setManualIp("127.0.0.1")
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            store = TimecardStore(
                config = config,
                discovery = TimecardDiscovery(mock<Context>()),
                messagesRepo = TimeclockMessagesRepository(dataDir),
                baseDir = dataDir
            )
            withTimeout(5_000) { store.state.first { it is TimecardUiState.Ready } }
        }
    }

    @After
    fun tearDown() {
        if (::store.isInitialized) store.cancel()
        if (::dataStoreScope.isInitialized) dataStoreScope.cancel()
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun statusServerErrorMovesReadyPinPadToUnavailableState() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))

            store.digitPressed("1")
            store.digitPressed("2")
            store.digitPressed("3")

            withTimeout(5_000) { store.state.first { it is TimecardUiState.NotFound } }
        }
    }
}

class GetCustomDisplayNameTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `reads displayName directly from employees json by pin`() {
        val baseDir = tmpFolder.newFolder("base")
        val timeCardsDir = File(baseDir, ".time_cards")
        timeCardsDir.mkdirs()
        File(timeCardsDir, "employees.json").writeText(
            """[{"id":"389","name":"Winston Ferguson","displayName":"Fergy","excluded":false}]"""
        )

        val result = TimecardStore.getCustomDisplayNameForTest(baseDir, "389")

        assertEquals("Fergy", result)
    }

    @Test
    fun `returns null when no displayName set`() {
        val baseDir = tmpFolder.newFolder("base")
        val timeCardsDir = File(baseDir, ".time_cards")
        timeCardsDir.mkdirs()
        File(timeCardsDir, "employees.json").writeText(
            """[{"id":"389","name":"Winston Ferguson","excluded":false}]"""
        )

        val result = TimecardStore.getCustomDisplayNameForTest(baseDir, "389")

        assertNull(result)
    }

    @Test
    fun `returns null for unknown pin`() {
        val baseDir = tmpFolder.newFolder("base")
        val timeCardsDir = File(baseDir, ".time_cards")
        timeCardsDir.mkdirs()
        File(timeCardsDir, "employees.json").writeText("[]")

        val result = TimecardStore.getCustomDisplayNameForTest(baseDir, "999")

        assertNull(result)
    }

    @Test
    fun `survives a rename that would have broken the old name-keyed lookup`() {
        // Regression test for the bug this migration fixes: the old
        // implementation looked up .time_cards/<parsed name>/profile.json,
        // which broke the instant the name changed. The new implementation
        // reads displayName straight off the employees.json record by pin,
        // so a rename has zero effect on it.
        val baseDir = tmpFolder.newFolder("base")
        val timeCardsDir = File(baseDir, ".time_cards")
        timeCardsDir.mkdirs()
        File(timeCardsDir, "employees.json").writeText(
            """[{"id":"901","name":"Kevin Olsen","displayName":"KO","excluded":false}]"""
        )
        // Deliberately no "Kevin Olsen" directory on disk at all -- proves this
        // no longer depends on a matching name-keyed folder existing.

        val result = TimecardStore.getCustomDisplayNameForTest(baseDir, "901")

        assertEquals("KO", result)
    }
}
