# Bolt's Performance Journal — KKCSheetTracker

Journal created 2026-06-10. Only surprising or codebase-specific learnings are recorded here.

---

## 2026-06-10 — Async Badge Pattern Already Established in JobBrowserScreen

**Learning:** `JobRepository.getJobPdfCatalog()` and `HardwoodsRepository.loadHardwoodsRevisionHistory()` are engine RPC calls (dispatched via `Dispatchers.IO` in `JobBrowserScreen.JobBrowserRow`). `AssemblyJobsScreen` was calling both synchronously per card inside a `remember {}` block on the main thread — N × 2 blocking I/O calls per recomposition triggered by `filtered`, `scanState.snapshot.generation`, or `hardwoodProgressVersion`.

**Action:** When adding per-item badge data to any screen, always use the `badgeCache = remember(scanGeneration) { mutableStateMapOf() }` + `LaunchedEffect(folderName, generation)` pattern from `JobBrowserScreen`. Do NOT put `jobRepository.getJobPdfCatalog()` or `hardwoodsRepository.loadHardwoodsRevisionHistory()` calls in synchronous `remember {}` blocks.

---

## 2026-06-10 — Synchronous I/O in Detail Screen remember{} Blocks

**Learning:** `JobDetailScreen` and `HardwoodsJobDetailScreen` were blocking the composition thread with 4 × engine I/O calls inside `remember(jobFolderName) {}` at screen entry. Same anti-pattern as the prior entry, but in per-screen detail navigation rather than per-item list rows — so it wasn't caught by the badgeCache rule which focused on list screens.

**Action:** Any `remember{}` block that calls `jobRepository.*` or `hardwoodsRepository.*` must be replaced with `var state by remember(key) { mutableStateOf(false) }` + `LaunchedEffect(key) { withContext(IO) { ... } }`. The rule applies to ALL composables, not just list item rows.
