# Code review: `main...HEAD` (specialty kanban, pill action row, card depth)

Reviewed 2026-09-28 by ten parallel review agents; findings merged and deduplicated.
Nothing was built, run or fixed as part of this review.
Paths are under `app/src/main/java/com/kkc/sheettracker/` unless given in full.
**New** means introduced on this branch; **Pre-existing** means `main` already had it.

## Bugs

| # | Where | Finding | Status |
|---|---|---|---|
| B1 | `ui/specialty/SpecialtyJobDetailScreen.kt:283-313` | A tick can stay shown as saved when nothing was written. `produceState` keeps the old list when a reload returns an equal one, so neither clear condition fires. It happens in archive and view-only (read-only store) and when this tablet's write loses the timestamp merge to another tablet (clock skew). The count and the kanban card position show the fake state too. | New (regression) |
| B1a | same, `:291` | The "newer reload" check uses the order reloads arrive in, not the order they read the file. A reload started before the save can clear the tick early, so the flicker can still happen, just less often. | New |
| B1b | same, `:283` | The reconcile effect is keyed on `inFlightUpdates.size`. If one save finishes in the same frame another tap starts, the size stays the same, the effect doesn't re-run, and a stale tick stays. | New |
| B1c | `data/SpecialtyProgressStore.kt:68-86` | Store cache race. A reload started before a write can fill the cache after that write clears it, so the next reload gets a stale list. | Pre-existing |
| B2 | `SpecialtyJobDetailScreen.kt:1483` | `formatSpecialtyQuantity`: `BigDecimal(Double)` throws on NaN or Infinity. The value can come from a bare `NaN` in the JSON (Python `json.dumps`) or be typed into the Qty field ("NaN", "1e999"). The job then crashes on every open, in both layouts. | New (regression) |
| B3 | `ui/specialty/SpecialtyKanbanBoard.kt:350-361` | Kanban columns can't scroll vertically. A tall card is cut off on landscape. The dims editor low in a column ends up under the keyboard, and there's no `imePadding`. | New |
| B4 | `SpecialtyKanbanBoard.kt:444` | Bottom padding is 140dp; the list uses 172dp. The bottom ~32dp of each column sits under the navbar and the Add Item button. | New |
| B5 | `ui/components/KKCSlidingPill.kt:772` | The row goes full width only when it has reference buttons, and those depend on the `SpecialtyAvailability` flags, which all start false. So the bar starts narrow, jumps to full width once the flags load, and resets its scroll. Jobs with no reference documents stay narrow. | New |
| B6 | `SpecialtyJobDetailScreen.kt:348`, delete dialog about `:688` | The sheet-rip tick and item delete write files with no `try/catch`, so an IO error crashes the app. The kanban adds new ways to reach both. | Pre-existing, more exposed |
| B7 | `SpecialtyJobDetailScreen.kt:1434/1448, 1495/1502` | The Qty field is pre-filled rounded to 4 decimals and always saved back, so editing only dims or material rewrites the quantity. | New (minor) |
| B8 | `SpecialtyKanbanBoard.kt:203-281`, list at `:939-987` | Neither layout blocks editing in archive or view-only mode. Writes are silently dropped, and B1 makes them look like they worked. | Pre-existing, amplified |

## Missing information and UX in the kanban card

| # | Where | Finding |
|---|---|---|
| I1 | `SpecialtyKanbanBoard.kt:136-141, 191, 252-260` | No Notes, Supplier, Model, Tracking or Order URL. Order Date shows only as a fallback. |
| I2 | kanban card | No Attachments menu, so attachments can't be opened from the kanban at all. |
| I3 | `:282` | No "To Order" chip. |
| I4 | `:203-211` | No "Saving..." text; the only cue is a disabled checkbox on a card that's already dimmed. |
| I5 | `:238-251` | Station chips became unlabelled 10dp colour dots. They have no content description, colour is the only way to tell done from open, and the current column's station is left out. |
| I6 | `:193-200` | Plain `Surface` at 50% alpha instead of `StatusBorderedCard`. The status border is lost and done cards drop below readable contrast. |
| I7 | `:205-210` | The checkbox has no label or merged semantics, so TalkBack reads only "Checkbox". The list's checkbox is worse: shrunk to 0.8x with no minimum touch size. |
| I8 | `:213-220` | A 32dp Delete button sits next to the main checkbox, making accidental taps likely. The confirm dialog does catch them. |
| I9 | `:465-469` | Finished sheet rips don't move to the bottom, and each row has a whole-row click plus a separate checkbox handler. |
| I10 | `:352` | Tall cards are clipped instead of scrolled (the same cause as B3). |

