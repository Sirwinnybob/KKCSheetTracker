# Assembly "Go" Search — Fix Spec

Status: proposed (spec only, no source changed)
Area: KKCSheetTracker tablet — assembly viewer cabinet search / "Go"
Author: investigation follow-up

---

## 1. Root cause (confirmed)

### Dual-pane "either-null-fails" theory: **NO — not the mechanism.**

Read exactly, a one-pane miss does **not** hard-fail the whole "Go". Each pane
updates independently and the total-failure path requires *both* lookups to be
null.

`AssemblyViewerScreen.jumpToCabinet` — `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt:482-510`

```
485  val (assemblyTarget, plansTarget) = assemblyStateStore.getCabinetJumpPages(job, normalized)
486  if (assemblyTarget != null) assemblyPage = assemblyTarget      // pane moves only if its own map hit
487  if (plansTarget   != null) plansPage    = plansTarget          // independent
...
492  detectedRoom = roomForAssemblyPage(assemblyTarget ?: assemblyPage)
494  if (assemblyTarget == null && plansTarget == null) { snackbar "not found in Assembly or Plans" }
499  else { if (assemblyTarget == null) snackbar "not in Assembly Sheets"
504         if (plansTarget   == null) snackbar "not in Plans & Elevations" }
```

`resolveCabinetJump` — `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt:507-513`

```
510  val assemblyPage = assemblyCabinetToPages(index)[normalized]?.firstOrNull()
511  val plansPage    = index?.documents?.plansElevations?.cabinetToPages?.get(normalized)?.firstOrNull()
```

Two independent maps are queried: `documents.assembly.cabinetToPages` (via
`assemblyCabinetToPages`) and `documents.plansElevations.cabinetToPages`.

### The actual confirmed mechanism

The two maps **differ in membership per job** (verified on live data, e.g. job
616b: some cabinets in plans-but-not-assembly and the reverse). The indexes are
themselves dense/gapless with bare-integer keys, and `virtualCombined == base`
on all current jobs — so this is not a data-gap or a virtualCombined bug. It is
a **membership asymmetry between the two documents.**

Given that asymmetry, when the operator types a cabinet that is present in only
one document:

1. **The pane the operator is watching may silently not move.** Only the pane
   whose map contains the cabinet jumps (lines 486-487). If the operator's eyes
   are on the plans pane and the cabinet is assembly-only, the plans pane stays
   put. To the user this reads as "I pressed Go and nothing happened / it didn't
   find my cabinet" — an intermittent, *per-cabinet* failure that averages near
   ~50% precisely because membership flips cabinet-by-cabinet.

2. **A snackbar says "not in Plans & Elevations" / "not in Assembly Sheets"**
   (lines 501, 506), which the operator reads as a failure even though the other
   pane did move.

3. **Two compounding side effects when the assembly map misses**
   (`assemblyTarget == null`):
   - `contextLine` is sourced from the assembly map only
     (`resolveCabinetContext`, `FileBackedUnifiedMetadataEngine.kt:515-522`,
     uses `assemblyCabinetToPages` at line 517) → context line goes blank.
   - `detectedRoom = roomForAssemblyPage(assemblyTarget ?: assemblyPage)`
     (`AssemblyViewerScreen.kt:492`) falls back to the *current* assembly page's
     room → wrong room label / wrong 3D room, reinforcing "it didn't work."

### Secondary contributing factor: normalization asymmetry

The "Go" jump path matches the typed cabinet as a **case-sensitive, trim-only,
exact** map key (`FileBackedUnifiedMetadataEngine.kt:509`,
`resolveCabinetJump`; same in `resolveCabinetContext:517` and
`resolveCabinetParts:530`). The sibling free-text search matches the same
`cabinetNumber` field **case-insensitively**
(`computeAssemblySearchMatches`, `AssemblySearchScreen.kt:73`:
`entry.cabinetNumber.equals(query, ignoreCase = true)`). On today's bare-integer
keys this asymmetry is inert, but it means "Go" degrades harder than search the
moment any job uses a non-bare-integer id (e.g. `A1`, `01`, `Cab 3`).

---

## 2. Fix

Design goal: a "Go" navigates to the cabinet if it exists in **any** pane the
viewer currently shows; each pane independently jumps where it can; a hard "not
found" appears **only** when **no** pane has the cabinet.

The engine already returns per-pane results correctly. The real defects are in
how `jumpToCabinet` (a) communicates a partial match, (b) derives room/context
from the assembly pane even when assembly missed, and (c) leaves the watched
pane silently stale. The fix is concentrated in `jumpToCabinet` plus a small
normalization helper shared with the resolver.

### 2a. `jumpToCabinet` — graceful partial navigation + honest feedback

