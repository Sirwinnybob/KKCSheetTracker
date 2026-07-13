# KKC Cross-App Audit Fix Queue

Audit date: 2026-07-12  
Status: Open handoff queue  
Primary repo: `C:\Scripts\KKCSheetTracker`

## Loop Objective (added 2026-07-12, for $loop coordination)

### Goal

Prepare KKCSheetTracker and updater-agent for the planned 2026-07-13 large release by resolving
every code-addressable audit issue that changes the Android APK, updater APK, or their
metadata/API contracts; verify each fix with regression tests and finish with a fresh release
build. Then continue through the remaining cross-app issues by priority. Leave only genuinely
external/live verification blockers open, with exact evidence and next steps.

### Read First

- `C:\Scripts\KKCSheetTracker\AGENTS.md`
- This full audit handoff (this file)
- `C:\Scripts\KKCSheetTracker\.agents\skills\kkc-metadata-map\SKILL.md` (full contents — ownership/handoff skill)
- Repo-specific AGENTS.md/CLAUDE.md files in every sibling repo before editing there
- `C:\Scripts\Hours Tracker\METADATA_AUDIT.md` when an issue overlaps its existing register

### Priority Order

Wave A — direct KKCSheetTracker/updater release content:
1. AUD-02 — updater signer policy and artifact path containment
2. AUD-08 — Android/watcher tracker ordering and atomic Lamport persistence
3. AUD-10 — atomic tablet supply status/comment writes and real timestamp ordering
4. AUD-11 — Android hub display-name parity
5. AUD-12 — updater diagnostics and protected trigger receiver

Wave B — contracts that can break or corrupt the new tablet release:
6. AUD-01 — coordinated timeclock authentication, including Android client support and a safe staged rollout
7. AUD-04 — NDJSON compaction late-append race
8. AUD-05 — watcher Syncthing-conflict NDJSON exclusion
9. AUD-06 — cross-app supply conflict-copy parity
10. AUD-09 — supply schema validation and canonical built-ins
11. AUD-03 — durable retry semantics for tablet-authored requests

Wave C — remaining server correctness:
12. AUD-07 — delete sub-seven-minute punches consistently

Release/field gate:
13. AUD-13 stays blocked until AUD-04 and AUD-05 are resolved and a real two-tablet field run is
    performed. Prepare the exact field checklist, but do not falsely mark live verification complete.

### Execution Rules

- Inspect current code and tests before trusting the audit's line numbers; code is authoritative.
- Use systematic debugging for behavioral/data-loss issues.
- Use test-driven development: reproduce failure, make the smallest contract-correct fix, rerun targeted tests.
- One issue per focused commit where practical. Include the AUD ID in commit subjects.
- After each issue: review the diff, run its targeted tests, update its handoff status, and continue.
- If one issue is externally blocked, record the exact blocker and continue with independent issues.
- Preserve unrelated dirty work. Never reset, stash, overwrite, or commit pre-existing user changes.
- Commit only changes created for this objective.
- Use isolated worktrees/branches when that is the safest way to separate existing dirty work.
- Do not deploy, publish, push, change production `Y:` metadata, bump versions, install APKs, or
  modify RTC/TrueNAS state. The user will perform the large deployment separately.
- Do not weaken a contract merely to preserve a passing legacy test.
- Cross-repo changes must remain backward-compatible through the documented rollout sequence.

### Model and Delegation Policy

- Use the strongest available model for lead integration, security design, concurrency protocols,
  cross-repo contracts, and final review.
- Use lower-cost/lower-capability models aggressively for bounded mechanical work after the lead
  defines exact ownership and acceptance tests.
- Good lower-model tasks: conflict-file predicates and fixtures for AUD-05/AUD-06; atomic-write
  conversions after the approved pattern is identified; DTO/API parsing and focused tests for
  AUD-11; manifest receiver hardening and diagnostics tests for AUD-12 after design is settled;
  documentation/status updates; read-only searches, test-gap inventory, and repetitive parity checks.
