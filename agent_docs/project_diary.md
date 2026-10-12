# Project Diary

## 2026-10-12 — Keep theme reads and navigator preview work out of idle UI rendering

Theme catalogs now load through a serial IO worker, starting with the in-memory built-in theme.
Reload failures preserve the last good catalog and expose a settings message. Startup and settings
reloads must not call the file-backed catalog reader directly from composition.

Both reference PDF navigators request previews for visible rows and two neighboring rows, rather
than rendering the entire document into a bounded cache. Loading and unavailable previews use
static text. An entire-document sweep could evict visible previews and leave their indeterminate
spinners redrawing indefinitely. On SM-X800-9231, a 51-page navigator reproduced 90–94% idle CPU
before this change; the patched debug build showed 0–2% CPU and no idle redraws. Scrolling and
cabinet search were checked on the tablet.

Crash and CPU-spike JSON reports also include `employeeName`, containing the saved Settings → Me
Name/PIN value (empty when unset). Both reporters seed it from preferences before Activity startup
and update it immediately when the setting is saved or cleared. Pending reports retain the value
from the time of the event; flushing them does not replace it with the current employee.

UI stalls now include a shared `stallId` on start/detail/end files and up to four one-second-spaced
`stallSamples`. Each sample records the main-thread state/stack, observed wait category, startup
checkpoints through first pre-draw, and best-effort `/proc` CPU/load/memory readings. Sampling is
bounded and skips long watchdog scheduling gaps; those gaps remain visible in
`longestWatchdogTickGapMs`. Detail files preserve collected evidence before a possible process
termination. Accessibility/Binder waits do not trigger another thermal-status Binder query.

The separate Android accessibility-service startup wait on SM-T738U-4019 did not recur during
repeated cold starts before or after an in-place signed update on that tablet. The patched release
build's three repeat launches took 0.59–0.66 seconds with no new stall or crash reports. The cause
of the earlier platform-service wait is still unconfirmed; preserve the original spike reports
until broader production verification.

## 2026-10-12 — Bound expandable lazy-list animation

Hardwoods part groups, generated and manually added board-stock groups (including saw-rip
routing), and Specialty sheet-rip/station groups share a bounded expansion policy. Collapsed
groups contribute no row entries. During the 220 ms transition, only a viewport-sized prefix
is measured and clipped using one shared height fraction; the remaining rows join after
expansion finishes. The prefix is laid out before the animation clock starts, so initial row
composition does not consume the visible transition. Animation-disabled settings snap directly.

Observe derived viewport height rather than every lazy-list layout update to avoid a layout/
composition feedback loop at idle. Hardwoods collapse, animation and jump maps share one
document/material key function. Programmatic jumps expose their target section immediately.
Already-complete parts do not replay their completion flash on expansion; actual completion
transitions still flash. Pure tests cover visible jump indices, key identity, bounded row counts,
and completion transitions. Expansion/collapse was checked on SM-T738U-4019 for hardwoods parts,
generated rip rows, manually added Toe Skins stock rows, and Specialty sheet-rip/CNC groups;
the operator confirmed the animation.

## 2026-08-17 — Retire tablet-local OCR state

Decision: KKCSheetTracker is a consumer, not a producer, of OCR/PDF-derived metadata. Ready Jobs
Watcher, Hours Tracker, and the PDF splitter own the expensive upstream work. Remove the unused
`ProgressStore` OCR memory/disk cache and its local `ocr/` pruning scan. Preserve parsing of published
sidecar `ocrBoxes` because the CNC viewer still uses those upstream bounds; rename Android helper/log
terminology where it incorrectly implies that the tablet performs OCR.

Reason: the local cache API has no callers, retains memory without a cap, and makes app-state pruning
walk obsolete directories. Keeping two potential owners for derived PDF data also violates the
project rule that each metadata stream has one canonical owner.
