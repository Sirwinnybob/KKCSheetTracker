package com.kkc.sheettracker.viewer3d

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kkc.sheettracker.data.ViewerInteractionSignal
import com.kkc.sheettracker.logging.AppLog
import com.kkc.sheettracker.ui.components.WebViewBlurGate

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Model3DPane(
    modifier: Modifier = Modifier,
    folderName: String,
    roomName: String?,
    modelAvailable: Boolean,
    serverPort: Int,
    serverError: String?,
    isDarkTheme: Boolean,
    onFullScreen: (() -> Unit)? = null,
    onOpenIn3DApp: (() -> Unit)? = null,
    /** Called when the room is switched inside the web viewer (no reload happens). */
    onRoomSelected: ((String) -> Unit)? = null,
    headerSlot: @Composable RowScope.() -> Unit
) {
    val latestOnRoomSelected by androidx.compose.runtime.rememberUpdatedState(onRoomSelected)
    // Room selector is drawn natively (see KKCVerticalSlidingPillColumn); the page reports its rooms.
    var rooms by remember(folderName, roomName) { mutableStateOf<List<String>>(emptyList()) }
    var selectedRoom by remember(folderName, roomName) { mutableStateOf<String?>(null) }
    var webViewRef by remember(folderName, roomName) { mutableStateOf<WebView?>(null) }
    var pendingRoomSwitch by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val roomScope = androidx.compose.runtime.rememberCoroutineScope()
    // Same track/pill colors as the native sliding selectors, handed to the page as AARRGGBB hex
    // so its room list can be themed to match (see viewer.html #room-list-ui / #room-pill).
    val pillStyle = com.kkc.sheettracker.ui.components.rememberKKCPillStyle()
    fun androidx.compose.ui.graphics.Color.hex(): String =
        "%08X".format(this.toArgb().toLong() and 0xFFFFFFFFL)
    val themeParams = "&t=${pillStyle.container.hex()}&tb=${pillStyle.containerBorder.hex()}" +
        "&tt=${pillStyle.unselectedText.hex()}&p=${pillStyle.fillColor.hex()}" +
        "&pb=${pillStyle.border.hex()}&pt=${pillStyle.selectedText.hex()}"
    val encodedUrl = if (modelAvailable && serverPort > 0 && roomName != null) {
        val encodedJob  = java.net.URLEncoder.encode(folderName, "UTF-8")
        val encodedRoom = java.net.URLEncoder.encode(roomName, "UTF-8")
        val darkParam   = if (isDarkTheme) "1" else "0"
        "http://127.0.0.1:$serverPort/viewer.html?job=$encodedJob&room=$encodedRoom&dark=$darkParam$themeParams"
    } else ""
    val paneId = remember(encodedUrl) {
        "pane_${encodedUrl.hashCode()}"
    }
    DisposableEffect(paneId) {
        onDispose {
            ViewerInteractionSignal.setPaneInteracting(paneId, false)
            ViewerInteractionSignal.setPaneActive(paneId, false)
        }
    }
    // Frosted chrome blurring a live WebView keeps Chromium's compositor redrawing every vsync
    // (~120% CPU while idle), so the navbar goes solid for as long as the WebView is on screen.
    if (encodedUrl.isNotEmpty()) {
        DisposableEffect(Unit) {
            val release = WebViewBlurGate.shared.acquire()
            onDispose { release() }
        }
    }
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                headerSlot()
                if (modelAvailable && roomName != null && onOpenIn3DApp != null) {
                    TextButton(
                        onClick = onOpenIn3DApp,
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        Text("Open in 3D APP")
                    }
                }
                if (modelAvailable && roomName != null && onFullScreen != null) {
                    IconButton(
                        onClick = onFullScreen,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Full screen 3D",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (encodedUrl.isNotEmpty()) {
            key(encodedUrl) {
              Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            addJavascriptInterface(
                                Viewer3DBridge(
                                    paneId,
                                    onRoomSelected = { room -> latestOnRoomSelected?.invoke(room) },
                                    onRooms = { list, current ->
                                        rooms = list
                                        selectedRoom = current
                                    }
                                ),
                                "KKCViewerBridge"
                            )
                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    val message = "${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]"
                                    when (msg.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> AppLog.e("Viewer3D_JS", message)
                                        ConsoleMessage.MessageLevel.WARNING -> AppLog.w("Viewer3D_JS", message)
                                        else -> AppLog.d("Viewer3D_JS", message)
                                    }
                                    return true
                                }
                            }
                            loadUrl(encodedUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        ViewerInteractionSignal.setPaneInteracting(paneId, false)
                        ViewerInteractionSignal.setPaneActive(paneId, false)
                        it.destroy()
                    }
                )
                if (rooms.size > 1) {
                    com.kkc.sheettracker.ui.components.KKCVerticalSlidingPillColumn(
                        options = rooms.map { room ->
                            com.kkc.sheettracker.ui.components.KKCPillOption(
                                label = room,
                                isSelected = room == selectedRoom,
                                onClick = {
                                    if (room != selectedRoom) {
                                        selectedRoom = room
                                        // Slide first; the page swap and the app notification (which
                                        // recomposes the screen) start once the pill has landed.
                                        pendingRoomSwitch?.cancel()
                                        pendingRoomSwitch = roomScope.launch {
                                            kotlinx.coroutines.delay(420)
                                            webViewRef?.evaluateJavascript(
                                                "window.kkcSelectRoom && window.kkcSelectRoom(${org.json.JSONObject.quote(room)})",
                                                null
                                            )
                                            latestOnRoomSelected?.invoke(room)
                                        }
                                    }
                                }
                            )
                        },
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    )
                }
              }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (!modelAvailable) "No 3D model is available for this job"
                    else if (serverPort == 0 && !serverError.isNullOrBlank()) "3D viewer server failed: $serverError"
                    else if (serverPort == 0) "Starting 3D viewer…"
                    else "Search a cabinet to load its 3D room",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

private class Viewer3DBridge(
    private val paneId: String,
    private val onRoomSelected: (String) -> Unit,
    private val onRooms: (List<String>, String) -> Unit
) {
    /** The page hands over its room list (JSON array) and the room it opened on. */
    @JavascriptInterface
    fun setRooms(roomsJson: String, current: String) {
        val list = runCatching {
            val arr = org.json.JSONArray(roomsJson)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRooms(list, current) }
    }

    @JavascriptInterface
    fun onRoomSelected(room: String) {
        // JavascriptInterface calls arrive on a background thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRoomSelected.invoke(room) }
    }

    @JavascriptInterface
    fun setViewerActive(active: Boolean) {
        ViewerInteractionSignal.setPaneActive(paneId, active)
    }

    @JavascriptInterface
    fun setViewerInteracting(interacting: Boolean) {
        ViewerInteractionSignal.setPaneInteracting(paneId, interacting)
    }
}