- Do not give lower models final authority over authentication architecture, signer trust,
  compaction protocol, event ordering, destructive migrations, or completion approval.
- Every lower-model change must be reviewed by the lead/strong model before integration.
- Maximum three active workers. Parallelize only disjoint write sets. Never let two agents edit
  the same unresolved module.
- If explicit model overrides are available, choose the cheapest model adequate for the bounded
  task. Do not invent model names when overrides are unavailable.

### Required Final Verification

KKCSheetTracker/updater:
- `.\gradlew.bat :app:testDebugUnitTest :updater-agent:testDebugUnitTest --rerun-tasks`
- `.\gradlew.bat assembleRelease`

Ready Jobs Watcher, if touched:
- `.\.venv\Scripts\python.exe -m pytest -q`

Hours Tracker, if touched:
- `.\backend\venv\Scripts\python.exe -m pytest backend\tests -q`
- `npm.cmd run build` when frontend code changes

timeclock-hub, if touched:
- `python -m pytest -q`

Every touched repo:
- `git diff --check`
- Inspect `git status --short`
- Confirm commits contain no unrelated user files
- Run a strongest-model final diff review for security, data loss, compatibility, and missing regression coverage

### Release-Ready Checkpoint

- AUD-02, AUD-08, AUD-10, AUD-11, and AUD-12 are resolved with targeted regression tests.
- Any Android half of AUD-01 is implemented and its server rollout sequence is explicit.
- AUD-04 and AUD-05 are resolved before R-01 field verification.
- Fresh Android/updater unit tests and `assembleRelease` pass.
- No unresolved Critical/Important review findings affect the APKs.
- The handoff contains exact commands/results, commits, remaining blockers, and the recommended deployment order.
- No deployment or publication has occurred.

Continue beyond that checkpoint through the remaining code-addressable audit issues until each is
resolved, explicitly blocked by external/live requirements, or the loop reaches its round limit.
Do not claim complete merely because broad tests pass.

## Purpose

Agent-ready findings from a source-level audit of:

- `C:\Scripts\KKCSheetTracker`
- `C:\Scripts\KKCSheetTracker\updater-agent`
- `C:\Scripts\Ready Jobs Watcher`
- `C:\Scripts\Hours Tracker`
- `C:\Scripts\timeclock-hub`

This document is a fix queue, not proof that any issue below has been repaired. The audit did not deploy to tablets, edit live `Y:` metadata, contact the RTC-1000, or refresh TrueNAS containers.

## Agent Rules

1. Run `git status --short` in every repo before editing. KKCSheetTracker and Ready Jobs Watcher already contain unrelated user changes. Preserve them.
2. Reproduce or add a failing regression test before changing behavior.
3. Keep cross-repo contracts synchronized. Do not fix only one reader/writer when another app uses the same data.
4. Update this document's issue status and record verification evidence when a fix lands.
5. Use `C:\Scripts\KKCSheetTracker\.agents\skills\kkc-metadata-map\SKILL.md` for ownership and contract routing.
6. Do not mark R-01 resolved until live two-tablet verification is complete.

Status values: `OPEN`, `IN PROGRESS - <agent> <date>`, `RESOLVED - <commit(s)>`, `WON'T FIX - <reason>`.

## P1 — Fix First

### AUD-01 — Timeclock API permits punch impersonation

- Status: **RESOLVED - hub d5444f7, Android 9c82652 (staged rollout; enforcement default OFF)**
- Repos: `C:\Scripts\timeclock-hub`, `C:\Scripts\KKCSheetTracker`
- Evidence:
  - `timeclock-hub\app.py:1155-1160` sets wildcard CORS.
  - `timeclock-hub\app.py:1291-1298` returns active employee names and PINs without authentication.
  - `timeclock-hub\app.py:1301-1332` and `:1335-1421` expose status and punch operations without authentication or rate limiting.
  - Hub binds `0.0.0.0` and is reachable across the shop VPN.
