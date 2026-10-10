package com.kkc.sheettracker.ui.jobs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleWiringTest {
    @Test
    fun jobsScreenRoutesDialogEditsThroughTheEditor() {
        val src = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(src.contains("DeliveryScheduleEditor("))
        assertTrue(src.contains("asDeliveryRemote()"))
        assertTrue(src.contains("overlayPendingDeliveryRequest("))
        assertTrue(src.contains("onPlaceJob = "))
        assertTrue(src.contains("onRemoveJob = "))
        assertTrue(src.contains("onResetWeek = "))
        assertFalse(src.contains("onQueueSlotEdit = "))
    }

    @Test
    fun bannerAndDialogUseShopZoneNotDeviceZone() {
        listOf("ui/components/DeliveryScheduleBanner.kt", "ui/components/DeliveryScheduleDialog.kt").forEach { path ->
            val src = source(path)
            assertTrue(path, src.contains("shopToday("))
            assertFalse(path, src.contains("LocalDate.now()"))
        }
    }

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Missing source $relativePath")
    }
}
