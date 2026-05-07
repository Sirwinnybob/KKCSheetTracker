package com.kkc.sheettracker.viewer3d

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Model3DPane(
    modifier: Modifier = Modifier,
    folderName: String,
    roomName: String?,
    serverPort: Int,
    isDarkTheme: Boolean,
    onFullScreen: () -> Unit,
    headerSlot: @Composable RowScope.() -> Unit
) {
    val encodedUrl = if (serverPort > 0 && roomName != null) {
        val encodedJob  = java.net.URLEncoder.encode(folderName, "UTF-8")
        val encodedRoom = java.net.URLEncoder.encode(roomName, "UTF-8")
        val darkParam   = if (isDarkTheme) "1" else "0"
        "http://127.0.0.1:$serverPort/viewer.html?job=$encodedJob&room=$encodedRoom&dark=$darkParam"
    } else ""

    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                headerSlot()
                if (roomName != null) {
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
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    val level = when (msg.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                                        ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                                        else -> Log.DEBUG
                                    }
                                    Log.println(level, "Viewer3D_JS", "${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]")
                                    return true
                                }
                            }
                            loadUrl(encodedUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.destroy() }
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (serverPort == 0) "Starting 3D viewer…"
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