- Risk: Any reachable host can enumerate PINs and clock employees in or out. A browser-origin request is also broadly allowed by the server's CORS policy.
- Required outcome:
  - Add per-device authentication for employee, status, and punch endpoints.
  - Restrict CORS to intended clients/origins.
  - Add rate limiting and useful punch audit evidence.
  - Preserve simple tablet operation and hub discovery.
- Acceptance:
  - Unauthenticated employee/status/punch requests are rejected.
  - Valid tablet credentials preserve normal clock-in/out.
  - Invalid PIN and repeated-request behavior are covered by tests.

### AUD-02 — Updater feed can authorize an arbitrary new package

- Status: **RESOLVED - a3b5bb9**
- Repo: `C:\Scripts\KKCSheetTracker\updater-agent`
- Evidence:
  - `update\IntegrityVerifier.kt:38-43` treats an empty expected-signer list as no signer policy.
  - `update\IntegrityVerifier.kt:45-48` only compares against an installed signer when the package is already installed.
  - `update\UpdateFeedRepository.kt:28` resolves raw manifest `packageName` and `apkFile` values without canonical containment validation.
  - `docs\device_policy.example.json` currently demonstrates `expectedSignerSha256: []`.
- Risk: A writable/compromised shared feed can add a new managed package and supply any signed APK plus matching hash. `..` path segments may also escape the intended artifact directory.
- Required outcome:
  - Require a non-empty signer allowlist for every managed package.
  - Reject blank or duplicate package entries.
  - Reject artifact paths outside `.appupdates\apps\<packageName>` after canonical resolution.
  - Validate policy and manifest before building the package map.
- Acceptance:
  - Tests reject empty signer policy, duplicate packages, blank fields, and `..` path traversal.
  - Existing-package signer continuity still works.

### AUD-03 — Valid tablet requests are deleted after transient failures

- Status: **RESOLVED - 9bea06a8**
- Repos: `C:\Scripts\Hours Tracker`, `C:\Scripts\KKCSheetTracker`
- Evidence:
  - `backend\main_v2.py:943-960` unlinks production-order requests after any exception.
  - `backend\main_v2.py:1000-1029` does the same for job-board requests.
  - `backend\main_v2.py:1169-1172` deletes delivery requests after non-HTTP exceptions, including transient write/I/O failures.
- Risk: SMB outage, permissions, read failure, or master-write failure can silently discard a valid tablet edit. Some paths can partially apply before deletion.
- Required outcome:
  - Separate malformed payload errors from transient operational errors.
  - Quarantine or consume invalid requests.
  - Leave valid requests for retry after I/O, lock, or master-write failure.
  - Make retries idempotent or record applied request IDs.
- Acceptance:
  - Tests inject request-read, master-write, lock, and sync failures.
  - Valid request remains after transient failure and succeeds on retry.
  - Malformed request cannot loop forever.

### AUD-04 — NDJSON compaction has a late-append deletion race

- Status: **RESOLVED - watcher 347a54b, Hours doc 4641b09c**
- Repos: `C:\Scripts\Ready Jobs Watcher`, `C:\Scripts\KKCSheetTracker`
- Evidence:
  - `ready_jobs_watcher\metadata_cache.py:783-791` checks mtime/size and then unlinks a device stream.
  - The watcher lock does not lock the Android process that appends to that file.
- Risk: A tablet can append after the watcher stats the stream but before unlink. The new event is then lost even though it was not included in `consolidated.json`.
- Required outcome:
  - Replace stat-then-unlink with atomic stream rotation plus consolidation of the rotated file, or another acknowledgement protocol that cannot delete a late append.
  - Update stale comments in `C:\Scripts\Hours Tracker\METADATA_AUDIT.md` that claim active NDJSON streams are never deleted.
- Acceptance:
  - Deterministic test injects an append at the old stat/unlink boundary.
  - Event survives and appears exactly once after compaction.

### AUD-05 — Watcher imports Syncthing-conflict NDJSON streams

