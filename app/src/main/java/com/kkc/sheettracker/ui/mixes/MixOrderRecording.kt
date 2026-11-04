package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixorder.FEATURE_COUNT
import com.kkc.sheettracker.data.mixorder.MixOrderLearningStore
import com.kkc.sheettracker.data.mixorder.MixOrderRecord
import com.kkc.sheettracker.data.mixorder.MixOrderWeights
import com.kkc.sheettracker.data.mixorder.PRIOR_WEIGHTS
import com.kkc.sheettracker.data.mixorder.trainMixOrderWeights
import com.kkc.sheettracker.data.mixfiles.MixFilesCatalog
import com.kkc.sheettracker.data.mixfiles.MixFilesResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Builds and writes the learning record for an applied new mix off the caller's thread. Apply never
 * waits for this: [MixOrderLearningStore.record] is synchronous (possibly network-share) file I/O, so it
 * only ever runs inside the launched coroutine, and every failure is swallowed.
 */
internal fun launchMixOrderRecordWrite(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    record: () -> MixOrderRecord?,
    write: (MixOrderRecord) -> Unit
): Job = scope.launch(dispatcher) {
    runCatching { record()?.let(write) }
}

/** Called inside the app-owned CNC request so leaving the screen cannot lose an accepted example. */
internal suspend fun submitMixWithLearning(
    applicationScope: CoroutineScope,
    record: () -> MixOrderRecord?,
    write: (MixOrderRecord) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    submit: suspend () -> MixFilesResult<MixFilesCatalog>
): MixFilesResult<MixFilesCatalog> = submit().also { result ->
    if (result is MixFilesResult.Ok) launchMixOrderRecordWrite(applicationScope, dispatcher, record, write)
}

/** Trained weights, or [PRIOR_WEIGHTS] when training yields anything unusable. */
internal fun sanitizedSmartWeights(weights: MixOrderWeights): MixOrderWeights =
    if (weights.values.size == FEATURE_COUNT && weights.values.all { it.isFinite() }) weights else PRIOR_WEIGHTS

/** Runs [train] and keeps the prior weights if it throws or returns non-finite values. Blocking: call off the main thread. */
internal fun loadSmartWeights(train: () -> MixOrderWeights): MixOrderWeights =
    runCatching { sanitizedSmartWeights(train()) }.getOrDefault(PRIOR_WEIGHTS)

/** Reads every shared learning record and trains. Blocking: call from `Dispatchers.IO`. */
internal fun loadSmartWeights(store: MixOrderLearningStore, baseDir: File): MixOrderWeights =
    loadSmartWeights {
        runCatching { store.flushPending(baseDir) }
        trainMixOrderWeights(store.readAll(baseDir))
    }