## Performance (low-end tablets)

| # | Where | Finding |
|---|---|---|
| P1 | `SpecialtyKanbanBoard.kt:475, 492` | `togglesById` and new toggle lists are rebuilt every board pass, so no card can skip recomposing. Fixing this is the biggest win. |
| P2 | `:208, 475`; `SpecialtyJobDetailScreen.kt:283` | One tick causes about 5 full-board passes: roughly 750 card recompositions at 150 cards, landing in the same frames as the move animation. Every card also reads the whole `inFlightUpdates` map. |
| P3 | `SpecialtyJobDetailScreen.kt:179-200, 341` | Any sync on any job recomposes the whole board: reloads keyed on global version counters, `.filter{}` making new lists, and `sheetRipDone` re-read on the main thread. |
| P4 | `SpecialtyStateStore.kt:99-102` → `HardwoodsProgressStore.kt:537-539` | `getSheetRipStoredDoneCount` copies the job's whole map on every call, 2n times per pass. On a cache miss it reads the tracker files on the main thread during composition. |
| P5 | `SpecialtyKanbanBoard.kt:426-432, 451` | The column-by-column build recomposes the whole board once per added column, so opening the board costs more, not less. |
| P6 | `:350-351` | `animateBounds` inside a `LookaheadScope` on every card measures each bucket twice and runs by default. The board also ignores the low-end lazy-loading flag. |
| P7 | `SpecialtyJobDetailScreen.kt:283` | The reconcile effect reads the map at screen level, so the whole screen recomposes twice per tick. |
| P8 | `SpecialtyJobDetailScreen.kt:261` | `actionRow` isn't remembered, and its full-width layout re-subcomposes on every screen pass. |
| P9 | `SpecialtyKanbanBoard.kt:508` | Reading `board.viewportPx` in composition costs one extra full-board pass on first layout and on rotation. |
| P10 | `:187, 191`; `SpecialtyJobDetailScreen.kt:1283` | Each card allocates on every pass: `orderSpecialtyStations` rebuilds the station order and an index map, and `item.copy(material = null)` copies the item. |

## Cleanups