- Status: **RESOLVED - ee0c3e6**
- Repo: `C:\Scripts\Ready Jobs Watcher`
- Evidence:
  - `ready_jobs_watcher\tracker_action_stream.py:85-96` recursively globs all NDJSON event files.
  - Legacy JSON filtering at `:213-218` excludes `.sync-conflict-*`, but the NDJSON loader does not.
- Risk: Divergent conflict streams can resurrect stale CNC/hardwood progress or bad-part actions and write them into active consolidated state.
- Required outcome: Apply one case-insensitive active-file predicate to legacy JSON and NDJSON loaders.
- Acceptance: Nested `.sync-conflict-*` NDJSON fixture is excluded from replay and consolidation.

### AUD-06 — Hours Tracker supply readers import conflict copies

- Status: **RESOLVED - 8bccc850**
- Repos: `C:\Scripts\Hours Tracker`, `C:\Scripts\KKCSheetTracker`
- Evidence:
  - Android already excludes `.sync-conflict-*` in `SupplyRepository.kt:49-54`, `:87`, and `:101`.
  - Hours `backend\routes\supply_store.py:176-182` scans all matching statuses.
  - `:190-208` scans all item JSON.
  - `:344-356` scans all comment JSON.
- Risk: Web/admin state can show duplicate items/comments or choose a stale status that the tablet correctly ignores.
- Required outcome: Add a shared conflict-copy exclusion to all supply readers and destructive cleanup paths.
- Acceptance: Cross-app fixtures produce identical active items, statuses, and comments when conflict copies exist.

### AUD-07 — Under-seven-minute punch rule drifted

- Status: **RESOLVED - 25864af (live RTC reconciliation still needs field verification)**
- Repo: `C:\Scripts\timeclock-hub`
- Contract: Punches under seven minutes are deleted silently. Duration otherwise rounds up to the nearest 15 minutes.
- Evidence:
  - Sync paths at `app.py:766-769`, `:838-841`, `:860-865`, and `:887-890` retain zero-hour records.
  - Live punch path at `app.py:1395-1406` closes and retains a zero-hour record.
- Risk: SQLite/reporting contains records the documented business rule says must not exist.
- Required outcome: Apply one shared short-punch rule to live and sync paths while preserving protection against ghost open punches.
- Acceptance:
  - Sub-seven-minute live and synced punches leave no reporting record.
  - Exactly-seven-minute behavior is explicit and tested.
  - RTC reconciliation does not recreate a deleted short punch or leave an employee clocked in.

## P2 — Next

### AUD-08 — Tracker ordering differs between watcher and Android

- Status: **RESOLVED - 89c42dc**
- Repos: `C:\Scripts\KKCSheetTracker`, `C:\Scripts\Ready Jobs Watcher`
- Evidence:
  - Watcher `tracker_action_stream.py:71-82` orders by timestamp, Lamport value, event ID, and stable fallbacks.
  - CNC `ProgressStore.kt:65-83` discards Lamport/event ID during decode and sorts at `:462` only by timestamp.
  - `HardwoodsProgressStore.kt` retains Lamport but uses timestamp-only sorts at `:373`, `:811`, `:816`, and `:1190`.
  - `TrackerEventLog.kt:52-58` persists the Lamport counter with swallowed, non-atomic `writeText`.
- Risk: Equal-timestamp events from different tablets can replay differently between Android and watcher. A torn Lamport file can reset the counter after restart.
- Required outcome:
  - Preserve Lamport and event ID in Android models.
  - Use one documented total-order comparator everywhere.
  - Persist the Lamport counter atomically and log failure.
- Acceptance: Two-stream equal-timestamp fixtures resolve identically in CNC Android, hardwood Android, and watcher tests.

### AUD-09 — Supply schema accepts corrupt contracts

- Status: **RESOLVED - e3ad2b7f**
- Repos: `C:\Scripts\Hours Tracker`, `C:\Scripts\KKCSheetTracker`
- Evidence:
  - `frontend\components\JobManager\supply\SupplySchemaEditor.tsx:19-35` creates `new_field` keys and slug collisions without validation.
  - `backend\routes\supply_store.py:161-167` merges by ID but does not enforce unique/nonblank IDs and keys or canonical built-in fields.
  - Android routing in `SupplyFieldRouting.kt:25-35` assumes keys and built-in flags are trustworthy.