File: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt:482-510`

**Before (current):**

```kotlin
fun jumpToCabinet(cab: String) {
    val normalized = cab.trim()
    if (normalized.isBlank()) return
    val (assemblyTarget, plansTarget) = assemblyStateStore.getCabinetJumpPages(jobFolderName, normalized)
    if (assemblyTarget != null) assemblyPage = assemblyTarget
    if (plansTarget != null) plansPage = plansTarget

    lastSearchedCabinet = normalized
    contextLine = assemblyStateStore.getCabinetContext(jobFolderName, normalized)
    detectedRoom = roomForAssemblyPage(assemblyTarget ?: assemblyPage)

    if (assemblyTarget == null && plansTarget == null) {
        scope.launch { snackbarHostState.showSnackbar("Cabinet $normalized not found in Assembly or Plans") }
    } else {
        if (assemblyTarget == null) scope.launch { snackbarHostState.showSnackbar("Cabinet $normalized not in Assembly Sheets") }
        if (plansTarget == null)    scope.launch { snackbarHostState.showSnackbar("Cabinet $normalized not in Plans & Elevations") }
    }
}
```

**After (proposed):**

```kotlin
fun jumpToCabinet(cab: String) {
    val normalized = normalizeCabinetQuery(cab)          // see 2b
    if (normalized.isBlank()) return
    val (assemblyTarget, plansTarget) = assemblyStateStore.getCabinetJumpPages(jobFolderName, normalized)

    // Which panes are actually on screen right now?
    val assemblyVisible = firstPaneSource == PaneSource.ASSEMBLY || secondPaneSource == PaneSource.ASSEMBLY
    val plansVisible    = firstPaneSource == PaneSource.PLANS    || secondPaneSource == PaneSource.PLANS

    if (assemblyTarget != null) assemblyPage = assemblyTarget
    if (plansTarget   != null) plansPage    = plansTarget

    lastSearchedCabinet = normalized

    // Derive room/context from whichever pane actually resolved the cabinet.
    // Prefer assembly (has room+wall detail); fall back to plans page's room if assembly missed.
    contextLine = assemblyStateStore.getCabinetContext(jobFolderName, normalized)
    detectedRoom = when {
        assemblyTarget != null -> roomForAssemblyPage(assemblyTarget)
        // do NOT overwrite detectedRoom with the stale current assembly page when assembly missed
        else -> detectedRoom
    }

    val foundInAny = assemblyTarget != null || plansTarget != null
    when {
        !foundInAny ->
            scope.launch { snackbarHostState.showSnackbar("Cabinet $normalized not found in this job") }

        // Found somewhere. Only warn about a missing pane if that pane is currently on screen,
        // and phrase it as informational, not failure.
        assemblyTarget == null && assemblyVisible ->
            scope.launch { snackbarHostState.showSnackbar("Cabinet $normalized: showing Plans (no Assembly sheet)") }

        plansTarget == null && plansVisible ->
            scope.launch { snackbarHostState.showSnackbar("Cabinet $normalized: showing Assembly (no Plans page)") }

        // else: fully resolved for the visible panes → no snackbar noise
    }
}
```

Key behavior changes:
- Hard "not found" fires **only** when the cabinet is in no map (`!foundInAny`).
- A one-pane miss is surfaced only if that pane is actually visible, and as an
  informational message ("showing Plans …"), not "not in …" failure language.
- `detectedRoom` is no longer clobbered with the stale current assembly page
  when the assembly map missed — it keeps the last good room instead of showing
  a wrong one.

Optional (stronger UX, larger change — call out for review, not required for the
core fix): if a cabinet resolves in a pane that is *not currently visible*,
auto-switch that pane's source (e.g. set `firstPaneSource = PaneSource.PLANS`)
so "Go" always lands the user on a pane that has the cabinet. This changes pane
state on the user's behalf, so gate it behind product sign-off.

### 2b. Shared defensive normalization

Introduce one normalization function used by BOTH the jump/resolve path and the
free-text search, so "Go" and search treat a typed cabinet id identically.

New helper (suggested location: `FileBackedUnifiedMetadataEngine` companion or a
small `CabinetKey` util in `data`), used by
`resolveCabinetJump`/`resolveCabinetContext`/`resolveCabinetParts` and mirrored
in `AssemblySearchScreen`:

```kotlin
fun normalizeCabinetQuery(raw: String): String =
    raw.trim()
       .removePrefix("#")
       .replace(Regex("(?i)^cab(inet)?\\s*"), "")   // "Cab 3", "Cabinet 3" -> "3"
       .trim()
