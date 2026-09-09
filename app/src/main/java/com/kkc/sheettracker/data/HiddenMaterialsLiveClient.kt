package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal fun nextHiddenMaterialsBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class HiddenMaterialsEnvelope(
    val type: String? = null,
    val revision: Long? = null,
    val hiddenMaterials: JsonObject? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's `/api/hidden-materials/live` read-only WebSocket for one mode
 * (Hardwoods or Specialty -- see the 2026-09-08 hidden-materials design doc's mode segregation;
 * a tablet screen showing both modes would run two independent instances of this client). The
 * socket carries complete document snapshots: the initial `snapshot` frame and subsequent
 * `hiddenMaterials` replacement frames. A connection is considered live only after a valid
 * initial snapshot has been parsed and delivered. Mirrors DeliveryScheduleLiveClient's
 * lifecycle/backoff/reconnect handling exactly -- see its kdoc for the full rationale.
 */
class HiddenMaterialsLiveClient(
    private val config: AdminSyncConfig,
    private val mode: HiddenMaterialsMode,
    private val tabletId: String,
    private val onDocument: (HiddenMaterialsDocument) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextHiddenMaterialsBackoffDelayMs,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var running = false
    private var generation = 0L
    private var socket: WebSocket? = null
    private var attempt = 0
    private var reconnectPending = false
    private var pendingJob: Job? = null

    companion object {
        private const val TAG = "HiddenMaterialsLiveClient"
        private val gson = Gson()
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        val startGeneration: Long
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            generation += 1
            startGeneration = generation
            attempt = 0
            reconnectPending = false
            pendingJob?.cancel()
            pendingJob = null
        }
        connectNow(startGeneration)
    }

    fun stop() {
        val socketToClose: WebSocket?
        synchronized(lifecycleLock) {
            running = false
            generation += 1
            reconnectPending = false
            pendingJob?.cancel()
            pendingJob = null
            socketToClose = socket
            socket = null
        }
        socketToClose?.close(1000, "client stop")
    }

    private fun connectNow(expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration)) return
            pendingJob = scope.launch {
                connectForGeneration(expectedGeneration)
            }
        }
    }

    private suspend fun connectForGeneration(expectedGeneration: Long) {
        try {
            val baseUrl = buildAdminSyncUrl(config.getManualIp())
            if (baseUrl == null) {
                Log.d(TAG, "No server IP configured; skipping connect")
                scheduleReconnect(expectedGeneration)
                return
            }
            val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/hidden-materials/live"
            val request = Request.Builder().url(wsUrl).build()
            val listener = Listener(expectedGeneration)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(expectedGeneration)) return
            }
            val newSocket = webSocketFactory(request, listener)
            val closeImmediately = synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(expectedGeneration)) {
                    true
                } else {
                    socket = newSocket
                    false
                }
            }
            if (closeImmediately) newSocket.close(1000, "stale client lifecycle")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "connectNow failed", e)
            scheduleReconnect(expectedGeneration)
        }
    }

    private fun scheduleReconnect(expectedGeneration: Long) {
        val currentAttempt: Int
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration)) return
            if (reconnectPending) {
                Log.d(TAG, "Reconnect already pending; ignoring duplicate schedule request")
                return
            }
            reconnectPending = true
            currentAttempt = attempt++
        }
        val delayMs = reconnectDelayMs(currentAttempt)
        Log.d(TAG, "Scheduling reconnect: attempt=$currentAttempt delayMs=$delayMs")
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration) || !reconnectPending) return
            pendingJob = scope.launch {
                delay(delayMs)
                val shouldConnect = synchronized(lifecycleLock) {
                    if (!isCurrentGenerationLocked(expectedGeneration) || !reconnectPending) {
                        false
                    } else {
                        reconnectPending = false
                        true
                    }
                }
                if (shouldConnect) connectNow(expectedGeneration)
            }
        }
    }

    private fun isCurrentGenerationLocked(expectedGeneration: Long): Boolean =
        running && generation == expectedGeneration

    private fun isCurrentSocket(expectedGeneration: Long, callbackSocket: WebSocket): Boolean =
        synchronized(lifecycleLock) {
            isCurrentGenerationLocked(expectedGeneration) && socket === callbackSocket
        }

    private fun parseValidDocument(hiddenMaterials: JsonObject): HiddenMaterialsDocument? {
        if (hiddenMaterials.get("global")?.isJsonObject != true) return null
        return runCatching { parseHiddenMaterialsDocument(gson.toJson(hiddenMaterials)) }.getOrNull()
    }

    private inner class Listener(
        private val listenerGeneration: Long
    ) : WebSocketListener() {
        private var receivedInitialSnapshot = false
        private var lastRevision: Long? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.d(TAG, "WebSocket opened, sending hello")
            webSocket.send(gson.toJson(mapOf("type" to "hello", "mode" to mode.name, "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            val envelope = runCatching {
                gson.fromJson(text, HiddenMaterialsEnvelope::class.java)
            }.getOrNull() ?: return

            when (envelope.type) {
                "snapshot", "hiddenMaterials" -> {
                    val revision = envelope.revision
                    if (revision == null || revision < 0L) {
                        Log.d(TAG, "Ignoring ${envelope.type} frame without a valid revision")
                        return
                    }
                    if (envelope.type == "snapshot" && receivedInitialSnapshot) {
                        Log.d(TAG, "Ignoring duplicate snapshot in an established session")
                        return
                    }
                    if (envelope.type == "hiddenMaterials" && !receivedInitialSnapshot) {
                        Log.d(TAG, "Ignoring update before initial snapshot")
                        return
                    }
                    if (envelope.type == "hiddenMaterials" && revision <= (lastRevision ?: -1L)) {
                        Log.d(TAG, "Ignoring stale revision=$revision lastRevision=$lastRevision")
                        return
                    }
                    val documentJson = envelope.hiddenMaterials ?: return
                    val document = parseValidDocument(documentJson) ?: return
                    if (!isCurrentSocket(listenerGeneration, webSocket)) return
                    Log.d(TAG, "Received ${envelope.type} frame: revision=$revision")
                    lastRevision = revision
                    onDocument(document)
                    if (envelope.type == "snapshot") {
                        synchronized(lifecycleLock) {
                            if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                            attempt = 0
                        }
                        receivedInitialSnapshot = true
                        onConnectionState(true)
                    }
                }
                "not_running", "error" -> {
                    if (isCurrentSocket(listenerGeneration, webSocket)) onConnectionState(false)
                }
                else -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.w(TAG, "Hidden materials socket failure", t)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }
    }
}
