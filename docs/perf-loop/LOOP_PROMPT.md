# Perf-Loop — Agent Prompt

> **Paste this whole file as the `/loop` prompt** (no interval — self-paced).
> Each invocation is a fresh, cold-context agent. This file + `LEDGER.md` are your only memory.

## Mission

Systematically improve **speed, optimization, and correctness (bug fixes)** of the KKCSheetTracker
Android app — **one subsystem per pass** — without breaking anything and without large rewrites.

Build on what already exists. Behavior must be preserved unless you are fixing a clear bug.

## Read first (every pass, in order)

1. `docs/perf-loop/LEDGER.md` — what's done, what's next, what's half-finished.
2. `docs/perf-loop/FINDINGS.md` — parked cross-cutting items (skim; don't act unless in scope).
3. `CLAUDE.md` (repo root) — project rules. **Hard rules that apply here:**
   - Tablets run **release** builds; deploy via `.\adb-install-release.ps1` (you will NOT deploy in the loop — build+test only).
   - Frosted-glass / haze rules — do not "optimize" these into `Surface(shadowElevation)`; that reintroduces the shadow-bleed bug.
   - Hours formatted `"%.2f"`.
4. Auto-memory at `C:\Users\chadc\.claude\projects\C--Scripts-KKCSheetTracker\memory\` — especially:
   - **engine RPC pattern**: never put `engine()` I/O in synchronous `remember{}` blocks; use the `badgeCache` pattern.
   - **ProgressStore index caching**: `ensureJobIndex` trusts its cache; invalidation goes through `TrackerChangeMonitor`. Do not "fix" the cache by re-scanning eagerly.

## Per-pass procedure

1. **Pick work.** From `LEDGER.md`, take the top subsystem whose status is `pending`. If one is
   `in-progress`, a previous pass was interrupted — resume that one instead.
2. **Branch.** `git checkout perf/loop-optimization` (create from `main` if it doesn't exist:
   `git checkout -b perf/loop-optimization`). All work for the whole loop lives on this one branch.
   Mark the subsystem `in-progress` in `LEDGER.md` and commit that ledger change so the next agent
   knows where you are even if you crash.
3. **Profile the subsystem.** Read its files. Hunt the smell list below. Form a short, ordered list
   of concrete issues (highest impact first). Verify each is real before touching it — don't guess.
4. **Fix only LOW-RISK items** (see risk line). For each medium/high-risk item or migration you
   spot: write it to `FINDINGS.md` (or `migrations/<name>.md` for big migrations) and **do not apply it.**
5. **Verify gate** — run both, both must pass:
   ```
   .\gradlew.bat assembleDebug
   .\gradlew.bat testDebugUnitTest
   ```
   - Known-flaky off-device: 1 `PdfMarkup` MotionEvent unit test fails without a device (stub). That
     single failure is environmental, not a regression — see memory `pdfmarkup-motionevent-test`.
   - Any other failure: fix it, or `git restore` the offending change. **No green, no commit.**
6. **Commit** on the branch — one commit per subsystem. Message:
   `perf(<subsystem>): <one-line summary>` + body listing each change and why it's safe.
   End the body with the Co-Authored-By trailer per repo convention.
7. **Update `LEDGER.md`**: set subsystem `done`, add a Pass-Log row (date, subsystem, commit hash,
   what changed, verify result), and append any deferred items you parked.
8. **Stop.** One subsystem per pass. The next `/loop` tick picks up the next one.

When every subsystem is `done`: do one final low-risk sweep of `FINDINGS.md` for anything that has
since become low-risk, then report that the queue is complete and stop making changes.

## What to hunt (smell list)

Performance:
- I/O, file reads, or `engine()` calls on the main thread or inside `remember{}` / composition.
- Recomposition churn: unstable lambdas/params, reading mutable state too high, missing `derivedStateOf`.
- `LazyColumn`/`LazyRow` items without stable `key =`; whole-list rebuilds on small changes.
- N+1 file reads / repeated parsing that could be cached (respect existing cache/invalidation paths).
- Allocations in hot paths (per-frame, per-row, per-recomposition) that can be hoisted/`remember`ed.
- Blocking calls on a coroutine `Dispatchers.Main`; work that belongs on `Dispatchers.IO`.
- Redundant `StateFlow`/collector wiring causing duplicate work.

Bugs / correctness:
- Obvious logic errors, off-by-one, wrong-null handling, leaked coroutines/listeners, unclosed resources.
- Race conditions around scan coordinators / progress stores / change monitor.

Always match surrounding code style, naming, and the engine-RPC / badgeCache patterns already in use.

## Risk line

**LOW — apply autonomously** (localized, behavior-preserving):
- add `remember` / stable `key`s, hoist allocations, wrap a lambda, add `derivedStateOf`
- move existing I/O onto the established engine/`Dispatchers.IO` path
- fix a clearly-broken bug with an obvious, contained correction
- dead-code / redundant-recomposition removal that can't change behavior

**MEDIUM / HIGH — do NOT touch; write a proposal instead:**
- changing an on-disk data format, JSON schema, or file layout
- API/signature changes that ripple across many files
- threading-model or architecture changes
- adding or upgrading a dependency
- anything touching **sync correctness** (Syncthing, metadata engine), file schemas, or the timeclock punch rules
- any "migration" (e.g. Room, WorkManager, websockets, Paging) → `migrations/<name>.md`:
  **why recommended, expected impact, difficulty/effort, blast radius, rollback.** Then keep going.

## Hard constraints

- Don't break anything. Behavior-preserving unless fixing a named bug.
- Build on existing patterns; no large rewrites or migrations applied in the loop — only proposed.
- One subsystem and one commit per pass.
- Never deploy to tablets from the loop. Build + unit test only.
- If a subsystem turns out to be all medium/high-risk work, mark it `done` with a note, park the
  proposals, and move on — don't force a risky change to "produce something."
