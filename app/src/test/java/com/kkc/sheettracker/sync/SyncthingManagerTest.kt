package com.kkc.sheettracker.sync

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncthingManagerTest {

    @Test
    fun `isServiceRunning returns true on 200`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(apiKey = "abc123"),
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_OK)
            },
            commandSender = NoOpCommandSender()
        )

        assertTrue(manager.isServiceRunning())
    }

    @Test
    fun `isServiceRunning returns false on non-200`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(),
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_INTERNAL_ERROR)
            },
            commandSender = NoOpCommandSender()
        )

        assertFalse(manager.isServiceRunning())
    }

    @Test
    fun `isServiceRunning returns false when connection fails`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(),
            connectionFactory = SyncthingConnectionFactory {
                throw ConnectException("refused")
            },
            commandSender = NoOpCommandSender()
        )

        assertFalse(manager.isServiceRunning())
    }

    @Test
    fun `startService broadcast uses package and include-stopped flag`() {
        val recorder = RecordingCommandSender()
        val config = SyncthingRuntimeConfig()
        val manager = SyncthingManager(
            context = null,
            config = config,
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_OK)
            },
            commandSender = recorder
        )

        manager.startService()

        assertEquals(config.intents.startAction, recorder.lastAction)
        assertEquals(config.intents.packageName, recorder.lastPackage)
        assertTrue(recorder.includeStopped)
    }

    @Test
    fun `isServiceRunning sends api key header when provided`() = runBlocking {
        val connection = FakeHttpURLConnection(URL("http://127.0.0.1"), HttpURLConnection.HTTP_OK)
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(apiKey = "header-key"),
            connectionFactory = SyncthingConnectionFactory { connection },
            commandSender = NoOpCommandSender()
        )

        manager.isServiceRunning()

        assertEquals("header-key", connection.requestedApiKey)
    }

    @Test
    fun `isServiceRunning follows local tls redirect and succeeds`() = runBlocking {
        val redirectConnection = FakeHttpURLConnection(
            URL("http://127.0.0.1:8384/rest/system/ping"),
            307
        ).apply {
            headers["Location"] = "https://127.0.0.1:8384/rest/system/ping"
        }
        val httpsConnection = FakeHttpsURLConnection(
            URL("https://127.0.0.1:8384/rest/system/ping"),
            HttpURLConnection.HTTP_OK
        )

        var call = 0
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(apiKey = "redirect-key"),
            connectionFactory = SyncthingConnectionFactory {
                call += 1
                if (call == 1) redirectConnection else httpsConnection
            },
            commandSender = NoOpCommandSender()
        )

        assertTrue(manager.isServiceRunning())
        assertEquals("redirect-key", httpsConnection.requestedApiKey)
    }

    @Test
    fun `pauseSync returns true on 200`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(apiKey = "abc123"),
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_OK)
            },
            commandSender = NoOpCommandSender()
        )

        assertTrue(manager.pauseSync())
    }

    @Test
    fun `resumeSync returns false on non-200`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(),
            connectionFactory = SyncthingConnectionFactory {
                FakeHttpURLConnection(it, HttpURLConnection.HTTP_INTERNAL_ERROR)
            },
            commandSender = NoOpCommandSender()
        )

        assertFalse(manager.resumeSync())
    }

    @Test
    fun `pauseSync returns false when connection fails`() = runBlocking {
        val manager = SyncthingManager(
            context = null,
            config = SyncthingRuntimeConfig(),
            connectionFactory = SyncthingConnectionFactory {
                throw ConnectException("refused")
            },
            commandSender = NoOpCommandSender()
        )

        assertFalse(manager.pauseSync())
    }
}

private class NoOpCommandSender : SyncthingCommandSender {
    override fun sendCommand(action: String, config: SyncthingIntentConfig) = Unit
}

private class RecordingCommandSender : SyncthingCommandSender {
    var lastAction: String? = null
    var lastPackage: String? = null
    var includeStopped: Boolean = false

    override fun sendCommand(action: String, config: SyncthingIntentConfig) {
        lastAction = action
        lastPackage = config.packageName
        includeStopped = config.includeStoppedPackages
    }
}

private class FakeHttpURLConnection(
    url: URL,
    private val code: Int
) : HttpURLConnection(url) {
    var requestedApiKey: String? = null
    val headers: MutableMap<String, String> = mutableMapOf()

    override fun setRequestProperty(key: String?, value: String?) {
        if (key == "X-API-Key") {
            requestedApiKey = value
        }
    }

    override fun getResponseCode(): Int = code
    override fun getHeaderField(name: String?): String? = headers[name]

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}

private class FakeHttpsURLConnection(
    url: URL,
    private val code: Int
) : HttpsURLConnection(url) {
    var requestedApiKey: String? = null

    override fun setRequestProperty(key: String?, value: String?) {
        if (key == "X-API-Key") {
            requestedApiKey = value
        }
    }

    override fun getResponseCode(): Int = code
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getCipherSuite(): String = "TLS_FAKE"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}
