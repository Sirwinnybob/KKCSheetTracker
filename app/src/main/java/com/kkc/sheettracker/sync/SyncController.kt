package com.kkc.sheettracker.sync

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface SyncController {
    suspend fun isServiceRunning(): Boolean
    fun startService()
    fun stopService()
}

data class SyncthingIntentConfig(
    val packageName: String = "com.nutomic.syncthingandroid",
    val startAction: String = "com.nutomic.syncthingandroid.action.START",
    val stopAction: String = "com.nutomic.syncthingandroid.action.STOP",
    val includeStoppedPackages: Boolean = true
)

data class SyncthingEndpointConfig(
    val baseUrl: String = "http://127.0.0.1:8384",
    val pingPath: String = "/rest/system/ping"
)

data class SyncthingNetworkConfig(
    val timeoutMs: Int = 2_000,
    val allowInsecureLocalTls: Boolean = true
)

data class SyncthingWatchdogConfig(
    val intervalMs: Long = 3_600_000L,
    val autoStartOnFailure: Boolean = true,
    val restartVerificationDelayMs: Long = 2_000L,
    val failureConfirmationWindowMs: Long = 30_000L
)

data class SyncthingRuntimeConfig(
    val intents: SyncthingIntentConfig = SyncthingIntentConfig(),
    val endpoint: SyncthingEndpointConfig = SyncthingEndpointConfig(),
    val network: SyncthingNetworkConfig = SyncthingNetworkConfig(),
    val watchdog: SyncthingWatchdogConfig = SyncthingWatchdogConfig(),
    val apiKey: String = ""
)

fun interface SyncthingConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

object DefaultSyncthingConnectionFactory : SyncthingConnectionFactory {
    override fun open(url: URL): HttpURLConnection {
        return url.openConnection() as HttpURLConnection
    }
}

interface SyncthingCommandSender {
    fun sendCommand(action: String, config: SyncthingIntentConfig)
}

class AndroidBroadcastSyncthingCommandSender(
    private val context: Context
) : SyncthingCommandSender {
    override fun sendCommand(action: String, config: SyncthingIntentConfig) {
        val intent = Intent(action).apply {
            setPackage(config.packageName)
            if (config.includeStoppedPackages) {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
        }
        context.sendBroadcast(intent)
    }
}

class SyncthingManager(
    context: Context?,
    private val config: SyncthingRuntimeConfig,
    private val connectionFactory: SyncthingConnectionFactory = DefaultSyncthingConnectionFactory,
    private val commandSender: SyncthingCommandSender = AndroidBroadcastSyncthingCommandSender(
        requireNotNull(context) { "Context is required when using default Syncthing command sender." }
    )
) : SyncController {

    override suspend fun isServiceRunning(): Boolean = withContext(Dispatchers.IO) {
        try {
            val pingUrl = buildPingUrl()
            val connection = connectionFactory.open(pingUrl)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.network.timeoutMs
                connection.readTimeout = config.network.timeoutMs
                val apiKey = config.apiKey.trim()
                if (apiKey.isNotEmpty()) {
                    connection.setRequestProperty("X-API-Key", apiKey)
                }
                val responseCode = connection.responseCode
                when {
                    responseCode == HttpURLConnection.HTTP_OK -> true
                    isLocalTlsRedirect(responseCode, connection) -> {
                        pingViaLocalTls(
                            location = connection.getHeaderField("Location"),
                            apiKey = apiKey
                        )
                    }
                    else -> false
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun startService() {
        commandSender.sendCommand(config.intents.startAction, config.intents)
    }

    override fun stopService() {
        commandSender.sendCommand(config.intents.stopAction, config.intents)
    }

    private fun buildPingUrl(): URL {
        val base = config.endpoint.baseUrl.trimEnd('/')
        val path = config.endpoint.pingPath.trimStart('/')
        return URL("$base/$path")
    }

    private fun isLocalTlsRedirect(
        responseCode: Int,
        connection: HttpURLConnection
    ): Boolean {
        if (responseCode != 307) return false
        val location = connection.getHeaderField("Location") ?: return false
        return location.startsWith("https://127.0.0.1:8384/")
    }

    private fun pingViaLocalTls(
        location: String?,
        apiKey: String
    ): Boolean {
        if (!config.network.allowInsecureLocalTls || location.isNullOrBlank()) return false
        val httpsUrl = URL(location)
        val connection = connectionFactory.open(httpsUrl)
        return try {
            if (connection is HttpsURLConnection) {
                configureInsecureLoopbackTls(connection)
            }
            connection.connectTimeout = config.network.timeoutMs
            connection.readTimeout = config.network.timeoutMs
            if (apiKey.isNotEmpty()) {
                connection.setRequestProperty("X-API-Key", apiKey)
            }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    }

    private fun configureInsecureLoopbackTls(connection: HttpsURLConnection) {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        connection.sslSocketFactory = sslContext.socketFactory
        connection.hostnameVerifier = HostnameVerifier { host, _ -> host == "127.0.0.1" }
    }
}
