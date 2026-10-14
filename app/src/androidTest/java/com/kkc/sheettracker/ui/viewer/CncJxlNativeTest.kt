package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Decode real desktop-produced JXLs through the app's native image loader. */
@RunWith(AndroidJUnit4::class)
class CncJxlNativeTest {
    private fun withImages(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "jxl-native-test-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun copyAsset(name: String, target: File) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    @Test fun losslessAndLossySheetDrawingsDecodeWithoutAnyPdfFile() = withImages { directory ->
        val pdf = File(directory, "missing.pdf")
        for (asset in listOf("cnc-lossless.jxl", "cnc-lossy.jxl")) {
            val image = File(directory, "drawing.jxl")
            copyAsset(asset, image)
            val prepared = loadCncSidecarDiagram(pdf, "drawing.png", 1600, "drawing.jxl")
            assertNotNull(prepared)
            val bitmap = prepared!!.bitmap
            assertEquals(800, bitmap.width)
            assertEquals(400, bitmap.height)
            assertEquals(Bitmap.Config.RGB_565, bitmap.config)
            assertEquals(0.5f, prepared.sourceScale, 0.0001f)
            assertTrue(Color.red(bitmap.getPixel(20, 20)) > 240)
            assertTrue(Color.red(bitmap.getPixel(100, 200)) < 50)
            bitmap.recycle()
        }
        assertFalse(pdf.exists())
    }

    @Test fun thumbnailsDecodeAtDisplaySizeWithoutOpeningThePdf() = withImages { directory ->
        val pdf = File(directory, "missing.pdf")
        copyAsset("cnc-lossless.jxl", File(directory, "thumbnail.jxl"))
        val bitmap = loadCncSidecarBitmap(pdf, "thumbnail.png", "thumbnail.jxl", 420, 280)
        assertNotNull(bitmap)
        assertEquals(420, bitmap!!.width)
        assertEquals(210, bitmap.height)
        bitmap.recycle()
        assertFalse(pdf.exists())
    }

    @Test fun corruptJxlFallsBackToLegacyPngOnDevice() = withImages { directory ->
        val pdf = File(directory, "missing.pdf")
        val legacy = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        File(directory, "drawing.png").outputStream().use { assertTrue(legacy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        legacy.recycle()
        File(directory, "drawing.jxl").writeBytes(byteArrayOf(0xff.toByte(), 0x0a, 1, 2, 3))
        val prepared = loadCncSidecarDiagram(pdf, "drawing.png", 80)
        assertNotNull(prepared)
        assertEquals(80, prepared!!.bitmap.width)
        assertTrue(Color.red(prepared.bitmap.getPixel(20, 20)) > 240)
        prepared.bitmap.recycle()
        assertFalse(pdf.exists())
    }
}