- Risk: Duplicate keys or modified built-ins can route values into the wrong map, overwrite data, or hide fields.
- Required outcome: Server-side schema validation/canonicalization, plus useful frontend validation errors.
- Acceptance: Backend rejects duplicate keys/IDs, blank values, unsupported types, and modified built-ins.

### AUD-10 — Tablet supply status/comment writes can tear

- Status: **RESOLVED - abc4b1c (Android), Hours: parsed-Instant get_status**
- Repos: `C:\Scripts\KKCSheetTracker`, `C:\Scripts\Hours Tracker`
- Evidence:
  - `SupplyRepository.kt:109-120` uses direct `writeText` for status and comment JSON.
  - Android `SupplyRepository.kt:53` and Hours `supply_store.py:181` compare ISO timestamps as strings.
- Risk: Partial status JSON falls back to `IN STOCK`. Lexical timestamp comparison can misorder whole-second and fractional-second Instants.
- Required outcome: Atomic status/comment writes and parsed-Instant ordering in both apps.
- Acceptance: Torn-write and fractional-timestamp tests pass; both apps choose the same latest status.

### AUD-11 — Hub display-name parity is broken on Android

- Status: **RESOLVED - 687f98a**
- Repos: `C:\Scripts\timeclock-hub`, `C:\Scripts\KKCSheetTracker`
- Evidence:
  - Hub storage uses numeric RTC `Employee.display_name` and human RTC `Employee.nickname` (`models.py:11-12`).
  - API `_effective_display_name` returns the human override in JSON key `display_name` (`app.py:209-229`).
  - Android `TimecardRepository.kt:46-63` and `:87-95` discard that API field based on an outdated numeric-ID comment.
- Risk: Browser and tablet show different employee names.
- Required outcome: Parse the API field and document precedence: Hours custom name, then hub effective name, then real name.
- Acceptance: Repository/store tests cover all precedence cases and blank overrides.

### AUD-12 — Updater failures are silent and update trigger is unprotected

- Status: **RESOLVED - eba95f3**
- Repo: `C:\Scripts\KKCSheetTracker\updater-agent`
- Evidence:
  - `UpdateWorker.kt:29-30` returns success when policy or manifest is absent/malformed, with no audit record.
  - `updater-agent\src\main\AndroidManifest.xml:46-51` exports `TriggerUpdateReceiver` without a permission.
- Risk: Update outages are hard to diagnose; any installed app can force repeated update checks.
- Required outcome:
  - Distinguish deliberate no-update, unavailable share, and corrupt policy/manifest.
  - Write audit evidence and retry only transient conditions.
  - Protect the broadcast while retaining an intentional maintenance trigger.
- Acceptance: Tests cover missing, corrupt, offline, and valid feeds; unauthorized broadcasts cannot trigger work.

## Release Gate

### AUD-13 — R-01 needs live two-tablet verification

- Status: **OPEN — code prerequisites (AUD-04, AUD-05) DONE; awaiting live two-tablet field run**
- Repos: `C:\Scripts\KKCSheetTracker`, `C:\Scripts\Ready Jobs Watcher`
- Reference: `C:\Scripts\Hours Tracker\METADATA_AUDIT.md:1062`
- Required sequence:
  1. Fix and verify AUD-04 and AUD-05. — DONE (AUD-05 ee0c3e6, AUD-04 347a54b; watcher suite 362 passed).
  2. Deploy backward-compatible Ready Jobs Watcher first.
  3. Deploy updated tablets afterward.
  4. Use two tablets on one real job to create competing CNC and hardwood events.
  5. Verify Android peers and watcher consolidation agree.
  6. Verify progress/reset/bad-part events survive restart, Syncthing propagation, and after-hours compaction.
- Acceptance: Record app/watcher versions, device IDs, event files, consolidated output, logs, and observed tablet state before marking R-01 resolved.
- Blocker: requires two physical tablets + the RTC/Syncthing environment; cannot be performed or
  simulated in this workspace, and deployment is out of scope per the loop rules. Not marked
  resolved.

