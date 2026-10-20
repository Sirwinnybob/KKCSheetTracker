package com.kkc.sheettracker.viewer3d

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.kkc.sheettracker.data.cvjob.CV_MATERIAL_TEXTURES_RELATIVE_PATH
import com.kkc.sheettracker.data.cvjob.CvWingGeometry
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.loadCvMaterialTextures
import com.kkc.sheettracker.logging.AppLog
import com.kkc.sheettracker.data.ViewerInteractionSignal
import com.kkc.sheettracker.ui.components.WebViewBlurGate
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val ANDROID_15_SDK = 35

/** MSAA sample count for the wing viewer; tablets older than Android 15 get the cheaper 2x. */
internal fun wingMsaaSamples(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt < ANDROID_15_SDK) 2 else 4

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
    val sessionSlot = remember {
        WingServerSessionSlot<WingServerSession> { it.server.stop() }
    }
    DisposableEffect(Unit) { onDispose { sessionSlot.dispose() } }
    LaunchedEffect(scene, basePath) {
        sessionSlot.dispose()
        val prepared = scene ?: return@LaunchedEffect
        val next = withContext(Dispatchers.IO) {
            ViewerServer(context, File(basePath)).let { server ->
                server.installWingPayload(Gson().toJson(prepared.payload), prepared.textureFiles)
                WingServerSession(server, server.startWithRetry())
            }
        }
        sessionSlot.replace(next)
    }
    val session = sessionSlot.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                WingModel3DPane(port)
            } else {
                Text(result?.error ?: "Starting 3D viewer…")
            }
        }
    }
}

private data class WingServerSession(val server: ViewerServer, val result: ViewerServer.StartResult)
private data class WingPreparedScene(val payload: WingScenePayload, val textureFiles: List<File>)

/** Material images are one shared cache at the Ready Jobs root, never per individual job. */
internal fun wingMaterialTextureRoot(basePath: String): File =
    File(basePath, CV_MATERIAL_TEXTURES_RELATIVE_PATH)

private suspend fun prepareWingScene(
    cacheDir: File,
    basePath: String,
    jobFolderName: String,
    wing: CvWingGeometry,
    doorsById: Map<String, DoorPreviewData>
): WingPreparedScene {
    val library = loadCvMaterialTextures(wingMaterialTextureRoot(basePath))
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
private fun WingModel3DPane(port: Int) {
    val isDarkTheme = LocalKKCIsDarkTheme.current
    val darkParam = if (isDarkTheme) 1 else 0
    val paneId = remember(port) { "wing_$port" }
    DisposableEffect(port) {
        val releaseBlurGate = WebViewBlurGate.shared.acquire()
        onDispose {
            ViewerInteractionSignal.setPaneInteracting(paneId, false)
            ViewerInteractionSignal.setPaneActive(paneId, false)
            releaseBlurGate()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).also { view ->
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.addJavascriptInterface(Viewer3DBridge(paneId), "KKCViewerBridge")
                view.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            AppLog.e("WingViewer", "WebView failed to load ${request.url}: ${error.errorCode} ${error.description}")
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request.isForMainFrame || errorResponse.statusCode >= 400) {
                            AppLog.e("WingViewer", "WebView HTTP ${errorResponse.statusCode} for ${request.url}")
                        }
                    }
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.e("WingViewer", "JS ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                        }
                        return super.onConsoleMessage(message)
                    }
                }
                view.loadUrl("http://127.0.0.1:$port/viewer.html?wing=1&dark=$darkParam&msaa=${wingMsaaSamples()}")
            }
        },
        onRelease = { it.destroy() }
    )
}
