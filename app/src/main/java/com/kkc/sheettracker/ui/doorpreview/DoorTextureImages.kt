package com.kkc.sheettracker.ui.doorpreview

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.kkc.sheettracker.data.cvjob.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

private val LocalDoorMaterialTextures = staticCompositionLocalOf { CvMaterialTextureLibrary.Empty }

/** Poll only while the door UI is composed, so late Syncthing arrivals recover without reopening. */
@Composable
internal fun DoorMaterialTextureProvider(basePath: String, content: @Composable () -> Unit) {
    val root = remember(basePath) { File(basePath, CV_MATERIAL_TEXTURES_RELATIVE_PATH) }
    var library by remember(root) { mutableStateOf(CvMaterialTextureLibrary.Empty) }
    LaunchedEffect(root) {
        if (basePath.isBlank()) return@LaunchedEffect
        var previous: Pair<Long, Long>? = null
        while (isActive) {
            withContext(Dispatchers.IO) {
                val manifest = File(root, "manifest.json")
                val version = manifest.lastModified() to manifest.length()
                if (version != previous) {
                    library = loadCvMaterialTextures(root)
                    previous = version
                }
            }
            delay(3_000)
        }
    }
    CompositionLocalProvider(LocalDoorMaterialTextures provides library, content = content)
}

internal data class DoorTextureImage(val bitmap: Bitmap, val texture: CvMaterialTexture)
internal typealias DoorTextureImages = Map<DoorMaterialRef, DoorTextureImage>

private val decodeMutex = Mutex()
private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/** Native decodes run serially off the UI thread, sampled to 1024px and cached by file version. */
internal suspend fun loadDoorTextureBitmap(texture: CvMaterialTexture): Bitmap? = decodeMutex.withLock {
    try {
        val file = texture.file
        val length = file.length()
        if (!file.isFile || length !in 1..32_000_000L) return@withLock null
        val modified = file.lastModified()
        val key = "${file.absolutePath}:$length:$modified:${texture.sha256}"
        bitmapCache.get(key)?.let { return@withLock it }
        val bytes = file.readBytes()
        texture.sha256?.let { expected ->
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) return@withLock null
        }
        val size = JxlCoder.getSize(bytes) ?: return@withLock null
        if (size.width <= 0 || size.height <= 0 || size.width.toLong() * size.height > 100_000_000L) return@withLock null
        val scale = minOf(1.0, 1024.0 / maxOf(size.width, size.height))
        val bitmap = JxlCoder.decodeSampled(bytes,
            (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1),
            preferredColorConfig = PreferredColorConfig.RGBA_8888)
        if (file.length() != length || file.lastModified() != modified) return@withLock null
        bitmapCache.put(key, bitmap)
        bitmap
    } catch (_: Exception) { null }
    catch (_: UnsatisfiedLinkError) { null }
}

@Composable
internal fun rememberDoorTextureImages(drawings: Collection<DoorDrawing>): DoorTextureImages {
    val library = LocalDoorMaterialTextures.current
    val materials = remember(drawings) { drawings.flatMap { it.surfaceMaterials.values + it.faceMaterial }.distinct() }
    var images by remember(library, materials) { mutableStateOf<DoorTextureImages>(emptyMap()) }
    LaunchedEffect(library, materials) {
        if (library.isEmpty) return@LaunchedEffect
        while (isActive) {
            images = withContext(Dispatchers.IO) {
                materials.mapNotNull { ref ->
                    val texture = library.faceTexture(ref.id, ref.name) ?: return@mapNotNull null
                    loadDoorTextureBitmap(texture)?.let { ref to DoorTextureImage(it, texture) }
                }.toMap()
            }
            delay(3_000)
        }
    }
    return images
}

/** No bitmap means pure white, without procedural grain, in both light and dark themes. */
internal fun DrawScope.drawDoorMaterial(path: Path, image: DoorTextureImage?, pixelsPerInch: Float, grain: Offset) {
    if (image == null) {
        drawPath(path, Color.White)
        return
    }
    val texture = image.texture
    val mode = if (texture.tile) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
    val shader = BitmapShader(image.bitmap, mode, mode)
    shader.setLocalMatrix(Matrix().apply {
        setValues(doorTextureMatrix(image.bitmap.width, image.bitmap.height, path.getBounds(), grain,
            pixelsPerInch, texture.worldWidthInches, texture.worldHeightInches, texture.tile))
    })
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
}
