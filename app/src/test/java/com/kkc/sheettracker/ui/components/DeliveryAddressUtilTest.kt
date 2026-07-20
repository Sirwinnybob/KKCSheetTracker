package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun parseDeliveryCoordinates_rejectsBlank() {
        assertNull(parseDeliveryCoordinates(""))
        assertNull(parseDeliveryCoordinates("   "))
    }
}
