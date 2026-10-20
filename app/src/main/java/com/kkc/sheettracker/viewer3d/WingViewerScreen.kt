package com.kkc.sheettracker.viewer3d

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.kkc.sheettracker.data.cvjob.CV_MATERIAL_TEXTURES_RELATIVE_PATH
import com.kkc.sheettracker.data.cvjob.CvWingGeometry
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.loadCvMaterialTextures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WingViewerScreen(
    basePath: String,
    jobFolderName: String,
    wing: CvWingGeometry,
    doorsById: Map<String, DoorPreviewData>,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val scene by produceState<WingPreparedScene?>(null, basePath, jobFolderName, wing, doorsById) {
        value = withContext(Dispatchers.IO) {
            prepareWingScene(context.cacheDir, basePath, jobFolderName, wing, doorsById)
        }
    }
    val session by produceState<WingServerSession?>(null, basePath, scene) {
        val prepared = scene ?: return@produceState
        value = withContext(Dispatchers.IO) {
            ViewerServer(context, File(basePath)).let { server ->
                server.installWingPayload(Gson().toJson(prepared.payload), prepared.textureFiles)
                WingServerSession(server, server.startWithRetry())
            }
        }
    }
    DisposableEffect(session) { onDispose { session?.server?.stop() } }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("3D · Cabinet ${wing.cabinet}") },
                navigationIcon = { IconButton(onClick = onDismiss) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val result = session?.result
            if (result?.port ?: 0 > 0) {
                val port = requireNotNull(result).port
                var webView by remember(port) { mutableStateOf<WebView?>(null) }
                WingModel3DPane(port, onCreated = { webView = it })
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { webView?.evaluateJavascript("window.wingViewer?.fit()", null) }) { Text("Fit") }
                    Button(onClick = { webView?.evaluateJavascript("window.wingViewer?.reset()", null) }) { Text("Reset") }
                    Button(onClick = { webView?.evaluateJavascript("window.wingViewer?.toggleCapInspection()", null) }) { Text("Caps") }
                }
            } else {
                Text(result?.error ?: "Starting 3D viewer…")
            }
        }
    }
}

private data class WingServerSession(val server: ViewerServer, val result: ViewerServer.StartResult)
private data class WingPreparedScene(val payload: WingScenePayload, val textureFiles: List<File>)

private suspend fun prepareWingScene(
    cacheDir: File,
    basePath: String,
    jobFolderName: String,
    wing: CvWingGeometry,
    doorsById: Map<String, DoorPreviewData>
): WingPreparedScene {
    val library = loadCvMaterialTextures(File(basePath, "$jobFolderName/$CV_MATERIAL_TEXTURES_RELATIVE_PATH"))
    val textureDir = File(cacheDir, "wing-textures")
    val source = buildWingScenePayload(wing, doorsById)
    val files = linkedSetOf<File>()
    val textured = source.parts.map { part ->
        val descriptor = WingTextureBridge.descriptorFor(part, doorsById, library) ?: return@map part
        val file = WingTextureBridge.materialize(textureDir, descriptor) ?: return@map part
        files += file
        part.copy(texture = WingSceneTexture(
            url = "/wing-textures/${file.name}",
            worldWidthInches = descriptor.worldWidthInches,
            worldHeightInches = descriptor.worldHeightInches,
            tile = descriptor.tile,
            grainAxis = part.material?.grainAxis
        ))
    }
    return WingPreparedScene(source.copy(parts = textured), files.toList())
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WingModel3DPane(port: Int, onCreated: (WebView) -> Unit) {
    var webView by remember(port) { mutableStateOf<WebView?>(null) }
    DisposableEffect(port) { onDispose { webView?.destroy() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).also { view ->
                webView = view
                onCreated(view)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.webViewClient = WebViewClient()
                view.webChromeClient = WebChromeClient()
                view.loadUrl("http://127.0.0.1:$port/viewer.html?wing=1")
            }
        },
        onRelease = { it.destroy() }
    )
}
