package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoursNavBarMirrorWiringTest {

    private val navGraph by lazy { source("navigation/NavGraph.kt") }

    @Test
    fun everyTimecardLaunchCarriesTheNavBarPayload() {
        val calls = navGraph.lines().filter {
            it.contains("launchTimecardApp(") && !it.contains("fun launchTimecardApp(")
        }
        assertTrue("expected the Hours launch call sites, found ${calls.size}", calls.size >= 7)
        calls.forEach { assertTrue("launch without navBar payload: ${it.trim()}", it.contains("navBar = ")) }
    }

    @Test
    fun bothNavHostsBuildThePayloadOnce() {
        assertEquals(2, Regex("currentKkcNavBarPayload\\(").findAll(navGraph).count())
    }

    @Test
    fun hoursTabHostReceivesThePayload() {
        val start = navGraph.indexOf("private fun HoursTabHost(")
        assertTrue("HoursTabHost not found", start >= 0)
        assertTrue(navGraph.substring(start, start + 400).contains("navBar: KkcNavBarPayload"))
    }

    @Test
    fun launchUsesTheLowEndAwareSwitchAnimation() {
        val start = navGraph.indexOf("private fun launchTimecardApp(")
        assertTrue("launchTimecardApp not found", start >= 0)
        val body = navGraph.substring(start, minOf(start + 1500, navGraph.length))
        assertTrue(body.contains("putKkcNavBarExtras(navBar)"))
        assertTrue(body.contains("ActivityOptions.makeCustomAnimation"))
    }

    companion object {
        fun source(relative: String): String {
            var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            repeat(6) {
                val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relative")
                if (candidate.exists()) return candidate.readText()
                val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relative")
                if (direct.exists()) return direct.readText()
                dir = dir.parentFile ?: return@repeat
            }
            error("Unable to locate $relative from ${System.getProperty("user.dir")}")
        }
    }
}
