# Project Diary

## 2026-08-17 — Retire tablet-local OCR state

Decision: KKCSheetTracker is a consumer, not a producer, of OCR/PDF-derived metadata. Ready Jobs
Watcher, Hours Tracker, and the PDF splitter own the expensive upstream work. Remove the unused
`ProgressStore` OCR memory/disk cache and its local `ocr/` pruning scan. Preserve parsing of published
sidecar `ocrBoxes` because the CNC viewer still uses those upstream bounds; rename Android helper/log
terminology where it incorrectly implies that the tablet performs OCR.

Reason: the local cache API has no callers, retains memory without a cap, and makes app-state pruning
walk obsolete directories. Keeping two potential owners for derived PDF data also violates the
project rule that each metadata stream has one canonical owner.
