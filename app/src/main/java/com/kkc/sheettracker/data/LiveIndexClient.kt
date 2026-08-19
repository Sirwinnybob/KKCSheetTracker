package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.CacheIndexRoot
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
import java.util.concurrent.atomic.AtomicInteger

internal fun nextBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class LiveIndexDeltaWire(
    val type: String? = null,
    val folderName: String? = null,
    val revision: Long? = null,
    val index: CacheIndexRoot? = null
)

internal data class LiveIndexEnvelope(
    val type: String? = null,
    val serverInstanceId: String? = null,
    val revision: Long? = null,
    val jobs: Map<String, CacheIndexRoot>? = null,
    val delta: LiveIndexDeltaWire? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's `/api/ready-jobs-worker/live-index` read-only WebSocket
 * (see `docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md`).
 * Any server-sent `snapshot` frame is always treated as a full replace, whatever the
 * reason it arrived (initial connect, revision-gap resync, or server restart) — the
 * server already resolves gap detection on its side.
 */
class LiveIndexClient(
    private val config: AdminSyncConfig,
    private val tabletId: String,
    private val onSnapshot: (jobs: Map<String, CacheIndexRoot>) -> Unit,
    private val onDelta: (folderName: String, index: CacheIndexRoot?) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextBackoffDelayMs,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private val attempt = AtomicInteger(0)
    private var pendingJob: Job? = null

    companion object {
        private const val TAG = "LiveIndexClient"
        private val gson = Gson()
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun start() {
        if (running) return
        running = true
        attempt.set(0)
        connectNow()
    }

    fun stop() {
        running = false
        pendingJob?.cancel()
        pendingJob = null
        socket?.close(1000, "client stop")
        socket = null
    }

    private fun connectNow() {
        pendingJob = scope.launch {
            val baseUrl = buildAdminSyncUrl(config.getManualIp())
            if (baseUrl == null) {
                scheduleReconnect()
                return@launch
            }
            val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/ready-jobs-worker/live-index"
            val request = Request.Builder().url(wsUrl).build()
            socket = webSocketFactory(request, Listener())
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        val delayMs = reconnectDelayMs(attempt.getAndIncrement())
        pendingJob = scope.launch {
            delay(delayMs)
            if (running) connectNow()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(gson.toJson(mapOf("type" to "hello", "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = runCatching { gson.fromJson(text, LiveIndexEnvelope::class.java) }.getOrNull() ?: return
            when (envelope.type) {
                "snapshot" -> {
                    attempt.set(0)
                    onSnapshot(envelope.jobs.orEmpty())
                    onConnectionState(true)
                }
                "delta" -> {
                    val delta = envelope.delta ?: return
                    val folderName = delta.folderName ?: return
                    onDelta(folderName, if (delta.type == "remove") null else delta.index)
                }
                "not_running", "error" -> onConnectionState(false)
                else -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnectionState(false)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Live index socket failure", t)
            onConnectionState(false)
            scheduleReconnect()
        }
    }
}
