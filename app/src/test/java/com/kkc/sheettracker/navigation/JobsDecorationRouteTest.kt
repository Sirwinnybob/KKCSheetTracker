package com.kkc.sheettracker.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsDecorationRouteTest {
    @Test
    fun jobsDecorationOnlyOwnsTheJobsListRoute() {
        assertTrue(isJobsListRoute("jobs"))
        assertFalse(isJobsListRoute("job/{folderName}"))
        assertFalse(isJobsListRoute("viewer/{folderName}/{pdfFilename}/{startPage}"))
        assertFalse(isJobsListRoute("assembly/viewer/{folderName}/{assemblyPage}/{plansPage}"))
        assertFalse(isJobsListRoute(null))
    }
}
