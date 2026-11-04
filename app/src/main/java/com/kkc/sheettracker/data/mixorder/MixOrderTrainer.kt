package com.kkc.sheettracker.data.mixorder

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.sqrt

private const val MODE_SMART = "smart"
private const val MODE_DEFAULT = "default"

// The loss sums one unit of logistic loss per record, so this is the pull toward PRIOR_WEIGHTS (in
// scaled feature space, unit-RMS pair differences) expressed as a number of pseudo-records of prior
// evidence. The prior therefore dominates with few records and fades as the history grows.
private const val L2_STRENGTH = 0.1
private const val MAX_NEWTON_STEPS = 30
private const val STEP_TOLERANCE = 1e-5
private const val LOSS_TOLERANCE = 1e-7
private const val MIN_STEP = 1e-6

// Upper bound on pairs fitted; larger histories are thinned by a fixed stride to stay fast.
private const val MAX_PAIRS = 40_000
private const val ZERO_DIFF = 1e-12

/**
 * Whether a record carries a learning signal: every smart record does, a default-order record only
 * when the user rearranged it. Fields come from JSON, so unknown or missing modes are not trainable.
 */
fun isTrainable(r: MixOrderRecord): Boolean {
    val mode: String? = r.mode
    if (mode == MODE_SMART) return true
    if (mode == MODE_DEFAULT) {
        // finalOrder omits sheets the operator removed, so compare against the default order of the kept
        // sheets: a removal alone is not a drag and carries no ordering signal.
        val kept = r.finalOrder.toSet()
        return r.finalOrder != r.defaultOrder.filter { it in kept }
    }
    return false
}

/**
 * Fits linear pairwise logistic regression on the user's final orders: for every pair of eligible
 * sheets the earlier one in `finalOrder` should outscore the later one. Eligible means present in
 * both `suggestedOrder` and `finalOrder`, described in `sheets`, and not locked.
 *
 * Training is Newton's method on a convex objective (so there is no learning rate or run-to-run
 * variation): logistic loss summed over records (each record's pairs average to one unit) plus a
 * fixed L2 pull toward [PRIOR_WEIGHTS], so anchoring is graded by evidence. Features
 * are scaled by the RMS of their pair differences and the prior is scaled the same way, so the pull
 * is consistent; weights are converted back to raw feature units before returning.
 *
 * Returns [PRIOR_WEIGHTS] with fewer than [minRecords] trainable records, when no pair carries
 * information, or when the fit is not finite.
 */
fun trainMixOrderWeights(records: List<MixOrderRecord>, minRecords: Int = 5): MixOrderWeights {
    val trainable = records.filter { isTrainable(it) }
    if (trainable.size < minRecords) return PRIOR_WEIGHTS

    val data = buildPairData(trainable) ?: return PRIOR_WEIGHTS
    val fitted = fit(data)
    return if (fitted != null && fitted.all { it.isFinite() }) MixOrderWeights(fitted.toList()) else PRIOR_WEIGHTS
}

/** Pair difference vectors (earlier minus later), scaled, with normalized per-pair weights. */
private class PairData(
    val diffs: DoubleArray,
    val weights: DoubleArray,
    val scale: DoubleArray
) {
    val count: Int get() = weights.size
}

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun eligibleVectors(r: MixOrderRecord): List<DoubleArray> {
    val suggested: List<String?> = if (r.suggestedOrder == null) return emptyList() else r.suggestedOrder
    val final: List<String?> = if (r.finalOrder == null) return emptyList() else r.finalOrder
    val sheets = r.sheets ?: return emptyList()
    val locked: List<String?> = r.lockedSheets ?: emptyList()

    val suggestedSet = HashSet<String>().apply { suggested.forEach { if (it != null) add(it) } }
    val lockedSet = HashSet<String>().apply { locked.forEach { if (it != null) add(it) } }
    val seen = HashSet<String>()
    val vectors = ArrayList<DoubleArray>()
    for (key in final) {
        if (key == null || key !in suggestedSet || key in lockedSet || !seen.add(key)) continue
        val features: SheetFeatures = sheets[key] ?: continue
        val vector = features.vector()
        if (vector.size != FEATURE_COUNT || vector.any { !it.isFinite() }) continue
        vectors += vector
    }
    return vectors
}

