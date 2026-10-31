package com.kkc.sheettracker.data.mixfiles

import org.junit.Assert.*
import org.junit.Test

class MixGroupsTest {
    @Test fun pairsMixAndFrzCompanionsIntoOneGroup() {
        val mix = row("Oak.mix", companion = "OAK.FRZ")
        val frz = row("oak.frz", companion = "OAK.MIX")
        val group = groups(frz, mix).single()
        assertEquals("Oak", group.title)
        assertEquals(mix, group.mix)
        assertEquals(frz, group.frz)
        assertEquals(frz, group.primary)
        assertTrue(group.isInterrupted)
        assertNull(group.errorFile)
    }

    @Test fun frzOnlyIsItsOwnInterruptedGroup() {
        val frz = row("Solo.frz")
        val group = groups(frz).single()
        assertEquals("Solo", group.title)
        assertNull(group.mix)
        assertEquals(frz, group.primary)
        assertTrue(group.isInterrupted)
    }

    @Test fun revisionsJoinGroupNewestFirstLegacyLast() {
        val groups = groups(
            row("Oak.mix", companion = "Oak.frz"), row("Oak.frz", companion = "Oak.mix"),
            row("OLD1_Oak.mix", "OAK.MIX", 1), row("Oak — 2025.mix", "Oak.mix"),
            row("OLD3_Oak.mix", "Oak.mix", 3), row("Oak — 2026.mix", "Oak.mix"),
            row("OLD2_Oak.frz", "OAK.FRZ", 2)
        )
        val group = groups.single()
        assertEquals(listOf("OLD3_Oak.mix", "OLD1_Oak.mix", "Oak — 2026.mix", "Oak — 2025.mix"), group.mixRevisions.map { it.file })
        assertEquals(listOf("OLD2_Oak.frz"), group.frzRevisions.map { it.file })
    }

    @Test fun errorFileFormsGroupWithErrorFile() {
        val broken = row("Broken.mix").copy(error = "Cannot decode")
        val group = groups(broken).single()
        assertEquals(broken, group.errorFile)
        assertEquals(broken, group.primary)
        assertFalse(group.isInterrupted)
    }

    @Test fun revisionOnlyGroupPreservesRecoveryFilesWithoutAnActiveFile() {
        val latest = row("OLD3_Gone.frz", "Gone.frz", 3)
        val group = groups(row("OLD1_Gone.frz", "GONE.FRZ", 1), latest).single()
        assertEquals("GONE", group.title)
        assertNull(group.mix)
        assertNull(group.frz)
        assertEquals(listOf(latest.file, "OLD1_Gone.frz"), group.frzRevisions.map { it.file })
        assertEquals(latest, group.primary)
    }

    @Test fun companionNamesMustBeMutualAndUnrelatedFilesStaySeparate() {
        val grouped = groups(
            row("Same.mix", companion = "Same.frz"), row("Same.frz"), row("zebra.mix"), row("Apple.mix")
        )
        assertEquals(listOf("Apple", "Same", "Same", "zebra"), grouped.map { it.title })
        assertEquals(4, grouped.map { it.key }.distinct().size)
        assertTrue(grouped.all { it.mix == null || it.frz == null })
    }

    private fun groups(vararg rows: MixFileRow) = groupMixFiles(MixFilesCatalog("Job", "Oak", "rev", rows.toList()))
    private fun row(file: String, revisionOf: String? = null, number: Int? = null, companion: String? = null) = MixFileRow(
        file, if (revisionOf != null) MixFileKind.REVISION else if (file.endsWith(".frz", true)) MixFileKind.FRZ else MixFileKind.MIX,
        revisionOf, number, "sha-$file", companion, false, null, null, emptyList()
    )
}