#### AUD-13 field checklist (run during the real two-tablet session)

1. Record versions: tablet APK versionCode (both devices), Ready Jobs Watcher commit (>= 347a54b),
   and each tablet's `updater_tablet_id`.
2. Pick one real job. On tablet A and tablet B, open the same CNC material and the same hardwood
   cutlist.
3. Create competing events at (near-)identical timestamps:
   - CNC: both tablets mark the same sheet complete / bad-part on the same page.
   - Hardwood: both tablets set done/bad/skipped on the same row.
4. Confirm both tablets converge to the SAME rendered state (peer ndjson read), and that
   `events/<tabletId>.ndjson` on each shows both devices' events.
5. Trigger watcher consolidation; confirm `consolidated.json` equals the Android-derived state
   (total order (timestamp, lamport, eventId, ...) now shared — AUD-08).
6. Restart both tablet apps; confirm the Lamport counter did not reset (tracker_lamport.txt) and
   state is unchanged.
7. Let Syncthing propagate; confirm no `.sync-conflict-*` ndjson is replayed (AUD-05) and no event
   is lost or duplicated.
8. Run the after-hours compaction sweep; confirm each event survives exactly once and a late
   append during compaction is not lost (AUD-04 rotation).
9. Capture: app/watcher versions, device IDs, the event files, consolidated output, tablet
   screenshots, and watcher logs. Only then mark R-01/AUD-13 resolved.

## Existing Low-Priority Carryover

Keep the existing L-02 through L-14 queue in `C:\Scripts\Hours Tracker\METADATA_AUDIT.md:1017-1029`. Revalidate each before editing; AUD-10 overlaps L-11 and should supersede it when resolved.

## Verified Baseline

These commands passed during the audit before any issue fixes:

- KKCSheetTracker and updater-agent: `gradlew :app:testDebugUnitTest :updater-agent:testDebugUnitTest --rerun-tasks` — 52 tasks executed, successful.
- Ready Jobs Watcher focused tracker/cache tests — 35 passed.
- Hours Tracker request-poller tests — 18 passed, with two FastAPI startup deprecation warnings.
- timeclock-hub full current suite — 17 passed.
- Metadata-map skill validator and retrieval checks — passed.

Passing baseline tests do not invalidate the findings; the identified race, conflict, authentication, and parity cases are not covered by those suites yet.

## Done

<!-- Append an entry per completed round: what was completed, key decisions + rationale, files changed, commits, verification evidence, and learnings for future iterations. -->

### Round 1 (2026-07-12) — Wave A release-critical issues (AUD-02, 08, 10, 11, 12)

Resolved all five Wave A issues that change the Android/updater APKs, each with targeted
regression tests. Worked in the main worktree; the pre-existing dirty UI/theme/M-13 work is
disjoint from every file touched and was left untouched. One focused commit per AUD.

- **AUD-02 (updater signer/path)** — `IntegrityVerifier.kt`, `UpdateFeedRepository.kt`,
  `UpdateWorker.kt`, `docs/device_policy.example.json`, tests
  `UpdateFeedRepositoryTest.kt` + new `IntegrityVerifierSignerPolicyTest.kt`.
  Empty signer allowlist is now a hard failure (extracted pure `evaluateSignerPolicy`);
  policy/manifest reject blank/duplicate package entries (fail-closed → readPolicy/readManifest
  return null); `resolveApkFile` returns null for paths escaping `.appupdates/apps/<pkg>` after
  canonical resolution. Example policy signer `[]` replaced with a placeholder digest.
