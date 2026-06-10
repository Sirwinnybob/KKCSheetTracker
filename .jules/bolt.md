# Bolt's Performance Journal — KKCSheetTracker

Journal created 2026-06-10. Only surprising or codebase-specific learnings are recorded here.

---

## 2026-06-10 — Async Badge Pattern Already Established in JobBrowserScreen

**Learning:** `JobRepository.getJobPdfCatalog()` and `HardwoodsRepository.loadHardwoodsRevisionHistory()` are engine RPC calls (dispatched via `Dispatchers.IO` in `JobBrowserScreen.JobBrowserRow`). `AssemblyJobsScreen` was calling both synchronously per card inside a `remember {}` block on the main thread — N × 2 blocking I/O calls per recomposition triggered by `filtered`, `scanState.snapshot.generation`, or `hardwoodProgressVersion`.

**Action:** When adding per-item badge data to any screen, always use the `badgeCache = remember(scanGeneration) { mutableStateMapOf() }` + `LaunchedEffect(folderName, generation)` pattern from `JobBrowserScreen`. Do NOT put `jobRepository.getJobPdfCatalog()` or `hardwoodsRepository.loadHardwoodsRevisionHistory()` calls in synchronous `remember {}` blocks.
