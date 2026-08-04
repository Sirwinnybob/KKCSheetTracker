# PDF Viewer Battery Optimization — Implementation Plan

**Goal:** Remove persistent PDF-viewer background work while retaining smooth navigation and metadata-backed part overlays.

1. Correct the canonical metadata ownership map for CNC sidecars.
2. Add failing unit tests for current/adjacent render quality and cache promotion.
3. Replace viewer markup polling with a scoped `FileObserver` refresh signal.
4. Remove CNC ML Kit OCR execution/prewarming and its text-recognition dependency.
5. Add quality-aware page caching, 0.5x adjacent renders, current-page diagram preparation, and structured renderer cleanup.
6. Stop composing the hidden pane in horizontal and vertical fullscreen split layouts.
7. Run focused tests, the full debug unit suite, Kotlin compilation, APK assembly, and source/diff checks.

