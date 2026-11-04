package com.kkc.sheettracker.data.cnc

import org.junit.Assert.*
import org.junit.Test

class MachineAccessTest {
    private val red = CncMachines.scm
    private val blue = CncMachines.scmNew
    private fun status(id: String? = null, readOnly: Boolean = false, capabilities: Set<String>? = null) =
        MixServiceStatus(setOf("pgm-edits-revert"), id, id?.let { "Name $it" }, capabilities, readOnly)

    @Test fun blueEditsMixesAndSecondPassButNeverFrz() {
        assertEquals(setOf(CncCapability.MIX_FILES, CncCapability.SECOND_PASS, CncCapability.MOVE_MATERIAL), blue.capabilities)
        assertFalse(CncCapability.FRZ_EDITING in blue.capabilities)
        val access = machineAccess(blue, status("scm-new", capabilities = setOf("MIX_FILES", "FRZ_EDITING", "SECOND_PASS")))
        assertTrue(access.mixFiles); assertTrue(access.secondPass); assertFalse(access.frz)
        assertNull(access.reason)
        assertTrue(CncCapability.FRZ_EDITING in red.capabilities)
    }

    @Test fun liveBlueStatusAllowsMixesAndSecondPassOnly() {
        val live = parseMixServiceStatus("""{"ok":true,"features":["pgm-edits-revert","pgm-edit-preview","pgm-edit-revisions"],
            "machineId":"scm-new","machineName":"Blue SCM","machineProfile":"maestro_pgm","architecture":"x64",
            "capabilities":["MIX_FILES","SECOND_PASS"],"readOnly":false,"cncRoot":"C:\\NEW CNC"}""")
        assertEquals(MachineAccess(mixFiles = true, frz = false, secondPass = true), machineAccess(blue, live, requireStatus = true))
        val redLive = parseMixServiceStatus("""{"ok":true,"features":[],"machineId":"scm","machineName":"Red SCM",
            "capabilities":["MIX_FILES","FRZ_EDITING","SECOND_PASS"],"readOnly":false}""")
        assertEquals(MachineAccess(mixFiles = true, frz = true, secondPass = true), machineAccess(red, redLive, requireStatus = true))
    }

    @Test fun moveNeedsAnExplicitServiceAdvertisement() {
        assertFalse(machineAccess(red, status()).moveMaterial) // Legacy service: no capabilities field.
        assertFalse(machineAccess(blue, status("scm-new", capabilities = setOf("MIX_FILES", "SECOND_PASS"))).moveMaterial)
        assertTrue(machineAccess(blue, status("scm-new", capabilities = setOf("MIX_FILES", "MOVE_MATERIAL"))).moveMaterial)
        assertFalse(machineAccess(blue, status("scm-new", readOnly = true, capabilities = setOf("MIX_FILES", "MOVE_MATERIAL"))).moveMaterial)
        assertFalse(machineAccess(blue, null).moveMaterial)
    }

    @Test fun oldRedServiceWithoutIdentityKeepsWorking() {
        val access = machineAccess(red, status())
        assertTrue(access.mixFiles); assertTrue(access.frz); assertTrue(access.secondPass)
        // Before any status is known Red keeps today's behaviour; the mutation boundary still verifies.
        assertTrue(machineAccess(red, null).mixFiles)
        assertFalse(machineAccess(red, null, requireStatus = true).mixFiles)
    }

    @Test fun blueRequiresAnIdentifiedService() {
        listOf(null, status()).forEach { s ->
            val access = machineAccess(blue, s)
            assertFalse(access.mixFiles); assertFalse(access.secondPass); assertFalse(access.frz)
            assertNotNull(access.reason)
        }
    }

    @Test fun identityMismatchRefusesEveryMutationWithClearMessage() {
        val swapped = machineAccess(blue, status("scm"))
        assertFalse(swapped.mixFiles); assertFalse(swapped.secondPass)
        assertEquals("machine_mismatch", swapped.code)
        assertTrue(swapped.reason!!, swapped.reason.contains("Blue SCM") && swapped.reason.contains("Name scm"))
        assertEquals("machine_mismatch", machineAccess(red, status("scm-new")).code)
    }

    @Test fun readOnlyServiceDisablesMutations() {
        val access = machineAccess(blue, status("scm-new", readOnly = true))
        assertFalse(access.mixFiles); assertFalse(access.secondPass)
        assertEquals("machine_read_only", access.code)
        assertTrue(access.reason!!.contains("read-only"))
    }

    @Test fun advertisedCapabilitiesNarrowLocalOnes() {
        val access = machineAccess(red, status("scm", capabilities = setOf("MIX_FILES")))
        assertTrue(access.mixFiles); assertFalse(access.frz); assertFalse(access.secondPass)
    }

    @Test fun parsesIdentityAndFailsClosedOnBadTypes() {
        val parsed = parseMixServiceStatus("""{"ok":true,"features":["pgm-edit-preview"],"machineId":"scm-new","machineName":"Blue SCM",
            "capabilities":["MIX_FILES","SECOND_PASS"],"readOnly":false}""")!!
        assertEquals(MixServiceStatus(setOf("pgm-edit-preview"), "scm-new", "Blue SCM", setOf("MIX_FILES", "SECOND_PASS"), false), parsed)
        assertEquals(MixServiceStatus(setOf("x")), parseMixServiceStatus("""{"ok":true,"features":["x"]}"""))
        assertTrue(parseMixServiceStatus("""{"ok":true,"features":[],"readOnly":"no"}""")!!.readOnly)
        assertEquals(emptySet<String>(), parseMixServiceStatus("""{"ok":true,"features":[],"capabilities":"MIX_FILES"}""")!!.capabilities)
        assertEquals("", parseMixServiceStatus("""{"ok":true,"features":[],"machineId":7}""")!!.machineId)
        assertNull(parseMixServiceStatus("""{"ok":false}"""))
        assertNull(parseMixServiceStatus("not json"))
    }

    @Test fun mixMachineSelectionFallsBackToRed() {
        assertEquals(blue, CncMachines.mixMachine("scm-new"))
        assertEquals(red, CncMachines.mixMachine(null))
        assertEquals(red, CncMachines.mixMachine("unknown"))
        assertEquals(listOf(red, blue), CncMachines.mixMachines)
    }
}