| # | Where | Finding |
|---|---|---|
| C1 | `KKCSlidingPill.kt:751-791` | `dividerAfterIndex` and `pillActionRowDividerAfter` are dead code, and the divider is ignored whenever trailing actions exist. The helper sits between `KKCPillActionRow` and its KDoc, so the row is undocumented. The only new test covers this dead helper; the trailing layout has none. |
| C2 | `KKCSlidingPill.kt:794-800` | `BoxWithConstraints` plus `widthIn(min = maxWidth)` is probably redundant, and it breaks if the row is ever placed in an unbounded-width parent. |
| C3 | `KKCSlidingPill.kt:808-814` | The inner group rows have no `verticalAlignment`. |
| C4 | `KKCSlidingPill.kt:45-47` | New imports are out of order. |
| C5 | trailing pill layout | When the row scrolls, the right-hand group starts off-screen with no fade or other hint. |
| C6 | `SpecialtyKanbanBoard.kt:143-152` | `SpecialtyActionRowSpec` / `specialtyActionRow` only pass their lists through, and their test can't fail. |
| C7 | `SpecialtyJobDetailScreen.kt:281-323` | The flicker fix is in the wrong layer. `AssemblyJobDetailScreen.kt:186-210` still flickers and duplicates the handler, and so do `CompactSpecialtySection` and `AssemblyViewerScreen`. Fix it in `SpecialtyStateStore`, or share one handler. |
| C8 | `SpecialtyKanbanBoard.kt:305-365, 405-510` | Copies the Supply board's column row and frame, and the copies already diverge on low-end handling. `KANBAN_CARD_WIDTH` repeats Supply's literal `300.dp`. |
| C9 | `SpecialtyKanbanBoard.kt:89-101` | `SpecialtyKanbanColumn` has the same fields as `SpecialtyDetailSection`. |
| C10 | `SpecialtyKanbanBoard.kt:189-191, 283-291` | Copies the list's dims/quantity branching with a slightly different predicate. The `item.copy(material = null)` hack only exists to steer a helper. |
| C11 | `SpecialtyJobDetailScreen.kt:398-462` vs `473-500` | The kanban branch copies the list's summary text, error text and empty-state text. |
| C12 | `SpecialtyKanbanBoard.kt:155-156` | The color constants are `internal` but could be `private`. |
| C13 | `CompactSpecialtySection.kt:310`, `AddSpecialtyItemSheet.kt:114` | `formatSpecialtyQuantity` isn't used here, so float noise still shows in the Edit sheet. |
| C14 | `CardDepth.kt:27-49` | `kkcCardDepth` hardcodes 4dp and makes every caller pass `shadowsDisabled`. `BoardCard`, `DashboardSurfacePrimitives` and `SettingsScreen` could adopt it. |
| C15 | `SpecialtyKanbanBoard.kt:320` | The column frame uses its own shadow instead of `kkcCardDepth`, so low-end tablets get no edge. Supply's column ignores the low-end setting entirely. |
| C16 | `JobBoardGrid.kt:291`, `CardDepth.kt:19-20` | Thumbnail cards previously had no shadow; they now get one in dark mode and a border in low-end mode. The KDoc wrongly says dark mode "keeps" its shadow. |
| C17 | `UnifiedJobsScreen.kt:666` | Pinned cards in grid view don't pass `gridLayout = true`, so they look flat next to the lifted grid cards. |
| C18 | `UnifiedJobsScreen.kt:1115-1130` | During the pin-flight crossfade, the two card copies have different depth, so the shadow doubles and shifts. |
| C19 | `CardDepth.kt:14, 38-39` | Shadow colours are ignored below API 28, and `minSdk` is 26. |
| C20 | `UnifiedJobCard.kt:203` vs `StatusComponents.kt:212` | The fixed 12dp outer shape doesn't match the theme's shape. The new border covers 1dp of the status accent bar. |
| C21 | `SpecialtyJobDetailScreen.kt:22, 39, 95`; `UnifiedJobCard.kt:6, 39` | Unused imports. |
| C22 | `SpecialtyJobDetailScreen.kt:315, 329` | `catch (_: Exception)` swallows `CancellationException` and writes state on a disposed screen. Pre-existing. |
| C23 | `SpecialtyKanbanBoard.kt:475-477, 492` | Duplicate item ids would share one checkbox state without crashing. The ids appear unique today. |
| C24 | `SpecialtyKanbanBoard.kt:112` | The `singleOrNull()` fallback in `kanbanColumnToggle` fails silently. Not reachable today. |
| C25 | `SpecialtyKanbanBoard.kt:390, 424` | The pill row is wrong for one frame after a column appears or disappears. Supply has the same pattern. |
| C26 | `SpecialtyJobDetailScreen.kt:211-213, 376-384` | The kanban preference isn't observed, so other live instances don't update. Harmless. |
| C27 | tests | Missing: tests for the reconcile logic (it's inline in the composable), screen wiring, multi-station toggles, the per-column count, NaN quantities and the trailing pill layout. The quantity test sits in the kanban test file and uses fully qualified names. |
| C28 | `app/build.gradle.kts:23-24` | The version bump (8.6.0 → 8.6.1) went into the feature commit rather than a separate chore commit. |

## Plan and spec docs (`docs/superpowers/plans/2026-09-24-specialty-kanban.md`)

| # | Finding |
|---|---|
| D1 | Describes the removed divider, the Print pill and `SpecialtyActionRowSpec(actions, dividerAfterIndex)`. Task 9's checklist (line 1240) would fail correct behaviour. |
| D2 | Line 519 removes the override on save, which would bring back the bounce this branch fixed. |
| D3 | Describes the old 260dp LazyColumn design, `animateItem` and the shrunken checkbox. The spec still says "not implemented". |
| D4 | Says the work is on the "main" branch, and its file map leaves out the Supply, CardDepth, JobBoardGrid, UnifiedJobCard and gradle changes. |

## Checked and fine
- The other four `KKCPillActionRow` callers behave as before.
- The new code follows CLAUDE.md's frosted-glass, shadow and Haze navbar rules.
- No kanban items go missing and no list keys collide.
- The handler refactor dropped no error handling.
- The action pills keep their gating.
- Print is still ungated, as on `main`.
