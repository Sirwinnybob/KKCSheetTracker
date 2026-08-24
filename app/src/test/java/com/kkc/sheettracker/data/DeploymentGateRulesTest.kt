package com.kkc.sheettracker.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeploymentGateRulesTest {
    @Test
    fun deployedJobWithParseReadyFalseRemainsIncluded() {
        val jobDir = Files.createTempDirectory("deployment-gate-test").toFile()
        File(jobDir, ".metadata").mkdirs()
        File(jobDir, ".metadata/deployment_gate.json").writeText(
            """{"deployed":true,"parseReady":false,"hiddenFromProduction":false}"""
        )

        val decision = DeploymentGateRules.evaluate(jobDir, isDebugBuild = false)

        assertTrue("A deployed job must remain available while parseReady is false", decision.includeJob)
    }
}
