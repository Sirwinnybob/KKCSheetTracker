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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private val attempt = AtomicInteger(0)
    private val reconnectPending = AtomicBoolean(false)
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
        if (running) return
        running = true
        attempt.set(0)
        reconnectPending.set(false)
        connectNow()
    }

    fun stop() {
        running = false
        reconnectPending.set(false)
        pendingJob?.cancel()
        pendingJob = null
        socket?.close(1000, "client stop")
        socket = null
    }

    private fun connectNow() {
        pendingJob = scope.launch {
            try {
                val baseUrl = buildAdminSyncUrl(config.getManualIp())
                if (baseUrl == null) {
                    Log.d(TAG, "No server IP configured; skipping connect")
                    scheduleReconnect()
                    return@launch
                }
                val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/delivery-schedule/live"
                val request = Request.Builder().url(wsUrl).build()
                if (!running) return@launch
                socket = webSocketFactory(request, Listener())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "connectNow failed", e)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        if (!reconnectPending.compareAndSet(false, true)) {
            Log.d(TAG, "Reconnect already pending; ignoring duplicate schedule request")
            return
        }
        val currentAttempt = attempt.getAndIncrement()
        val delayMs = reconnectDelayMs(currentAttempt)
        Log.d(TAG, "Scheduling reconnect: attempt=$currentAttempt delayMs=$delayMs")
        pendingJob = scope.launch {
            delay(delayMs)
            reconnectPending.set(false)
            if (running) connectNow()
        }
    }

    private fun parseValidSchedule(schedule: JsonObject): DeliverySchedule? {
        if (schedule.get("slots")?.isJsonObject != true) return null
        return runCatching { parseDeliverySchedule(gson.toJson(schedule)) }.getOrNull()
    }

    private inner class Listener : WebSocketListener() {
        private var receivedInitialSnapshot = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened, sending hello")
            webSocket.send(gson.toJson(mapOf("type" to "hello", "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = runCatching {
                gson.fromJson(text, DeliveryScheduleEnvelope::class.java)
            }.getOrNull() ?: return

            when (envelope.type) {
                "snapshot", "schedule" -> {
                    val scheduleJson = envelope.schedule ?: return
                    val schedule = parseValidSchedule(scheduleJson) ?: return
                    Log.d(TAG, "Received ${envelope.type} frame: revision=${envelope.revision}")
                    onSchedule(schedule)
                    if (envelope.type == "snapshot") {
                        attempt.set(0)
                        receivedInitialSnapshot = true
                        onConnectionState(true)
                    } else if (!receivedInitialSnapshot) {
                        Log.d(TAG, "Received schedule update before initial snapshot")
                    }
                }
                "not_running", "error" -> onConnectionState(false)
                else -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
            onConnectionState(false)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Delivery schedule socket failure", t)
            onConnectionState(false)
            scheduleReconnect()
        }
    }
}
