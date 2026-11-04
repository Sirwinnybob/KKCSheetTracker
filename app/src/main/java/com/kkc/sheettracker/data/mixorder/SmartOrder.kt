package com.kkc.sheettracker.data.mixorder

/** One weight per [SheetFeatures.vector] entry; higher scores run earlier. */
data class MixOrderWeights(val values: List<Double>) {
    init {
        require(values.size == FEATURE_COUNT) { "Expected $FEATURE_COUNT weights, got ${values.size}" }
    }
}

/** Hand rule as weights: small edge parts first, small interior parts break ties. */
val PRIOR_WEIGHTS = MixOrderWeights(listOf(1.0, 0.001, 0.0, 0.0, 0.0))

fun MixOrderWeights.score(f: SheetFeatures): Double {
    val vector = f.vector()
    var total = 0.0
    for (i in values.indices) total += values[i] * vector[i]
    return total
}

/** Stable sort by score descending, so equal scores keep the incoming (head-page) order. */
fun smartOrder(sheets: List<SmartSheet>, weights: MixOrderWeights): List<SmartSheet> =
    sheets.sortedByDescending { weights.score(it.features) }

fun smartTag(f: SheetFeatures): String = "${f.smallEdge + f.smallInterior} small · ${f.smallEdge} on edge"
