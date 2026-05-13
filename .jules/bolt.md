# Bolt Journal

## 2026-05-13 - Duplicate Search Passes in Compose
**Learning:** `HardwoodsSearchScreen` recalculated query matches twice per keystroke (one full scan for visible results and another for match count), which doubled predicate work on large in-memory search indexes.
**Action:** For list search UIs, compute both limited results and total count in one pass, then reuse the single computed result object across UI rendering.

## 2026-05-13 - Uncapped 3D + Sync Bursts Caused Cross-Layer Jank
**Learning:** For this tablet/WebView stack, running the 3D viewer continuously at full speed while also allowing startup/live tracker invalidation bursts caused render-thread saturation and multi-second derivation churn; either change alone looked tolerable, but together regressed smoothness.
**Action:** Keep 3D interaction-first scheduling (continuous only during interaction/settling) and batch/coalesce tracker invalidations with startup warm-up to prevent synchronized render + derive spikes.
