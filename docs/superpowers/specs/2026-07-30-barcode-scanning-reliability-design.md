# Barcode Scanning Reliability Improvements

**Date:** 2026-07-30
**Project:** KKCSheetTracker — Supply module barcode scanner

---

## Problem

Users report three issues with the current barcode scanner:

1. **False positive detections** — phantom barcodes detected from patterns/textures in the environment ("out of thin air")
2. **Slow focus** — camera autofocus takes too long, especially in low-light shop conditions
3. **No visual boundary** — scan area is ambiguous; unclear where the scanner is looking

---

## Approach 1: Bolt-on Reliability (immediate)

Minimal code changes to `SupplyScannerOverlay.kt` and `ScannerReticle.kt`. No architectural changes.

### Format Restriction

Replace `BarcodeScanning.getClient()` (all formats) with `BarcodeScanning.getClient(BarcodeScannerOptions)` whitelisting only:

- `FORMAT_EAN_13`
- `FORMAT_UPC_A`
- `FORMAT_CODE_128`
- `FORMAT_CODE_39`
- `FORMAT_ITF`
- `FORMAT_QR_CODE`

Rationale: ML Kit with all formats enabled returns false positives when surface textures (wood grain, shelving, labels) happen to resemble less common symbologies like Aztec, DataMatrix, or PDF417. Restricting to known-used formats eliminates these.

### Multi-frame Verification

State: `consecutiveHits` counter tracking same-barcode sighting count.

- Frame decode → if barcode matches previous frame's barcode in ROI → increment counter
- If different or none → reset to 0
- Lock (set `lockedBarcodeValue`) only when counter reaches 3
- Kills single-frame false positives — a hallucination won't repeat consistently

### Torch Toggle

- Add `isTorchOn` boolean state
- Toggle via `camera.cameraControl?.enableTorch(isTorchOn)`
- Icon button in top bar next to Close
- More light → faster AF → faster scan

### Enhanced Scrim & Border

In `ScannerReticle.kt`:

- Scrim alpha: 0.55 → 0.75 (darker outside area)
- Solid 2dp white border line drawn around the reticle rectangle (not just corner brackets)
- "Aim barcode inside the box" instructional text centered below the reticle
- Makes non-scan area unambiguous

### Focus Config

No explicit CameraX change needed — `DEFAULT_BACK_CAMERA` already provides continuous AF. But add `Preview.Builder().setCameraSelector()` with explicit AF targeting for robustness.

---

## Approach 2: Surgical ROI (phase 2, if needed)

If false positives persist after Approach 1:

### ROI Cropping

Crop the camera frame bitmap to the reticle dimensions before passing to ML Kit `process()`. ML Kit never sees non-ROI pixels — zero chance of detection outside the scan window.

### Tap-to-Focus

Add `FocusMeteringAction` on tap coordinates. Cancels previous metering, triggers on-tap AF.

### Architecture

Only the analyzer lambda inside `ImageAnalysis.setAnalyzer()` changes — swap the frame processing logic. The reticle, torch, and format restriction carry over unchanged.

---

## Files Changed

| File | Changes |
|---|---|
| `SupplyScannerOverlay.kt` | Format restriction, multi-frame verification, torch toggle, focus config |
| `ScannerReticle.kt` | Enhanced scrim, solid border, instructional text |

---

## Out of Scope (this iteration)

- Timeout mechanism (user can close manually)
- Low-light auto-torch detection
- Success haptic feedback
- Zoom slider
- Barcode format selection UI
