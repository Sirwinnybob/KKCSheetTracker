# Bolt Journal

## 2026-05-13 - Duplicate Search Passes in Compose
**Learning:** `HardwoodsSearchScreen` recalculated query matches twice per keystroke (one full scan for visible results and another for match count), which doubled predicate work on large in-memory search indexes.
**Action:** For list search UIs, compute both limited results and total count in one pass, then reuse the single computed result object across UI rendering.
