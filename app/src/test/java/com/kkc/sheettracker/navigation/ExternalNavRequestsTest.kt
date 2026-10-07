package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.ui.components.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalNavRequestsTest {

    @Before
    fun clear() {
        ExternalNavRequests.take()
    }

    @Test
    fun parsesEveryTopLevelRouteExceptHours() {
        NavDestination.entries.filter { it != NavDestination.HOURS }.forEach { dest ->
            assertEquals(ExternalNavTarget.Destination(dest), ExternalNavRequests.parse(dest.route))
        }
    }

    @Test
    fun hoursIsRejectedSoHoursTrackerCannotRelaunchItself() {
        assertNull(ExternalNavRequests.parse("hours"))
    }

    @Test
    fun calculatorParses() {
        assertEquals(ExternalNavTarget.Calculator, ExternalNavRequests.parse("calculator"))
    }

    @Test
    fun unknownBlankAndNullAreRejected() {
        assertNull(ExternalNavRequests.parse("job/123"))
        assertNull(ExternalNavRequests.parse(""))
        assertNull(ExternalNavRequests.parse(null))
    }

    @Test
    fun postedRequestIsRetainedUntilTakenOnce() {
        assertTrue(ExternalNavRequests.postFromRoute("supply"))
        // No consumer yet (cold start): the request must still be there.
        assertEquals(ExternalNavTarget.Destination(NavDestination.SUPPLY), ExternalNavRequests.pending.value)
        assertEquals(ExternalNavTarget.Destination(NavDestination.SUPPLY), ExternalNavRequests.take())
        assertNull(ExternalNavRequests.take())
        assertNull(ExternalNavRequests.pending.value)
    }

    @Test
    fun invalidRouteIsNotPosted() {
        assertFalse(ExternalNavRequests.postFromRoute("bogus"))
        assertNull(ExternalNavRequests.pending.value)
    }

    @Test
    fun destinationNotVisibleInCurrentWorkModeIsIgnored() {
        val visible = listOf(NavDestination.JOBS, NavDestination.HOURS, NavDestination.SUPPLY)
        assertNull(resolveExternalDestination(ExternalNavTarget.Destination(NavDestination.DASHBOARD), visible))
        assertEquals(
            NavDestination.SUPPLY,
            resolveExternalDestination(ExternalNavTarget.Destination(NavDestination.SUPPLY), visible)
        )
    }
}