- **AUD-08 (tracker total-order + Lamport)** — `Models.kt` (added `lamport`/`eventId` to
  TrackerAction + `eventId` to HardwoodTrackerAction), `TrackerEventLog.kt` (shared
  `TRACKER_TOTAL_ORDER` / `HARDWOOD_TRACKER_TOTAL_ORDER`; atomic Lamport persist + logged
  failure), `ProgressStore.kt` + `HardwoodsProgressStore.kt` (decode/sanitize preserve
  lamport+eventId; four timestamp-only sorts each → shared comparator). Comparator key matches
  the watcher's `_sort_combined_actions` (timestamp, lamport, event_id, file, page, action); the
  watcher mapper already reads the same `lamport`/`eventId` keys Android writes — no watcher
  change needed. Tests added to `TrackerEventLogTest.kt`.
- **AUD-10 (atomic supply writes + Instant ordering)** — Android `SupplyRepository.kt`
  (setStatus/addComment → `atomicWriteFile`; status/comment recency via parsed-Instant
  comparator) + `SupplyRepositoryTest.kt`; Hours `backend/routes/supply_store.py`
  (`_parse_instant` + `get_status` orders by parsed instant) + new
  `test_supply_store_status_ordering.py`. Both apps now pick the same latest status.
- **AUD-11 (hub display-name parity)** — `TimecardRepository.kt` parses hub `display_name`
  (the human `_effective_display_name`, confirmed in `timeclock-hub/app.py`);
  `TimecardStore.kt` resolves via `resolveDisplayOverride` (Hours custom → hub effective → real
  name); new `TimecardDisplayNameTest.kt`.
- **AUD-12 (updater diagnostics + protected trigger)** — `Model.kt` (`FeedState`),
  `UpdateFeedRepository.classifyFeed`, `UpdateWorker` (audit + retry only transient; silent
  success removed), `AndroidManifest.xml` (signature-level
  `com.kkc.updateragent.permission.TRIGGER_UPDATE` gating the receiver); new
  `UpdateFeedClassifyTest.kt`.

Verification: `:app:testDebugUnitTest` + `:updater-agent:testDebugUnitTest` BUILD SUCCESSFUL
(52 tasks); Hours `pytest test_supply_store_status_ordering.py test_sync_conflict.py` → 13
passed. `assembleRelease` + full `--rerun-tasks` run deferred to end-of-round gate.

Commits (KKCSheetTracker): AUD-02, AUD-12, AUD-11, AUD-10, AUD-08 (one each, "fix(AUD-xx): ...").
Commit (Hours Tracker): "fix(AUD-10): parsed-Instant status ordering in supply reader".

