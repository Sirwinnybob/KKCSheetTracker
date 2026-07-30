package com.kkc.sheettracker.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryAddressUtilTest {

    @Test
    fun parseDeliveryCoordinates_parsesStandardCommaSpaceFormat() {
        assertEquals(45.523 to -122.676, parseDeliveryCoordinates("45.523, -122.676"))
    }

    @Test
    fun parseDeliveryCoordinates_parsesWithoutSpace() {
        assertEquals(45.523 to -122.676, parseDeliveryCoordinates("45.523,-122.676"))
    }

    @Test
    fun parseDeliveryCoordinates_acceptsBoundaryValues() {
        assertEquals(90.0 to 180.0, parseDeliveryCoordinates("90, 180"))
        assertEquals(-90.0 to -180.0, parseDeliveryCoordinates("-90, -180"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsOutOfRangeLatitude() {
        assertNull(parseDeliveryCoordinates("91, 0"))
        assertNull(parseDeliveryCoordinates("-91, 0"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsOutOfRangeLongitude() {
        assertNull(parseDeliveryCoordinates("0, 181"))
        assertNull(parseDeliveryCoordinates("0, -181"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsPlainStreetAddress() {
        assertNull(parseDeliveryCoordinates("123 Main St, Springfield"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsPlusCode() {
        assertNull(parseDeliveryCoordinates("8FVC9G8V+5V"))
        assertNull(parseDeliveryCoordinates("8FVC9G8V+5V Portland, OR"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsWrongPartCount() {
        assertNull(parseDeliveryCoordinates("45.5,-122.6,10"))
        assertNull(parseDeliveryCoordinates("45.5"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsMixedValidAndInvalidParts() {
        assertNull(parseDeliveryCoordinates("45.5, abc"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsBlank() {
        assertNull(parseDeliveryCoordinates(""))
        assertNull(parseDeliveryCoordinates("   "))
    }

    @Test
    fun deliveryMapActions_useTheSafeLauncherAtEveryEntryPoint() {
        val workingDirectory = System.getProperty("user.dir") ?: error("Missing working directory")
        var projectRoot = File(workingDirectory)
        while (!File(projectRoot, "app/src/main").isDirectory) {
            projectRoot = projectRoot.parentFile
                ?: error("Unable to locate the project root from $workingDirectory")
        }
        val files = listOf(
            "app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt",
            "app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt"
        )

        files.forEach { relativePath ->
            val source = File(projectRoot, relativePath).readText()
            assertTrue(
                "$relativePath must use openDeliveryMapsSafely so a missing map app cannot crash KKC",
                source.contains("openDeliveryMapsSafely")
            )
        }
    }
}
