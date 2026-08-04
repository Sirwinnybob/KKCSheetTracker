# PDF Viewer Battery Optimization — Design

**Date:** 2026-08-04

## Problem

PDF pages are not rendered every animation frame, but the viewers keep doing avoidable background work: one-second markup directory scans, full-resolution adjacent rendering, CNC-wide ML Kit OCR prewarming, and hidden split panes that remain composed in fullscreen.

## Approved behavior

- Treat CNC sidecar metadata as authoritative. Do not run or prewarm OCR on the tablet. Tagged-v1 sidecars may intentionally omit legacy OCR boxes.
- Refresh markup initially, when the selected page/document changes, after local writes, and from filesystem events. Remove one-second viewer polling.
- In split fullscreen, compose only the visible pane.
- Render adjacent CNC pages at 0.5x native PDF size. Render the current unzoomed page at 1x. A cached adjacent page must be promoted to 1x when selected.
- Prepare the expensive embedded diagram only for the current page, not adjacent prewarm pages.
- Close `ParcelFileDescriptor`, `PdfRenderer`, and `PdfRenderer.Page` on success, cancellation, and failure.

## Verification

- Pure unit tests cover render scale and cache promotion rules.
- Existing viewer and markup-store unit tests remain green.
- Debug Kotlin compilation and the debug APK build succeed.
- Source checks confirm that viewer polling and ML Kit text-recognition calls are gone.

