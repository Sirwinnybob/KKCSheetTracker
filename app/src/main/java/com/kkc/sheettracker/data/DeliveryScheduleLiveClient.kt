package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.DeliverySchedule
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

internal fun nextDeliveryScheduleBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class DeliveryScheduleEnvelope(
    val type: String? = null,
    val revision: Long? = null,
    val schedule: JsonObject? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's `/api/delivery-schedule/live` read-only WebSocket.
 * The socket carries complete schedule snapshots: the initial `snapshot` frame and
 * subsequent `schedule` replacement frames. A connection is considered live only
 * after a valid initial snapshot has been parsed and delivered.
 */
class DeliveryScheduleLiveClient(
    private val config: AdminSyncConfig,
    private val tabletId: String,
    private val onSchedule: (DeliverySchedule) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextDeliveryScheduleBackoffDelayMs,
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
        private const val TAG = "DeliveryScheduleLiveClient"
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
            val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/delivery-schedule/live"
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

    private fun parseValidSchedule(schedule: JsonObject): DeliverySchedule? {
        if (schedule.get("slots")?.isJsonObject != true) return null
        return runCatching { parseDeliverySchedule(gson.toJson(schedule)) }.getOrNull()
    }

    private inner class Listener(
        private val listenerGeneration: Long
    ) : WebSocketListener() {
        private var receivedInitialSnapshot = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.d(TAG, "WebSocket opened, sending hello")
            webSocket.send(gson.toJson(mapOf("type" to "hello", "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            val envelope = runCatching {
                gson.fromJson(text, DeliveryScheduleEnvelope::class.java)
            }.getOrNull() ?: return

            when (envelope.type) {
                "snapshot", "schedule" -> {
                    if (envelope.type == "schedule" && !receivedInitialSnapshot) {
                        Log.d(TAG, "Ignoring schedule update before initial snapshot")
                        return
                    }
                    val scheduleJson = envelope.schedule ?: return
                    val schedule = parseValidSchedule(scheduleJson) ?: return
                    if (!isCurrentSocket(listenerGeneration, webSocket)) return
                    Log.d(TAG, "Received ${envelope.type} frame: revision=${envelope.revision}")
                    onSchedule(schedule)
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
            Log.w(TAG, "Delivery schedule socket failure", t)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }
    }
}
