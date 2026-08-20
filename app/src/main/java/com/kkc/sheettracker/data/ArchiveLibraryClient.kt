package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.ArchiveJobEntry
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

internal fun nextArchiveLibraryBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class ArchiveLibraryDeltaWire(
    val type: String? = null,
    val archiveJobId: String? = null,
    val revision: Long? = null,
    val entry: ArchiveJobEntry? = null
)

internal data class ArchiveLibraryEnvelope(
    val type: String? = null,
    val serverInstanceId: String? = null,
    val revision: Long? = null,
    val archives: Map<String, ArchiveJobEntry>? = null,
    val delta: ArchiveLibraryDeltaWire? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's `/api/ready-jobs-archive/library/live` read-only WebSocket.
 * Structural twin of [LiveIndexClient] for the archive-library read model: any server-sent
 * `snapshot` frame is always treated as a full replace, whatever the reason it arrived
 * (initial connect, revision-gap resync, or server restart) — the server already resolves
 * gap detection on its side.
 */
class ArchiveLibraryClient(
    private val config: AdminSyncConfig,
    private val tabletId: String,
    private val onSnapshot: (archives: Map<String, ArchiveJobEntry>) -> Unit,
    private val onDelta: (archiveJobId: String, entry: ArchiveJobEntry?) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextArchiveLibraryBackoffDelayMs,
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
        private const val TAG = "ArchiveLibraryClient"
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
                val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/ready-jobs-archive/library/live"
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

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened, sending hello")
            webSocket.send(gson.toJson(mapOf("type" to "hello", "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = runCatching { gson.fromJson(text, ArchiveLibraryEnvelope::class.java) }.getOrNull() ?: return
            when (envelope.type) {
                "snapshot" -> {
                    attempt.set(0)
                    Log.d(TAG, "Received snapshot: revision=${envelope.revision}, archives=${envelope.archives?.size ?: 0}")
                    onSnapshot(envelope.archives.orEmpty())
                    onConnectionState(true)
                }
                "delta" -> {
                    val delta = envelope.delta
                    if (delta == null || delta.archiveJobId == null) {
                        Log.w(TAG, "Dropped malformed delta: missing delta or archiveJobId")
                        return
                    }
                    onDelta(delta.archiveJobId, if (delta.type == "remove") null else delta.entry)
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
            Log.w(TAG, "Archive library socket failure", t)
            onConnectionState(false)
            scheduleReconnect()
        }
    }
}
