# Continuous Ink Mode — Observations Log

Running log of bugs, risks, compute-heavy code and cleanup opportunities spotted while executing
`2026-09-23-continuous-ink-mode.md`. **Flag only — do not fix anything here that is outside the task
being executed** unless the user says so. Related plan: `docs/superpowers/plans/2026-09-23-continuous-ink-mode.md`.

Entry format (one bullet each, newest last within a section):

```
- [Task N | role] KIND — path/File.kt:line — what is wrong or heavy, and why it matters — suggested fix — in-scope: yes/no
```

- `role`: `planner`, `implementer`, `spec-review`, `quality-review`, or `controller`.
- `KIND`: `BLOCKER` (ink issue that pauses the plan — see "Ink Blocker Protocol" in the plan; include exact evidence), `BUG` (wrong behavior), `RACE` (ordering/concurrency), `PERF` (compute, allocation, IO), `RISK` (fragile, could break later), `CLEANUP` (duplication, dead code, naming), `TEST-GAP` (untested behavior).
- Mark anything you did not verify by running or reading the exact lines as `(unverified)`.

## Pre-identified while planning (from reading the code; verify before acting)

- [planner] PERF — `ui/markup/PdfMarkupUi.kt:225-239` — `eraseAt` runs `activeStrokes.mapNotNull { distanceToViewStroke(...) }` for every stroke on every erase MOVE event, including each historical sample: O(strokes × points) plus a list allocation per sample, on the UI thread. — early-out with a per-stroke bounding box, reuse a buffer, or skip history samples closer than the hit radius — in-scope: no
- [planner] PERF — `data/PdfMarkupStore.kt:174-197` (`getMergedActiveStrokesByPage`) and `:208` (`loadAllTabletMarkup`) — parses every tablet markup JSON file in the job's tracker dir on each call. Before this change it ran on every page change; after it runs once per `markupChangeGeneration`. Large jobs with many tablets/pages still re-parse everything per markup change. — cache parsed files keyed by (name, lastModified, length) — in-scope: no
- [planner] PERF (unverified) — `ui/viewer/PdfMarkupObservation.kt` + `data/PdfMarkupStore.kt:151-172` — the `FileObserver` watches `CLOSE_WRITE`/`CREATE`/etc. in the tracker dir, so this tablet's own `savePageMarkup` likely bumps `markupChangeGeneration` and triggers a full reload after every stroke. — ignore events for own file, or debounce — in-scope: no
- [planner] RACE (blocker candidate, unverified) — `UnifiedReferenceViewer.kt` load effect + `PdfMarkupPageStates.replaceAll` — if a reload (fired by the observer, see above) reads the file before an in-flight async save lands, `replaceAll` overwrites the in-memory page with stale disk data and the newest stroke vanishes until the next reload. The same window existed before for the single page, but the effect now replaces every page. Verify in Task 4/5 by drawing two strokes quickly; if reproducible, treat as `BLOCKER` per the Ink Blocker Protocol. — merge instead of replace while saves are pending, or skip reload for own writes — in-scope: no
- [planner] RACE — `ui/viewer/UnifiedReferenceViewer.kt` (persist path, formerly `persistMarkupState`) — each save is a separate `scope.launch(Dispatchers.IO)`, so two quick strokes on the same page can reach `savePageMarkup` out of order and the older snapshot can overwrite the newer one. Pre-existing; the per-page persist lambda in Task 4 keeps the same behavior. — serialize saves per page with a single-consumer channel or `Mutex` — in-scope: no
- [planner] PERF — `data/PdfMarkupStore.kt:75-101` (`savePageMarkup`) — re-reads and rewrites the whole tablet file (all pages) for every single stroke or erase, and `loadTabletMarkup` logs via `AppLog.d` on every call. — in-memory copy of own file, write-behind — in-scope: no
- [planner] CLEANUP — `ui/hardwoods/ClassicCutListTable.kt:92` and `:166` — a near-duplicate "does a finger lock the view" predicate (`allowFingerDrawing && activeTool != PAN_ZOOM`) that behaves differently from the new `shouldContinuousPaneOwnFingerGestures`. — consider one shared rule — in-scope: no
- [planner] CLEANUP — `ui/viewer/SheetViewerScreen.kt:409-520` — third copy of the local-strokes / deleted-ids / persist pattern (single-page). Could reuse `PdfMarkupPageStates`. — in-scope: no
- [planner] RISK — `ui/components/ContinuousReferencePdfPane.kt` (`ContinuousReferencePdfPaneTest`) — several tests assert on source text (`continuousPaneSource().contains(...)`), which breaks on refactors that keep behavior and passes on code that is wrong. Task 3's wiring guards follow that pattern. — behavior-level test around the handler — in-scope: no

## Task 1 — `PdfMarkupPageStates`

## Task 2 — Gesture predicates

## Task 3 — Pane gesture wiring

## Task 4 — `UnifiedReferenceViewer` wiring

## Task 5 — Verification / on-device

## Task 6 — Commit

## Triage (filled by the controller at the end)

| Entry | Action | Owner |
|---|---|---|
