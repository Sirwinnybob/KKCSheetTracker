package com.kkc.sheettracker.ui.timecard

import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.kkc.sheettracker.data.TimecardBgConfig
import com.kkc.sheettracker.data.TimecardBgType
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import java.io.File

@Composable
fun TimeclockBackground(
    config: TimecardBgConfig,
    modifier: Modifier = Modifier
) {
    val lowEnd = LocalLowEndMode.current
    when (config.type) {
        TimecardBgType.NONE ->
            Box(modifier = modifier.background(MaterialTheme.colorScheme.background))

        TimecardBgType.COLOR ->
            Box(modifier = modifier.background(Color(config.color)))

        TimecardBgType.IMAGE -> {
            val path = config.mediaPath ?: return run {
                Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
            }
            val file = File(path)
            if (!file.exists()) return run {
                Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
            }
            val context = LocalContext.current
            val imageLoader = remember(context) {
                ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                        else add(GifDecoder.Factory())
                    }
                    .build()
            }
            AsyncImage(
                model = ImageRequest.Builder(context).data(file).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                imageLoader = imageLoader,
                modifier = modifier
            )
        }

        TimecardBgType.VIDEO -> {
            val path = config.mediaPath ?: return run {
                Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
            }
            val file = File(path)
            if (!file.exists()) return run {
                Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
            }
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                        }
                        start()
                    }
                },
                modifier = modifier
            )
        }
    }
}
