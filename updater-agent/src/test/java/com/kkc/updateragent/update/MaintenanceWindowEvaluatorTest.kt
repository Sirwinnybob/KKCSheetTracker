package com.kkc.updateragent.update

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceWindowEvaluatorTest {
    @Test
    fun openWhenNoWindowConfigured() {
        assertTrue(MaintenanceWindowEvaluator.isOpen(null, LocalTime.of(12, 0)))
    }

    @Test
    fun openWithinSameDayWindow() {
        val window = MaintenanceWindow(startHourLocal = 9, endHourLocal = 17)
        assertTrue(MaintenanceWindowEvaluator.isOpen(window, LocalTime.of(10, 0)))
        assertFalse(MaintenanceWindowEvaluator.isOpen(window, LocalTime.of(18, 0)))
    }

    @Test
    fun openAcrossMidnightWindow() {
        val window = MaintenanceWindow(startHourLocal = 22, endHourLocal = 4)
        assertTrue(MaintenanceWindowEvaluator.isOpen(window, LocalTime.of(23, 0)))
        assertTrue(MaintenanceWindowEvaluator.isOpen(window, LocalTime.of(2, 0)))
        assertFalse(MaintenanceWindowEvaluator.isOpen(window, LocalTime.of(12, 0)))
    }
}
