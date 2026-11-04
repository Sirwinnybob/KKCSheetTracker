package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.cnc.CncMachines
import org.junit.Assert.*
import org.junit.Test

class MaterialOwnershipTest {
    private val red = CncMachines.scm
    private val blue = CncMachines.scmNew
    private val catalog = MixFilesCatalog("711", "19mm", "rev", emptyList())
    private fun states(red: CatalogState?, blue: CatalogState?) = mapOf("scm" to red, "scm-new" to blue)

    @Test fun blueOnlyBelongsToBlue() {
        assertEquals(MaterialOwnership.Single(blue), resolveMaterialOwnership(states(CatalogState.NoFolder, CatalogState.Live(catalog))))
        // An offline Blue with a cached catalog still owns it; Red never borrows that order.
        assertEquals(MaterialOwnership.Single(blue), resolveMaterialOwnership(states(CatalogState.NoFolder, CatalogState.Offline(catalog))))
    }

    @Test fun redOtherwise() {
        assertEquals(MaterialOwnership.Single(red), resolveMaterialOwnership(states(CatalogState.Live(catalog), CatalogState.NoFolder)))
        assertEquals(MaterialOwnership.Single(red), resolveMaterialOwnership(states(CatalogState.NoFolder, CatalogState.NoFolder)))
        assertEquals(MaterialOwnership.Single(red), resolveMaterialOwnership(states(CatalogState.Loading, CatalogState.Offline(null))))
        assertEquals(MaterialOwnership.Single(red), resolveMaterialOwnership(emptyMap()))
    }

    @Test fun bothIsAmbiguousUnlessTheCallerNamedOne() {
        val both = states(CatalogState.Live(catalog), CatalogState.Outdated(catalog))
        assertEquals(MaterialOwnership.Ambiguous(listOf(red, blue)), resolveMaterialOwnership(both))
        assertEquals(MaterialOwnership.Single(blue, explicit = true), resolveMaterialOwnership(both, "scm-new"))
        assertEquals(MaterialOwnership.Single(red, explicit = true), resolveMaterialOwnership(both, "scm"))
        // Unknown restored IDs are ignored rather than silently mapped to some machine.
        assertEquals(MaterialOwnership.Ambiguous(listOf(red, blue)), resolveMaterialOwnership(both, "bogus"))
    }

    @Test fun explicitMachineWinsEvenWhenItLacksTheFolder() {
        assertEquals(MaterialOwnership.Single(red, explicit = true),
            resolveMaterialOwnership(states(CatalogState.NoFolder, CatalogState.Live(catalog)), "scm"))
    }

    @Test fun settledOnlyAfterEveryMachineAnswered() {
        assertFalse(materialOwnershipSettled(states(CatalogState.NoFolder, CatalogState.Loading)))
        assertFalse(materialOwnershipSettled(states(CatalogState.NoFolder, null)))
        assertTrue(materialOwnershipSettled(states(CatalogState.NoFolder, CatalogState.Offline(null))))
    }

    @Test fun frzSupportFollowsCapabilities() {
        assertTrue(red.supportsFrz)
        assertFalse(blue.supportsFrz)
    }
}
