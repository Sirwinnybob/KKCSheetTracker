package com.kkc.sheettracker.data.mixorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MixOrderTrainerTest {
    private fun features(random: Random, edgeMax: Int = 30, interiorMax: Int = 30) = SheetFeatures(
        smallEdge = random.nextInt(edgeMax + 1),
        smallInterior = random.nextInt(interiorMax + 1),
        smallEdgeArea = random.nextDouble() * 0.05,
        minDim = 1.0 + random.nextDouble() * 6.0,
        partCount = 5 + random.nextInt(60)
    )

    private fun record(
        id: String,
        sheets: Map<String, SheetFeatures>,
        suggested: List<String>,
        final: List<String>,
        mode: String = "smart",
        default: List<String> = suggested,
        locked: List<String> = emptyList()
    ) = MixOrderRecord(
        recordId = id,
        job = "100",
        material = "mat",
        mixFile = "mix.mix",
        tabletId = "t1",
        appliedAt = "2026-01-01T00:00:00.000+00:00",
        appVersion = "8.8.0",
        mode = mode,
        edgeBandIn = 1.0,
        smallPartIn = 6.0,
        weightsUsed = PRIOR_WEIGHTS.values,
        defaultOrder = default,
        suggestedOrder = suggested,
        finalOrder = final,
        sheets = sheets,
        lockedSheets = locked
    )

    /** A record whose final order sorts sheets by [rank] descending (stable for ties). */
    private fun rankedRecord(
        random: Random,
        id: String,
        sheetCount: Int,
        mode: String = "smart",
        rank: (SheetFeatures) -> Double
    ): MixOrderRecord {
        val sheets = (1..sheetCount).associate { "$it|R$it" to features(random) }
        val keys = sheets.keys.toList()
        val final = keys.sortedByDescending { rank(sheets.getValue(it)) }
        return record(id, sheets, suggested = keys, final = final, mode = mode)
    }

    private fun smartSheets(random: Random, count: Int): List<SmartSheet> =
        (1..count).map { SmartSheet(it, listOf("R$it"), features(random)) }

    /** Fraction of strictly-ranked sheet pairs that [order] puts in descending [rank] order. */
    private fun concordance(order: List<SmartSheet>, rank: (SheetFeatures) -> Double): Double {
        var agree = 0
        var total = 0
        for (i in order.indices) for (j in i + 1 until order.size) {
            val a = rank(order[i].features)
            val b = rank(order[j].features)
            if (a == b) continue
            total++
            if (a > b) agree++
        }
        return agree.toDouble() / total
    }

    @Test
    fun isTrainableRules() {
        val f = SheetFeatures(0, 0, 0.0, 1.0, 1)
        val sheets = mapOf("a" to f, "b" to f)
        assertTrue(isTrainable(record("1", sheets, listOf("a", "b"), listOf("a", "b"), mode = "smart")))
        assertFalse(isTrainable(record("2", sheets, listOf("a", "b"), listOf("a", "b"), mode = "default")))
        assertTrue(isTrainable(record("3", sheets, listOf("a", "b"), listOf("b", "a"), mode = "default")))
        assertFalse(isTrainable(record("4", sheets, listOf("a", "b"), listOf("b", "a"), mode = "bogus")))
    }

    @Test
    fun defaultRecordWithOnlyARemovalIsNotTrainable() {
        val f = SheetFeatures(0, 0, 0.0, 1.0, 1)
        val sheets = mapOf("a" to f, "b" to f, "c" to f)
        // Operator removed "b" (finalOrder drops it) without dragging anything.
        assertFalse(isTrainable(record("1", sheets, listOf("a", "b", "c"), listOf("a", "c"), mode = "default")))
        // A removal plus a real drag still trains.
        assertTrue(isTrainable(record("2", sheets, listOf("a", "b", "c"), listOf("c", "a"), mode = "default")))
        // A drag with no removal still trains.
        assertTrue(isTrainable(record("3", sheets, listOf("a", "b", "c"), listOf("b", "a", "c"), mode = "default")))
        // Smart records always train, removal or not.
        assertTrue(isTrainable(record("4", sheets, listOf("a", "b", "c"), listOf("a", "c"), mode = "smart")))
    }

    @Test
    fun belowMinimumReturnsPrior() {
        val random = Random(1)
        val records = (1..4).map { rankedRecord(random, "r$it", 10) { f -> f.smallInterior.toDouble() } }
        assertEquals(PRIOR_WEIGHTS, trainMixOrderWeights(records))
    }

    @Test
    fun untouchedDefaultNotTrainable() {
        val random = Random(2)
        val records = (1..10).map { rankedRecord(random, "r$it", 10, mode = "default") { f -> f.smallInterior.toDouble() } }
            .map { it.copy(defaultOrder = it.finalOrder) }
        assertTrue(records.none { isTrainable(it) })
        assertEquals(PRIOR_WEIGHTS, trainMixOrderWeights(records))
    }

    @Test
    fun learnsInteriorPreference() {
        val random = Random(3)
        val rank = { f: SheetFeatures -> f.smallInterior.toDouble() }
        val records = (1..30).map { rankedRecord(random, "r$it", 12, rank = rank) }

        val weights = trainMixOrderWeights(records)
        assertEquals(FEATURE_COUNT, weights.values.size)
        assertTrue(weights.values.all { it.isFinite() })
        // Interior now outweighs edge, though the prior ranked edge a thousand times higher.
        assertTrue("weights=$weights", weights.values[1] > weights.values[0])

        val heldOut = smartSheets(random, 20)
        val agreement = concordance(smartOrder(heldOut, weights), rank)
        assertTrue("held-out agreement $agreement, weights=$weights", agreement >= 0.9)
    }

    @Test
    fun consistentWithPriorStaysClose() {
        val random = Random(4)
        val priorRank = { f: SheetFeatures -> PRIOR_WEIGHTS.score(f) }
        val records = (1..30).map { rankedRecord(random, "r$it", 12, rank = priorRank) }

        val weights = trainMixOrderWeights(records)
        assertTrue("weights=$weights", weights.values[0] > 0.0)
        assertTrue("weights=$weights", weights.values.all { it.isFinite() })

        val heldOut = smartSheets(random, 20)
        val agreement = concordance(smartOrder(heldOut, weights), priorRank)
        assertTrue("held-out agreement $agreement, weights=$weights", agreement >= 0.95)
    }

    @Test
    fun addedOrRemovedSheetsIgnored() {
        val random = Random(5)
        val rank = { f: SheetFeatures -> f.smallInterior.toDouble() }
        val clean = (1..8).map { rankedRecord(random, "r$it", 8, rank = rank) }
        val noisy = clean.mapIndexed { index, r ->
            when (index % 3) {
                0 -> r.copy(finalOrder = listOf("ghost|X") + r.finalOrder)
                1 -> r.copy(suggestedOrder = r.suggestedOrder + "extra|Y")
                else -> r.copy(finalOrder = r.finalOrder + "ghost|Z", suggestedOrder = listOf("only|S") + r.suggestedOrder)
            }
        }
        assertEquals(trainMixOrderWeights(clean), trainMixOrderWeights(noisy))
    }

    @Test
    fun lockedSheetsAreIgnored() {
        val random = Random(6)
        val rank = { f: SheetFeatures -> f.smallInterior.toDouble() }
        val clean = (1..8).map { rankedRecord(random, "r$it", 8, rank = rank) }
        // Pin the lowest-ranked sheet to the front, as a lock would, and mark it locked.
        val locked = clean.map { r ->
            val pinned = r.finalOrder.last()
            r.copy(finalOrder = listOf(pinned) + r.finalOrder.dropLast(1), lockedSheets = listOf(pinned))
        }
        assertEquals(
            trainMixOrderWeights(clean.map { r ->
                r.copy(finalOrder = r.finalOrder.dropLast(1), suggestedOrder = r.suggestedOrder - r.finalOrder.last())
            }),
            trainMixOrderWeights(locked)
        )
    }

    @Test
    fun degenerateRecordsFallBackToPrior() {
        val f = SheetFeatures(1, 1, 0.0, 1.0, 2)
        // Identical features everywhere: no pair carries any signal.
        val records = (1..6).map { record("r$it", mapOf("a" to f, "b" to f, "c" to f), listOf("a", "b", "c"), listOf("c", "b", "a")) }
        assertEquals(PRIOR_WEIGHTS, trainMixOrderWeights(records))
        // Single-sheet records have no pairs at all.
        val single = (1..6).map { record("s$it", mapOf("a" to f), listOf("a"), listOf("a")) }
        assertEquals(PRIOR_WEIGHTS, trainMixOrderWeights(single))
    }

    @Test
    fun nonFiniteFeaturesAreSkipped() {
        val random = Random(7)
        val rank = { f: SheetFeatures -> f.smallInterior.toDouble() }
        val records = (1..10).map { rankedRecord(random, "r$it", 8, rank = rank) }.map { r ->
            val key = r.finalOrder.first()
            r.copy(sheets = r.sheets + (key to r.sheets.getValue(key).copy(minDim = Double.NaN)))
        }
        val weights = trainMixOrderWeights(records)
        assertEquals(FEATURE_COUNT, weights.values.size)
        assertTrue(weights.values.all { it.isFinite() })
    }

    @Test
    fun fastEnough() {
        val random = Random(8)
        val records = (1..500).map { rankedRecord(random, "r$it", 20) { f -> f.smallInterior + 0.1 * f.smallEdge } }

        trainMixOrderWeights(records) // warm-up
        val millis = (1..5).map {
            val start = System.nanoTime()
            trainMixOrderWeights(records)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val median = millis[2]
        assertTrue("median $median ms of $millis", median < 100.0)
    }

    @Test
    fun fewOddRecordsStayAnchoredToPrior() {
        val random = Random(9)
        val priorRank = { f: SheetFeatures -> PRIOR_WEIGHTS.score(f) }
        // Exactly the minimum: five records that all push their most edge-heavy sheet to the very end.
        val records = (1..5).map {
            val r = rankedRecord(random, "r$it", 12, rank = priorRank)
            val edgeHeaviest = r.finalOrder.first()
            r.copy(finalOrder = r.finalOrder.drop(1) + edgeHeaviest)
        }

        val weights = trainMixOrderWeights(records)
        assertTrue(weights.values.all { it.isFinite() })
        // Edge stays clearly positive and within a factor ~3 of the prior's 1.0.
        assertTrue("weights=$weights", weights.values[0] in 0.3..3.0)
        // No other feature may swing the score more over its full range (the generator's value
        // ranges: edge 30, interior 30, area 0.05, minDim 6, partCount 60) than half of edge's.
        val ranges = doubleArrayOf(30.0, 30.0, 0.05, 6.0, 60.0)
        val edgeSwing = weights.values[0] * ranges[0]
        for (k in 1 until FEATURE_COUNT) {
            assertTrue("feature $k swing ${abs(weights.values[k]) * ranges[k]} vs edge $edgeSwing", abs(weights.values[k]) * ranges[k] <= 0.5 * edgeSwing)
        }

        // Five odd mixes must not rewrite the hand rule: the 80% bar leaves room for mild drift
        // (each of 5 records contradicts the prior on 11 of its 66 pairs) but rules out a flip.
        val heldOut = smartSheets(random, 20)
        val agreement = concordance(smartOrder(heldOut, weights), priorRank)
        assertTrue("held-out agreement $agreement, weights=$weights", agreement >= 0.8)
    }

    @Test
    fun moreEvidenceMovesFurtherFromPrior() {
        val rank = { f: SheetFeatures -> f.smallInterior.toDouble() }
        fun interiorShare(count: Int): Double {
            val random = Random(10)
            val weights = trainMixOrderWeights((1..count).map { rankedRecord(random, "r$it", 12, rank = rank) })
            return weights.values[1] / (weights.values[0] + weights.values[1])
        }
        assertTrue(interiorShare(5) < interiorShare(30))
        assertTrue(interiorShare(30) < interiorShare(200))
    }

    @Test
    fun thinningIsDeterministic() {
        val random = Random(11)
        // 500 x 20 sheets is 95k pairs, beyond the pair cap, so the strided thinning is exercised.
        val records = (1..500).map { rankedRecord(random, "r$it", 20) { f -> f.smallInterior + 0.1 * f.smallEdge } }
        assertEquals(trainMixOrderWeights(records), trainMixOrderWeights(records))
        assertEquals(trainMixOrderWeights(records), trainMixOrderWeights(records.map { it.copy() }))
    }
}