private fun buildPairData(records: List<MixOrderRecord>): PairData? {
    val perRecord = records.map { eligibleVectors(it) }.filter { it.size >= 2 }
    val allPairs = perRecord.sumOf { it.size.toLong() * (it.size - 1) / 2 }
    if (allPairs == 0L) return null

    // Pairs within a record are highly redundant, so very large histories are thinned by a fixed
    // stride (offset per record, so small records are not systematically dropped).
    val stride = ((allPairs + MAX_PAIRS - 1) / MAX_PAIRS).toInt()
    val capacity = perRecord.sumOf { (it.size * (it.size - 1) / 2) / stride + 1 }
    val diffs = DoubleArray(capacity * FEATURE_COUNT)
    val weights = DoubleArray(capacity)
    var pairs = 0
    var contributing = 0
    for ((recordIndex, vectors) in perRecord.withIndex()) {
        val first = pairs
        var seen = recordIndex
        for (i in vectors.indices) for (j in i + 1 until vectors.size) {
            if (seen++ % stride != 0) continue
            val a = vectors[i]
            val b = vectors[j]
            var informative = false
            val base = pairs * FEATURE_COUNT
            for (k in 0 until FEATURE_COUNT) {
                val d = a[k] - b[k]
                diffs[base + k] = d
                if (abs(d) > ZERO_DIFF) informative = true
            }
            if (informative) pairs++
        }
        val kept = pairs - first
        if (kept == 0) continue
        contributing++
        // Every record counts equally, however many sheets it has.
        val weight = 1.0 / kept
        for (p in first until pairs) weights[p] = weight
    }
    if (pairs == 0) return null

    // Weights sum to one per record, so the loss grows with the number of records while the prior
    // pull below stays fixed; the RMS scale is a per-record average.
    val total = contributing.toDouble()
    val scale = DoubleArray(FEATURE_COUNT)
    for (p in 0 until pairs) for (k in 0 until FEATURE_COUNT) {
        val d = diffs[p * FEATURE_COUNT + k]
        scale[k] += weights[p] * d * d / total
    }
    for (k in 0 until FEATURE_COUNT) {
        val rms = sqrt(scale[k])
        scale[k] = if (rms.isFinite() && rms > ZERO_DIFF) rms else 1.0
    }
    for (p in 0 until pairs) for (k in 0 until FEATURE_COUNT) diffs[p * FEATURE_COUNT + k] /= scale[k]

    return PairData(diffs.copyOf(pairs * FEATURE_COUNT), weights.copyOf(pairs), scale)
}

/** Raw-unit weights, or null when the solver breaks down. */
private fun fit(data: PairData): DoubleArray? {
    val n = FEATURE_COUNT
    val prior = DoubleArray(n) { PRIOR_WEIGHTS.values[it] * data.scale[it] }
    var w = prior.copyOf()

    var grad = DoubleArray(n)
    var hess = Array(n) { DoubleArray(n) }
    var nextGrad = DoubleArray(n)
    var nextHess = Array(n) { DoubleArray(n) }
    val candidate = DoubleArray(n)
    var current = evaluate(data, w, prior, grad, hess)
    if (!current.isFinite()) return null

    for (step in 0 until MAX_NEWTON_STEPS) {
        val direction = solve(hess, grad) ?: return null
        var slope = 0.0
        for (k in 0 until n) {
            direction[k] = -direction[k]
            slope += grad[k] * direction[k]
        }
        if (!(slope < 0.0)) break

        // Backtracking line search keeps the iteration monotone even for badly scaled data. The
        // full step (the common case) is evaluated with gradient and Hessian so the next iteration
        // can reuse them; rejected shorter steps are only priced by their loss.
        var t = 1.0
        var candidateLoss = Double.NaN
        var accepted = false
        var withDerivatives = true
        while (t >= MIN_STEP) {
            for (k in 0 until n) candidate[k] = w[k] + t * direction[k]
            candidateLoss = if (withDerivatives) {
                evaluate(data, candidate, prior, nextGrad, nextHess)
            } else {
                evaluate(data, candidate, prior, null, null)
            }
            if (candidateLoss.isFinite() && candidateLoss <= current + 1e-4 * t * slope) {
                accepted = true
                break
            }
            t /= 2.0
            withDerivatives = false
        }
        if (!accepted) break
        if (!withDerivatives) candidateLoss = evaluate(data, candidate, prior, nextGrad, nextHess)

        var largest = 0.0
        for (k in 0 until n) largest = maxOf(largest, abs(candidate[k] - w[k]))
        val improvement = current - candidateLoss
        candidate.copyInto(w)
        current = candidateLoss
        grad = nextGrad.also { nextGrad = grad }
        hess = nextHess.also { nextHess = hess }
        if (largest < STEP_TOLERANCE || improvement < LOSS_TOLERANCE) break
    }
    return w.toRaw(data.scale)
}