```

Then make the resolver lookups case/format tolerant instead of exact:
- Build a normalized view of the map keys once (keys are bare integers today, so
  `normalizeCabinetQuery` is a no-op on them — zero behavior change on live
  data), and look up the normalized typed value against normalized keys with
  `equals(..., ignoreCase = true)` semantics.
- This makes the "Go" path match the leniency of
  `computeAssemblySearchMatches` (`AssemblySearchScreen.kt:73`) so the two can
  never disagree again.

Because current keys are bare integers and `normalizeCabinetQuery("3") == "3"`,
this section is **behavior-neutral on all 14 live jobs** and purely future-proofs
non-bare-integer ids.

---

## 3. Normalization consistency (part of 2b)

- `AssemblySearchScreen.computeAssemblySearchMatches` already does
  `cabinetNumber.equals(query, ignoreCase = true)` (`AssemblySearchScreen.kt:73`).
- The jump/parts/context path uses exact, case-sensitive, trim-only keys
  (`FileBackedUnifiedMetadataEngine.kt:509, 517, 530`).
- Fix: route both through `normalizeCabinetQuery` and case-insensitive key
  comparison so "Go" and search resolve an identical set of inputs. Verified
  inert on bare-integer keys; only matters if a future job introduces alpha or
  zero-padded ids.

---

## 4. Follow-ups (do NOT fix here — separate tickets)

- **RJW marker-drop latent risk** — Ready Jobs Watcher consolidation/compaction
  path can, under some orderings, drop event markers. Currently inert on live
  data; file a separate ticket to harden the merge before it can manifest.
- **Tablet `assemblyCabinetToPages` all-or-nothing fallback** —
  `FileBackedUnifiedMetadataEngine.kt:1178-1183` returns the `virtualCombined`
  map wholesale when present and never unions it with the base
  `assembly.cabinetToPages`. Inert today because `virtualCombined == base` on all
  jobs, but it would silently drop cabinets the day a job's virtual-combined map
  is a strict subset. Separate ticket: union the two maps instead of choosing one.

---

## 5. Test plan

### Unit / logic
1. Add a test around `jumpToCabinet` decision logic (extract the pure decision
   into a testable helper if needed) covering:
   - assembly hit + plans hit → both panes move, no snackbar.
   - assembly hit + plans miss (plans pane visible) → assembly moves, plans
     stays, informational "showing Assembly (no Plans page)" snackbar, room
     derived from assembly.
   - assembly miss + plans hit → plans moves, `detectedRoom` NOT overwritten with
     stale assembly room, informational snackbar only if assembly visible.
   - both miss → single "not found in this job" snackbar, no navigation.
2. `normalizeCabinetQuery` unit tests: `"3" -> "3"`, `" 3 " -> "3"`,
   `"#3" -> "3"`, `"Cab 3" -> "3"`, `"Cabinet 3" -> "3"`; and that a bare-integer
   map lookup is unchanged.
3. Consistency test: for a sample index, every cabinet that
   `computeAssemblySearchMatches` matches for a given typed value also resolves
   via `resolveCabinetJump` (no set where search finds it but "Go" doesn't).

### Concrete repro on real divergent data (job 616b)
Precondition: confirm from the live index which cabinet numbers are
plans-only vs assembly-only for job 616b (the two maps are known to differ):
- Pick a cabinet present in **assembly but not plans** — call it `Cn_asm`.
- Pick a cabinet present in **plans but not assembly** — call it `Cn_plan`.

Steps (before fix → after fix):
1. Open job 616b in the assembly viewer with both Assembly and Plans panes shown.
2. Type `Cn_plan`, press Go.
   - Before: plans pane jumps, assembly pane silently stays, snackbar "not in
     Assembly Sheets", `detectedRoom` shows the *old* assembly page's room.
     Operator perceives failure.
   - After: plans pane jumps, informational "showing Plans (no Assembly sheet)",
     room not wrongly changed. Perceived as success.
3. Type `Cn_asm`, press Go.
   - Before: assembly pane jumps, plans stays, snackbar "not in Plans &
     Elevations". Perceived as failure.
   - After: assembly pane jumps, informational message, success.
4. Type a cabinet present in **both** → both panes jump, no snackbar (before and
   after).
5. Type a cabinet present in **neither** (e.g. a bogus number) → single "not
   found in this job" snackbar, no navigation (after).

Success criteria: every `Cn_asm` and `Cn_plan` that previously produced a
"not in …" snackbar + a stale watched-pane now navigates the pane that has the
cabinet and reads as a success. The only inputs that produce "not found" are
those in neither map.

### Regression / smoke
- Headless import smoke (`QT_QPA_PLATFORM=offscreen` equivalent build) + manual
  walkthrough per CLAUDE.md (assembly viewer is not unit-tested at the UI layer).
- Verify bare-integer jobs (any of the other 13) behave identically to today —
  `normalizeCabinetQuery` and the case-insensitive lookup must be no-ops there.
