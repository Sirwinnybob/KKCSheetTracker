# Core Technology

## OCR and PDF-derived metadata ownership

KKCSheetTracker does not perform OCR on Android and must not maintain a tablet-local OCR cache.
OCR, PDF splitting, and other expensive PDF-derived analysis belong to upstream PC/server workflows,
including Ready Jobs Watcher, Hours Tracker, and the PDF splitter as appropriate for the stream.

The tablet may consume durable upstream results from published metadata. In particular, legacy CNC
sidecars can contain `ocrBoxes`; those values are authoritative input used as diagram/part bounds.
Reading those fields is not tablet OCR and should remain compatible until the upstream schema is
renamed or retired. Do not add ML Kit text recognition, page OCR prewarming, local OCR JSON, or OCR
directory scanning to KKCSheetTracker.

ML Kit remains in the Android app for supply barcode scanning only.