Learnings: `isReturnDefaultValues = true` in app/build.gradle makes `android.util.Log` safe in
JVM unit tests. Extracting pure functions (evaluateSignerPolicy, classifyFeed,
resolveDisplayOverride, comparators) is the reliable way to unit-test logic that otherwise needs
PackageManager/Context/network. Hours Tracker working tree was clean (audit's "unrelated user
changes" not present now).

### Round 2 (2026-07-12) — Wave B + Wave C (AUD-01, 03, 04, 05, 06, 07, 09)

Resolved every remaining code-addressable issue. AUD-13 stays open as a live field-verification
blocker only (two physical tablets + RTC/Syncthing; out of scope to deploy here). Its exact field
checklist is now recorded above.

- **AUD-05 (watcher conflict NDJSON)** ee0c3e6 — shared `_is_active_stream_file` predicate applied
  to both legacy JSON and NDJSON loaders, checking every path segment (nested conflicts + conflicted
  sub-dirs). `tests/test_tracker_action_stream.py`.
- **AUD-04 (compaction late-append race)** watcher 347a54b, Hours doc 4641b09c — `_consolidate_tracker`
  atomically rotates each ndjson stream into a hidden `.compacting-*` dir before reading,
  consolidates the snapshot, then drops it; late appends land in a fresh stream and survive;
  `_restore_rotated_streams` guards consolidation failure. Rewrote the stale-guard test to assert
  late-append survival. Updated METADATA_AUDIT.md M-10 sign-off.
- **AUD-06 (Hours supply conflict copies)** 8bccc850 — `is_sync_conflict` exclusion added to
  get_items/get_status/get_comments. `test_supply_store_conflict_exclusion.py`.
- **AUD-09 (supply schema validation)** e3ad2b7f — `validate_and_canonicalize_schema` rejects
  duplicate/blank ids+keys, unsupported types, reserved-key collisions, and modified built-ins
  (400); always stores canonical built-ins. Frontend `SupplySchemaEditor.tsx` adds client-side
  validation + surfaces the backend error. `test_supply_store_schema_validation.py`; `npm run build` OK.
- **AUD-07 (short-punch rule)** hub 25864af — centralized `SHORT_PUNCH_MINUTES`/`_is_short_punch`;
  sync deletes existing sub-7 records and skips creating them (no RTC re-creation); live path deletes
  when never pushed to RTC (pid_in None), else ghost-safe close; exactly-7 kept. `test_short_punch_rule.py`.
  NOTE: full live RTC reconciliation behavior needs field verification (documented on the issue).
- **AUD-03 (durable retry)** 9bea06a8 — all three request pollers separate malformed (quarantine to
  `<name>.rejected`) from transient (leave for retry); applies are idempotent. `test_request_retry_semantics.py`.
- **AUD-01 (timeclock auth)** hub d5444f7, Android 9c82652 — `X-Hub-Token` device auth on
  employees/status/punch gated by `HUB_REQUIRE_AUTH` (default OFF for staged rollout), CORS
  restricted to `HUB_ALLOWED_ORIGINS` (native tablets unaffected), sliding-window rate limiting (429),
  punch audit logging. Android sends the token from `TimecardServerConfig` (empty until provisioned).
  `test_device_auth.py`.

Final verification (all green):
- KKCSheetTracker/updater: `:app:testDebugUnitTest :updater-agent:testDebugUnitTest --rerun-tasks`
  — BUILD SUCCESSFUL, 52 tasks. `assembleRelease` — BUILD SUCCESSFUL.
- Ready Jobs Watcher: `pytest -q` — 362 passed.
- Hours Tracker: `pytest backend/tests -q` — 205 passed; `npm run build` — compiled successfully.
- timeclock-hub: `pytest -q` — 33 passed.
- `git diff --check` clean (LF/CRLF warnings only); each repo's remaining dirty files are pre-existing
  user work (KKC UI/theme/M-13 + spec .md; watcher cabinet_sheet_indexer.py + its test). No audit
  file left uncommitted; no unrelated user file committed.

Recommended deployment order (user performs; nothing deployed here):
1. Ready Jobs Watcher (backward compatible; enables AUD-04/05 and is the R-01 prerequisite).
2. timeclock-hub with `HUB_DEVICE_TOKENS` set and `HUB_REQUIRE_AUTH=0`, plus `HUB_ALLOWED_ORIGINS`
   for any browser widget/admin origin (AUD-01 phase 1; also ships AUD-07 short-punch + AUD-09 has no
   hub part). Hub tolerates old tablets.
3. Tablet APK (app + updater): ships AUD-02/08/10/11/12 and the AUD-01 client that sends the token.
   Provision each tablet's `hub_device_token`.
4. After all tablets updated + verified sending the token, set `HUB_REQUIRE_AUTH=1` to enforce.
5. Hours Tracker backend + frontend (AUD-03/06/09/10 server halves) — order relative to tablets is
   flexible (contracts are backward compatible).
6. Then run the AUD-13 two-tablet field checklist above before closing R-01.

Learnings: Rotation-before-read is the clean fix for stat-then-unlink TOCTOU (AUD-04) and keeps the
merged action set identical to a single pre-rotation read. Security rollouts must default enforcement
OFF and validate-but-don't-require during the transition (AUD-01) so hub-first deploys never break
live tablets. Quarantine-not-delete (AUD-03) preserves evidence while breaking infinite loops.

## Confirmed Parity

- Built-in supply schema matches between Hours Tracker and Android.
- Production-order, job-board, and delivery per-tablet request filenames/locations match.
- Timecard `hazeState` remains owned by `TimecardScreen` and passed downward.
- Hour displays use two decimal places.
- Hub duration calculation still rounds up to the nearest 15-minute increment.