private fun DoubleArray.toRaw(scale: DoubleArray): DoubleArray = DoubleArray(size) { this[it] / scale[it] }

/** Objective at [w]; also fills [grad] and [hess] when given. */
private fun evaluate(
    data: PairData,
    w: DoubleArray,
    prior: DoubleArray,
    grad: DoubleArray?,
    hess: Array<DoubleArray>?
): Double {
    val n = FEATURE_COUNT
    var loss = 0.0
    if (grad != null) grad.fill(0.0)
    if (hess != null) for (row in hess) row.fill(0.0)

    val diffs = data.diffs
    for (p in 0 until data.count) {
        val base = p * n
        var margin = 0.0
        for (k in 0 until n) margin += w[k] * diffs[base + k]
        val weight = data.weights[p]
        // One exp serves the loss log(1 + exp(-margin)) and sigma(-margin), stable for either sign.
        val e = exp(-abs(margin))
        loss += weight * (ln1p(e) + if (margin < 0.0) -margin else 0.0)
        if (grad == null || hess == null) continue

        val wrong = if (margin < 0.0) 1.0 / (1.0 + e) else e / (1.0 + e) // sigma(-margin)
        val g = weight * wrong
        val h = weight * wrong * (1.0 - wrong)
        for (a in 0 until n) {
            val da = diffs[base + a]
            grad[a] -= g * da
            val ha = h * da
            for (b in a until n) hess[a][b] += ha * diffs[base + b]
        }
    }

    for (k in 0 until n) {
        val offset = w[k] - prior[k]
        loss += L2_STRENGTH * offset * offset
        if (grad != null && hess != null) {
            grad[k] += 2.0 * L2_STRENGTH * offset
            hess[k][k] += 2.0 * L2_STRENGTH
        }
    }
    if (hess != null) for (a in 0 until n) for (b in 0 until a) hess[a][b] = hess[b][a]
    return loss
}

/** Solves [matrix] x = [rhs] by Gaussian elimination with partial pivoting; null when singular. */
private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val n = rhs.size
    val a = Array(n) { matrix[it].copyOf() }
    val b = rhs.copyOf()
    for (col in 0 until n) {
        var pivot = col
        for (row in col + 1 until n) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
        if (!(abs(a[pivot][col]) > 1e-14)) return null
        if (pivot != col) {
            val rowSwap = a[pivot]; a[pivot] = a[col]; a[col] = rowSwap
            val rhsSwap = b[pivot]; b[pivot] = b[col]; b[col] = rhsSwap
        }
        for (row in col + 1 until n) {
            val factor = a[row][col] / a[col][col]
            for (k in col until n) a[row][k] -= factor * a[col][k]
            b[row] -= factor * b[col]
        }
    }
    val x = DoubleArray(n)
    for (row in n - 1 downTo 0) {
        var sum = b[row]
        for (k in row + 1 until n) sum -= a[row][k] * x[k]
        x[row] = sum / a[row][row]
    }
    return x
}
