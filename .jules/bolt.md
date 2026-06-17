
## 2026-06-09 - [Removed Massive List Allocations on Search Load]
**Learning:** Calling `.map` over the entire search index (which often contains thousands of entries) just to copy fields into an identical UI-layer data class triggers huge O(N) object allocations and slows down screen load and indexing sync. Inside search query loops, allocating Strings using `.toString()` per element also generates high GC churn.
**Action:** Use existing data models from the index directly when identical instead of mapping to view-specific wrapper classes. Precompute integer/string conversions outside loops when the search target type allows.
