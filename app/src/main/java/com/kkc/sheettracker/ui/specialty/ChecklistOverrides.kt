package com.kkc.sheettracker.ui.specialty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optimistic checkbox state for a specialty checklist that reloads from disk after each save.
 *
 * [values] holds ticks the user made that the stored checklist doesn't show yet, keyed by the
 * caller's control key; [inFlight] marks keys whose save is running. The checklist reloads on IO
 * after a save, so dropping a tick as soon as its save returned made the checkbox flip back for a
 * moment. Instead each load is numbered when it starts, and a tick stays until the stored value
 * matches it or a load that started after its save lands -- so a save that wrote nothing
 * (read-only stores) or lost to another tablet shows what is stored. A reload is requested after
 * every save so that load always comes. Pair with [rememberLoadedChecklist].
 */
@Stable
internal class ChecklistOverrides {
    val values = mutableStateMapOf<String, Boolean>()
    val inFlight = mutableStateMapOf<String, Boolean>()

    /** Bumped after every save; a key of the checklist load, so each save forces a reload. */
    var reloadRequest by mutableIntStateOf(0)
        private set

    private var loadsStarted = 0
    private val savedAfterLoad = HashMap<String, Int>()

    /** Numbers a checklist load as it starts. */
    fun startLoad(): Int = ++loadsStarted

    /** Drops the ticks that the load numbered [landedLoad] settles. [stored] is its stored state by key. */
    fun reconcile(landedLoad: Int, stored: Map<String, Boolean>) {
        if (values.isEmpty()) return
        values.keys.toList().forEach { key ->
            if (shouldDropChecklistOverride(values[key], stored[key], savedAfterLoad[key], landedLoad)) {
                values.remove(key)
                savedAfterLoad.remove(key)
            }
        }
    }

    /**
     * Shows [next] for [key] at once, then runs [write] on [scope]. When the write throws, the tick
     * goes (the stored value shows again) and [onError] runs before the control is re-enabled.
     */
    fun toggle(
        scope: CoroutineScope,
        key: String,
        next: Boolean,
        write: suspend () -> Unit,
        onSaved: () -> Unit = {},
        onError: suspend () -> Unit = {}
    ): Job {
        values[key] = next
        // Until this save returns, only a matching stored value may clear the tick.
        savedAfterLoad.remove(key)
        inFlight[key] = true
        return scope.launch {
            try {
                write()
                savedAfterLoad[key] = loadsStarted
                reloadRequest++
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Show what is stored again; the reload also settles any earlier pending save.
                values.remove(key)
                savedAfterLoad.remove(key)
                reloadRequest++
                onError()
            } finally {
                inFlight.remove(key)
            }
        }
    }
}

@Composable
internal fun rememberChecklistOverrides(jobFolderName: String): ChecklistOverrides =
    remember(jobFolderName) { ChecklistOverrides() }

/**
 * The job's resolved checklist items (those passing [filter]), loaded on IO whenever the scan,
 * the stored progress or [overrides] asks for a reload, and reconciled against [overrides] as each
 * load lands. [storedValues] maps the items to the stored checked state by the same keys the
 * caller uses in [overrides]. Unchanged items keep their previous instances.
 */
@Composable
internal fun rememberLoadedChecklist(
    specialtyStateStore: SpecialtyStateStore,
    jobFolderName: String,
    overrides: ChecklistOverrides,
    filter: (SpecialtyResolvedItem) -> Boolean = { true },
    storedValues: (List<SpecialtyResolvedItem>) -> Map<String, Boolean> = ::storedChecklistValues
): LoadedChecklist {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val currentFilter by rememberUpdatedState(filter)
    val currentStoredValues by rememberUpdatedState(storedValues)
    // getResolvedItems on a cache miss parses specialty_items.json + checklist.json + every
    // tablet's tracker sidecar file for this job -- and every save invalidates that cache -- so
    // this must not run synchronously on the main thread.
    val loaded by produceState(
        LoadedChecklist(emptyList(), 0),
        scanState.snapshot.generation,
        progressVersion,
        jobFolderName,
        overrides,
        overrides.reloadRequest
    ) {
        val loadSeq = overrides.startLoad()
        val previous = value.items
        val items = withContext(Dispatchers.IO) {
            reuseUnchangedResolvedItems(
                previous = previous,
                fresh = specialtyStateStore.getResolvedItems(jobFolderName).filter(currentFilter)
            )
        }
        value = LoadedChecklist(items, loadSeq)
    }
    LaunchedEffect(loaded, overrides) {
        overrides.reconcile(loaded.loadSeq, currentStoredValues(loaded.items))
    }
    return loaded
}

/** One landed checklist load. [loadSeq] is the load's start order, so every landing is distinct. */
internal data class LoadedChecklist(
    val items: List<SpecialtyResolvedItem>,
    val loadSeq: Int
)

/**
 * [fresh] with every item that equals its [previous] counterpart swapped for that previous
 * instance (and [previous] itself when nothing changed), so an unchanged reload does not hand
 * new instances to every row and kanban card and force them all to recompose.
 */
internal fun reuseUnchangedResolvedItems(
    previous: List<SpecialtyResolvedItem>,
    fresh: List<SpecialtyResolvedItem>
): List<SpecialtyResolvedItem> {
    if (previous.isEmpty()) return fresh
    val previousById = previous.associateBy { it.item.id }
    val merged = fresh.map { item -> previousById[item.item.id]?.takeIf { it == item } ?: item }
    val unchanged = merged.size == previous.size && merged.indices.all { merged[it] === previous[it] }
    return if (unchanged) previous else merged
}

/** The stored (override-free) checked state of every checklist control in [items], by control id. */
internal fun storedChecklistValues(items: List<SpecialtyResolvedItem>): Map<String, Boolean> =
    items.flatMap { checklistTogglesForItem(it, emptyMap()) }.associate { it.controlId to it.checked }

/**
 * Whether an optimistic checkbox override can go: the stored value already matches it, or its
 * save has returned ([savedAfterLoad] = loads started by then) and a load started after that has
 * landed, so the stored value reflects the save -- or shows that it wrote nothing or lost.
 */
internal fun shouldDropChecklistOverride(
    override: Boolean?,
    stored: Boolean?,
    savedAfterLoad: Int?,
    landedLoad: Int
): Boolean = override == stored || (savedAfterLoad != null && landedLoad > savedAfterLoad)
