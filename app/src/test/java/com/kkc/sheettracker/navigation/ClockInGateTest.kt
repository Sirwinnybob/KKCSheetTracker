package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the legacy-nav-stack clock-in bug: LegacySingleStackNavigation used to
 * call clockInState.clockIn(...) directly without checking for a blank employee name, unlike
 * MultiBackStackNavigation which pends the clock-in behind the employee-login dialog first. Both
 * nav hosts now route onClockIn through the shared resolveClockInGate/formattedClockInJobName
 * helpers in NavGraph.kt, so this test pins the gating contract both hosts depend on.
 */
class ClockInGateTest {

    @Test
    fun `blank employee name is gated behind login instead of persisted immediately`() {
        val gate = resolveClockInGate(
            employeeName = "",
            jobNumber = "1234",
            jobName = "Smith Kitchen",
            folderName = "1234 Smith Kitchen",
            tabType = "cnc"
        )

        assertEquals(
            ClockInGateResult.NeedsLogin(
                PendingClockIn(
                    jobNumber = "1234",
                    jobName = "Smith Kitchen",
                    folderName = "1234 Smith Kitchen",
                    tabType = "cnc"
                )
            ),
            gate
        )
    }

    @Test
    fun `blank (whitespace-only) employee name is also gated`() {
        val gate = resolveClockInGate(
            employeeName = "   ",
            jobNumber = "1234",
            jobName = "Smith Kitchen",
            folderName = "1234 Smith Kitchen",
            tabType = "cnc"
        )

        assertEquals(true, gate is ClockInGateResult.NeedsLogin)
    }

    @Test
    fun `known employee name clocks in immediately without gating`() {
        val gate = resolveClockInGate(
            employeeName = "Jane Doe",
            jobNumber = "5678",
            jobName = "Jones Bath",
            folderName = "5678 Jones Bath",
            tabType = "hardwoods"
        )

        assertEquals(
            ClockInGateResult.Ready(
                jobNumber = "5678",
                jobName = "Jones Bath",
                folderName = "5678 Jones Bath",
                tabType = "hardwoods",
                employee = "Jane Doe"
            ),
            gate
        )
    }

    @Test
    fun `formattedClockInJobName appends the employee in parentheses`() {
        assertEquals("Jones Bath (Jane Doe)", formattedClockInJobName("Jones Bath", "Jane Doe"))
    }
}
