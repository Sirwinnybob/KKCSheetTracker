package com.kkc.sheettracker.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimecardRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun employeesRequestDoesNotTreatUnauthorizedResponseAsAnEmptySuccessfulList() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val error: Exception = try {
            TimecardRepository(server.url("/").toString().trimEnd('/')).getEmployees()
            throw AssertionError("Expected unauthorized employee request to fail")
        } catch (expected: Exception) {
            expected
        }

        assertTrue(error.message.orEmpty().contains("HTTP 401"))
    }

    @Test
    fun employeesRequestDoesNotTreatServerErrorAsAnEmptySuccessfulList() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val error: Exception = try {
            TimecardRepository(server.url("/").toString().trimEnd('/')).getEmployees()
            throw AssertionError("Expected server-error employee request to fail")
        } catch (expected: Exception) {
            expected
        }

        assertTrue(error.message.orEmpty().contains("HTTP 503"))
    }
}
