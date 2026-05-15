package com.kkc.updateragent.update

import java.time.LocalTime

object MaintenanceWindowEvaluator {
    fun isOpen(window: MaintenanceWindow?, now: LocalTime = LocalTime.now()): Boolean {
        if (window == null) return true
        val start = LocalTime.of(window.startHourLocal.coerceIn(0, 23), 0)
        val end = LocalTime.of(window.endHourLocal.coerceIn(0, 23), 0)
        if (start == end) return true
        return if (end.isAfter(start)) {
            !now.isBefore(start) && now.isBefore(end)
        } else {
            !now.isBefore(start) || now.isBefore(end)
        }
    }
}
